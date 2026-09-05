package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ContentIndexingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public final class GenerationSnapshotService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final GenerationReadinessService readiness;

    GenerationSnapshotService(JdbcClient jdbc, ObjectMapper json, GenerationReadinessService readiness) {
        this.jdbc = jdbc;
        this.json = json;
        this.readiness = readiness;
    }

    public Snapshot session(UUID ownerId, UUID courseId, UUID sessionId, String phase) {
        List<Source> sources = jdbc.sql("""
                        SELECT c.text_content,c.source_ref::text,c.source_hash,c.embedding IS NOT NULL AS indexed,
                               c.source_ref->>'sourceType' AS source_type,
                               COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,ts.recording_id) AS source_id,
                               COALESCE(m.version,n.version,h.input_version,r.version,1) AS input_version
                        FROM chunks c
                        JOIN courses course ON course.id=c.course_id AND course.owner_id=c.owner_id
                        LEFT JOIN content_blocks cb ON cb.id=c.content_block_id
                        LEFT JOIN materials m ON m.id=cb.material_id
                        LEFT JOIN notes n ON n.id=cb.note_id
                        LEFT JOIN handwriting_blocks h ON h.id=cb.handwriting_block_id
                        LEFT JOIN transcript_segments ts ON ts.id=c.transcript_segment_id
                        LEFT JOIN audio_recordings r ON r.id=ts.recording_id
                        WHERE c.owner_id=:owner AND c.course_id=:course AND c.session_id=:session
                          AND course.deleted_at IS NULL
                          AND CASE WHEN :phase='preview' THEN
                                m.source_phase='preview_pdf' AND m.status NOT IN ('cancelled','outdated')
                              ELSE
                                (m.source_phase='review_pdf' AND m.status NOT IN ('cancelled','outdated'))
                                OR (n.id IS NOT NULL AND n.last_left_version=n.version)
                                OR h.status='confirmed'
                                OR (r.id IS NOT NULL AND r.status NOT IN ('cancelled','outdated'))
                              END
                        ORDER BY source_type,source_id,input_version,c.source_hash,c.id
                        """).param("owner", ownerId).param("course", courseId).param("session", sessionId)
                .param("phase", phase).query((row, ignored) -> source(row.getString("source_type"),
                        row.getObject("source_id", UUID.class), row.getInt("input_version"),
                        row.getString("source_hash"), row.getString("text_content"),
                        row.getString("source_ref"), row.getBoolean("indexed"))).list();
        GenerationReadinessService.Readiness state = readiness.session(ownerId, courseId, sessionId, phase);
        return snapshot(phase, ownerId, courseId, sessionId, null, sources,
                state.expectedSources(), state.blocked());
    }

    public Snapshot exam(UUID ownerId, UUID examId, boolean predicted) {
        ExamScope scope = jdbc.sql("""
                        SELECT e.course_id,min(member.session_id::text)::uuid AS session_id
                        FROM exams e
                        JOIN courses course ON course.id=e.course_id AND course.owner_id=e.owner_id
                        JOIN exam_session_members member ON member.exam_id=e.id
                        WHERE e.owner_id=:owner AND e.id=:exam AND course.deleted_at IS NULL GROUP BY e.course_id
                        """).param("owner", ownerId).param("exam", examId)
                .query((row, ignored) -> new ExamScope(row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class))).optional().orElse(null);
        if (scope == null) return null;
        List<Source> sources = jdbc.sql("""
                        SELECT c.text_content,c.source_ref::text,c.source_hash,c.embedding IS NOT NULL AS indexed,
                               c.source_ref->>'sourceType' AS source_type,
                               COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,cb.exam_resource_id,
                                        ts.recording_id) AS source_id,
                               COALESCE(m.version,n.version,h.input_version,r.version,1) AS input_version
                        FROM chunks c
                        JOIN exam_session_members member ON member.owner_id=c.owner_id
                         AND member.course_id=c.course_id AND member.session_id=c.session_id
                        LEFT JOIN content_blocks cb ON cb.id=c.content_block_id
                        LEFT JOIN materials m ON m.id=cb.material_id
                        LEFT JOIN notes n ON n.id=cb.note_id
                        LEFT JOIN handwriting_blocks h ON h.id=cb.handwriting_block_id
                        LEFT JOIN transcript_segments ts ON ts.id=c.transcript_segment_id
                        LEFT JOIN audio_recordings r ON r.id=ts.recording_id
                        LEFT JOIN exam_resources er ON er.id=cb.exam_resource_id
                        WHERE member.exam_id=:exam AND c.owner_id=:owner
                          AND ((:predicted AND er.exam_id=:exam AND er.resource_type='past_exam'
                                AND er.status NOT IN ('cancelled','outdated')) OR
                               (er.id IS NULL AND (
                                 (m.id IS NOT NULL AND m.status NOT IN ('cancelled','outdated'))
                                 OR (n.id IS NOT NULL AND n.last_left_version=n.version)
                                 OR h.status='confirmed'
                                 OR (r.id IS NOT NULL AND r.status NOT IN ('cancelled','outdated')))))
                        ORDER BY source_type,source_id,input_version,c.source_hash,c.id
                        """).param("owner", ownerId).param("exam", examId).param("predicted", predicted)
                .query((row, ignored) -> source(row.getString("source_type"),
                        row.getObject("source_id", UUID.class), row.getInt("input_version"),
                        row.getString("source_hash"), row.getString("text_content"),
                        row.getString("source_ref"), row.getBoolean("indexed"))).list();
        String phase = predicted ? "exam_quiz" : "exam_summary";
        GenerationReadinessService.Readiness state = readiness.exam(ownerId, examId, predicted);
        Snapshot snapshot = snapshot(phase, ownerId, scope.courseId(), scope.sessionId(), examId,
                sources, state.expectedSources(), state.blocked());
        boolean indexedPastExam = sources.stream().anyMatch(source ->
                source.type().equals("past_exam") && source.indexed());
        return predicted && !indexedPastExam && snapshot.readiness() == Readiness.READY
                ? snapshot.withReadiness(Readiness.INSUFFICIENT_SOURCE_DATA) : snapshot;
    }

    private Snapshot snapshot(String phase, UUID owner, UUID course, UUID session, UUID exam,
                              List<Source> sources, int expected, boolean blocked) {
        int represented = sources.stream().map(Source::sourceId).distinct().toList().size();
        boolean complete = expected > 0 && expected == represented;
        boolean embeddingsPending = complete && sources.stream().anyMatch(source -> !source.indexed());
        boolean ready = complete && !blocked && !embeddingsPending;
        Readiness readiness = ready ? Readiness.READY : embeddingsPending
                ? Readiness.EMBEDDING_NOT_READY : Readiness.INSUFFICIENT_SOURCE_DATA;
        String canonical = sources.stream().sorted(Comparator.comparing(Source::canonical))
                .map(Source::canonical).reduce((left, right) -> left + "\u001e" + right).orElse("");
        return new Snapshot(phase, owner, course, session, exam, List.copyOf(sources), canonical,
                ContentIndexingService.sha256(canonical), ready, readiness);
    }

    private Source source(String type, UUID id, int version, String hash, String text,
                          String sourceReference, boolean indexed) {
        try {
            return new Source(type, id, version, hash, text, json.readTree(sourceReference), indexed);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored source reference is invalid.", exception);
        }
    }

    public record Snapshot(String phase, UUID ownerId, UUID courseId, UUID sessionId, UUID examId,
                    List<Source> sources, String canonical, String snapshotHash, boolean ready, Readiness readiness) {
        Snapshot withReadiness(Readiness value) {
            return new Snapshot(phase, ownerId, courseId, sessionId, examId,
                    sources, canonical, snapshotHash, value == Readiness.READY, value);
        }
    }

    enum Readiness { READY, INSUFFICIENT_SOURCE_DATA, EMBEDDING_NOT_READY }

    public record Source(String type, UUID sourceId, int inputVersion, String sourceHash, String text,
                  JsonNode sourceReference, boolean indexed) {
        String canonical() {
            return String.join("\u001f", type, sourceId.toString(), Integer.toString(inputVersion), sourceHash);
        }
    }

    private record ExamScope(UUID courseId, UUID sessionId) {}
}
