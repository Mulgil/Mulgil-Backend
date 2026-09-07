package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.indexing.ContentIndexingService;
import com.pgvector.PGvector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
final class SelectedTopicRetrievalService {
    private final JdbcClient jdbc;
    private final ObjectProvider<ChunkEmbeddingPort> embeddings;
    private final MulgilProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    SelectedTopicRetrievalService(JdbcClient jdbc, ObjectProvider<ChunkEmbeddingPort> embeddings,
                                  MulgilProperties properties, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    Retrieval retrieve(UUID ownerId, UUID courseId, UUID sessionId, String query,
                       SelectedTopicGenerationService.Intent intent, int topK, List<UUID> sourceScope,
                       SelectedTopicGenerationService.ScopeExpansion expansion) {
        long started = System.nanoTime();
        String failure = null;
        String model = null;
        int candidates = 0;
        List<SelectedChunk> selected = List.of();
        try {
            validateScope(ownerId, courseId, sessionId, sourceScope, expansion);
            ChunkEmbeddingPort port = embeddings.getIfAvailable();
            if (port == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE", "Embedding provider unavailable.");
            ChunkEmbeddingPort.Embedding embedding = port.embed(query);
            model = embedding.model();
            int candidateLimit = Math.multiplyExact(topK, properties.retrieval().candidateMultiplier());
            List<SelectedChunk> ranked = ranked(ownerId, courseId, sessionId, embedding.values(),
                    sourceScope, candidateLimit);
            candidates = ranked.size();
            selected = selectDiverse(ranked, topK);
            if (selected.size() < topK && sourceScope != null
                    && expansion == SelectedTopicGenerationService.ScopeExpansion.SESSION) {
                ranked = ranked(ownerId, courseId, sessionId, embedding.values(), null, candidateLimit);
                candidates = ranked.size();
                selected = selectDiverse(ranked, topK);
            }
            if (selected.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,
                    "RETRIEVAL_NOT_READY", "No indexed source matches the selected scope.");
            return new Retrieval(selected, candidates, model);
        } catch (ApiException exception) {
            failure = exception.code();
            throw exception;
        } catch (RuntimeException exception) {
            failure = "RETRIEVAL_FAILED";
            throw exception;
        } finally {
            record(ownerId, query, intent, topK, candidates, selected, model, failure,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
        }
    }

    void validateScope(UUID ownerId, UUID courseId, UUID sessionId, List<UUID> scope,
                       SelectedTopicGenerationService.ScopeExpansion expansion) {
        boolean owns = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM class_sessions session
                    JOIN courses course ON course.id=session.course_id AND course.owner_id=session.owner_id
                    WHERE session.owner_id=:owner AND session.course_id=:course AND session.id=:session
                      AND course.deleted_at IS NULL)
                """).param("owner", ownerId).param("course", courseId).param("session", sessionId)
                .query(Boolean.class).single();
        if (!owns) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found.");
        if (scope == null) {
            if (expansion != SelectedTopicGenerationService.ScopeExpansion.NONE) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_SCOPE",
                        "Scope expansion requires an explicit source scope.");
            }
            return;
        }
        if (scope.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_SCOPE",
                "Source scope must not be empty.");
        Set<UUID> current = new LinkedHashSet<>(jdbc.sql("""
                SELECT DISTINCT COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,ts.recording_id)
                FROM chunks c
                LEFT JOIN content_blocks cb ON cb.id=c.content_block_id AND cb.owner_id=c.owner_id
                    AND cb.course_id=c.course_id AND cb.session_id=c.session_id
                LEFT JOIN transcript_segments ts ON ts.id=c.transcript_segment_id AND ts.owner_id=c.owner_id
                    AND ts.course_id=c.course_id AND ts.session_id=c.session_id
                LEFT JOIN materials m ON m.id=cb.material_id AND m.owner_id=c.owner_id
                LEFT JOIN notes n ON n.id=cb.note_id AND n.owner_id=c.owner_id
                LEFT JOIN handwriting_blocks h ON h.id=cb.handwriting_block_id AND h.owner_id=c.owner_id
                LEFT JOIN audio_recordings r ON r.id=ts.recording_id AND r.owner_id=c.owner_id
                WHERE c.owner_id=:owner AND c.course_id=:course AND c.session_id=:session
                  AND ((m.id IS NOT NULL AND m.status NOT IN ('cancelled','outdated'))
                    OR (n.id IS NOT NULL AND n.last_left_version=n.version)
                    OR h.status='confirmed'
                    OR (r.id IS NOT NULL AND r.status NOT IN ('cancelled','outdated')))
                """).param("owner", ownerId).param("course", courseId).param("session", sessionId)
                .query(UUID.class).list());
        if (!current.containsAll(scope)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_SOURCE_SCOPE", "Source scope is outside the active session.");
    }

    private List<SelectedChunk> ranked(UUID ownerId, UUID courseId, UUID sessionId, List<Float> values,
                                       List<UUID> scope, int limit) {
        float[] vector = new float[values.size()];
        for (int index = 0; index < vector.length; index++) vector[index] = values.get(index);
        List<UUID> filter = scope == null ? List.of(new UUID(0, 0)) : scope;
        return jdbc.sql("""
                SELECT c.id,c.text_content,c.source_ref::text AS stored_source_ref,c.source_hash,
                       CASE WHEN cb.material_id IS NOT NULL THEN
                            CASE WHEN cb.block_type='table' THEN 'table' ELSE 'pdf_text' END
                            WHEN cb.note_id IS NOT NULL THEN 'note'
                            WHEN cb.handwriting_block_id IS NOT NULL THEN 'handwriting'
                            WHEN ts.id IS NOT NULL THEN 'transcript' END AS source_type,
                       COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,ts.recording_id) AS source_id,
                       COALESCE(m.version,n.version,h.input_version,r.version,1) AS input_version,
                       cb.block_type,cb.material_id,cb.note_id,cb.handwriting_block_id,
                       cb.id AS content_block_id,p.page_number,cb.bbox_norm::text AS bbox_norm,
                       cb.paragraph_offset,ad.material_id AS handwriting_material_id,
                       h.page_number AS handwriting_page_number,
                       ts.recording_id,ts.id AS transcript_segment_id,ts.start_ms,ts.end_ms,
                       c.embedding <=> :query AS distance
                FROM chunks c
                JOIN courses course ON course.id=c.course_id AND course.owner_id=c.owner_id
                JOIN class_sessions session ON session.id=c.session_id AND session.course_id=c.course_id
                    AND session.owner_id=c.owner_id
                LEFT JOIN content_blocks cb ON cb.id=c.content_block_id AND cb.owner_id=c.owner_id
                    AND cb.course_id=c.course_id AND cb.session_id=c.session_id
                LEFT JOIN materials m ON m.id=cb.material_id AND m.owner_id=c.owner_id
                LEFT JOIN notes n ON n.id=cb.note_id AND n.owner_id=c.owner_id
                LEFT JOIN handwriting_blocks h ON h.id=cb.handwriting_block_id AND h.owner_id=c.owner_id
                LEFT JOIN annotation_documents ad ON ad.id=h.annotation_document_id
                    AND ad.owner_id=c.owner_id AND ad.course_id=c.course_id AND ad.session_id=c.session_id
                LEFT JOIN document_pages p ON p.id=cb.page_id AND p.owner_id=c.owner_id
                    AND p.course_id=c.course_id AND p.session_id=c.session_id
                LEFT JOIN transcript_segments ts ON ts.id=c.transcript_segment_id AND ts.owner_id=c.owner_id
                    AND ts.course_id=c.course_id AND ts.session_id=c.session_id
                LEFT JOIN audio_recordings r ON r.id=ts.recording_id AND r.owner_id=c.owner_id
                WHERE c.owner_id=:owner AND c.course_id=:course AND c.session_id=:session
                  AND course.deleted_at IS NULL AND c.embedding IS NOT NULL
                  AND ((m.id IS NOT NULL AND m.status NOT IN ('cancelled','outdated'))
                    OR (n.id IS NOT NULL AND n.last_left_version=n.version)
                    OR h.status='confirmed'
                    OR (r.id IS NOT NULL AND r.status NOT IN ('cancelled','outdated')))
                  AND (:allSources OR COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,ts.recording_id)
                       IN (:scope))
                ORDER BY c.embedding <=> :query,c.id LIMIT :limit
                """).param("query", new PGvector(vector)).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("allSources", scope == null).param("scope", filter)
                .param("limit", limit).query((row, ignored) -> new SelectedChunk(
                        row.getObject("id", UUID.class), row.getString("source_type"),
                        row.getObject("source_id", UUID.class), row.getInt("input_version"),
                        row.getString("source_hash"), row.getString("text_content"),
                        sourceReference(json, new RelationalSource(row.getString("block_type"),
                                row.getObject("material_id", UUID.class), row.getObject("note_id", UUID.class),
                                row.getObject("handwriting_block_id", UUID.class),
                                row.getObject("content_block_id", UUID.class),
                                nullableInteger(row, "page_number"), nullableInteger(row, "paragraph_offset"),
                                row.getString("bbox_norm"), row.getObject("handwriting_material_id", UUID.class),
                                nullableInteger(row, "handwriting_page_number"),
                                row.getObject("recording_id", UUID.class),
                                row.getObject("transcript_segment_id", UUID.class),
                                nullableLong(row, "start_ms"), nullableLong(row, "end_ms"),
                                row.getInt("input_version")), row.getString("stored_source_ref")),
                        row.getDouble("distance"))).list();
    }

    static List<SelectedChunk> selectDiverse(List<SelectedChunk> ranked, int topK) {
        List<SelectedChunk> selected = new ArrayList<>(Math.min(topK, ranked.size()));
        Set<UUID> sources = new LinkedHashSet<>();
        for (SelectedChunk chunk : ranked) {
            if (sources.add(chunk.sourceId())) selected.add(chunk);
            if (selected.size() == topK) return List.copyOf(selected);
        }
        for (SelectedChunk chunk : ranked) {
            if (!selected.contains(chunk)) selected.add(chunk);
            if (selected.size() == topK) break;
        }
        return List.copyOf(selected);
    }

    private void record(UUID ownerId, String query, SelectedTopicGenerationService.Intent intent, int topK,
                        int candidateCount, List<SelectedChunk> selected, String model, String failure,
                        long latencyMs) {
        Map<String, Integer> types = new LinkedHashMap<>();
        selected.forEach(chunk -> types.merge(chunk.sourceType(), 1, Integer::sum));
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO selected_topic_retrieval_metrics
                    (id,owner_id,query_hash,intent,requested_k,candidate_count,selected_count,latency_ms,
                     embedding_model,source_type_counts,failure_code,retention_expires_at,created_at)
                VALUES (:id,:owner,:hash,:intent,:k,:candidates,:selected,:latency,:model,
                        CAST(:types AS jsonb),:failure,:expires,:now)
                """).param("id", UUID.randomUUID()).param("owner", ownerId)
                .param("hash", ContentIndexingService.sha256(query)).param("intent", intent.value)
                .param("k", topK).param("candidates", candidateCount).param("selected", selected.size())
                .param("latency", latencyMs).param("model", model).param("types", json(types))
                .param("failure", failure).param("expires", Timestamp.from(now.plusSeconds(
                        properties.retrieval().retentionDays() * 86_400L)))
                .param("now", Timestamp.from(now)).update();
    }

    static JsonNode sourceReference(ObjectMapper json, RelationalSource source, String stored) {
        var trusted = json.createObjectNode();
        if (source.materialId() != null) {
            trusted.put("sourceType", "table".equals(source.blockType()) ? "table" : "pdf_text");
            trusted.put("materialId", source.materialId().toString());
            trusted.put("contentBlockId", source.contentBlockId().toString());
            trusted.put("pageNumber", source.pageNumber());
            putJson(json, trusted, "bboxNorm", source.bboxNorm());
        } else if (source.noteId() != null) {
            trusted.put("sourceType", "note");
            trusted.put("noteId", source.noteId().toString());
            trusted.put("contentBlockId", source.contentBlockId().toString());
            trusted.put("paragraphOffset", source.paragraphOffset());
        } else if (source.handwritingBlockId() != null) {
            trusted.put("sourceType", "handwriting");
            trusted.put("handwritingBlockId", source.handwritingBlockId().toString());
            trusted.put("contentBlockId", source.contentBlockId().toString());
            trusted.put("materialId", source.handwritingMaterialId().toString());
            trusted.put("pageNumber", source.handwritingPageNumber());
            putJson(json, trusted, "bboxNorm", source.bboxNorm());
        } else if (source.transcriptSegmentId() != null) {
            trusted.put("sourceType", "transcript");
            trusted.put("recordingId", source.recordingId().toString());
            trusted.put("transcriptSegmentId", source.transcriptSegmentId().toString());
            trusted.put("startMs", source.startMs());
            trusted.put("endMs", source.endMs());
        } else {
            throw new IllegalStateException("Chunk has no trusted relational source.");
        }
        trusted.put("inputVersion", source.inputVersion());
        try {
            if (!trusted.equals(json.readTree(stored))) {
                throw new IllegalStateException("Stored source reference does not match its relational source.");
            }
            return trusted;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored source reference is invalid.");
        }
    }

    private static void putJson(ObjectMapper json, com.fasterxml.jackson.databind.node.ObjectNode target,
                                String name, String value) {
        if (value == null) return;
        try { target.set(name, json.readTree(value)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Trusted source metadata is invalid."); }
    }

    private static Integer nullableInteger(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    record Retrieval(List<SelectedChunk> chunks, int candidateCount, String embeddingModel) {}
    record RelationalSource(String blockType, UUID materialId, UUID noteId, UUID handwritingBlockId,
                            UUID contentBlockId, Integer pageNumber, Integer paragraphOffset, String bboxNorm,
                            UUID handwritingMaterialId, Integer handwritingPageNumber, UUID recordingId,
                            UUID transcriptSegmentId, Long startMs, Long endMs, int inputVersion) {}
    record SelectedChunk(UUID chunkId, String sourceType, UUID sourceId, int inputVersion,
                         String sourceHash, String text, JsonNode sourceReference, double distance) {}
}
