package com.mulgil.indexing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobQueue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContentIndexingService {
    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final ObjectMapper json;
    private final Clock clock;

    public ContentIndexingService(JdbcClient jdbc, JobQueue jobs, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public UUID index(IndexRequest request) {
        String text = request.text().strip();
        if (text.isEmpty()) throw new IllegalArgumentException("Indexed text must not be blank.");
        UUID contentBlockId = uuid(request.sourceReference().get("contentBlockId"));
        UUID transcriptSegmentId = uuid(request.sourceReference().get("transcriptSegmentId"));
        if ((contentBlockId == null) == (transcriptSegmentId == null)) {
            throw new IllegalArgumentException("Exactly one chunk source is required.");
        }
        UUID sourceId = contentBlockId != null ? contentBlockId : transcriptSegmentId;
        String sourceHash = sha256(sourceId + ":" + text);
        UUID id = UUID.nameUUIDFromBytes((request.ownerId() + ":" + request.courseId() + ":"
                + request.sessionId() + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
        String reference = json(request.sourceReference());
        String conflict = contentBlockId != null
                ? "(content_block_id, chunk_index) WHERE content_block_id IS NOT NULL"
                : "(transcript_segment_id, chunk_index) WHERE transcript_segment_id IS NOT NULL";
        jdbc.sql("""
                        INSERT INTO chunks
                            (id, owner_id, course_id, session_id, content_block_id, transcript_segment_id,
                             chunk_index, text_content, source_ref, source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :block, :segment, 0, :text,
                                CAST(:reference AS jsonb), :hash, :now)
                        ON CONFLICT %s DO UPDATE SET text_content=EXCLUDED.text_content,
                            source_ref=EXCLUDED.source_ref, source_hash=EXCLUDED.source_hash,
                            embedding=CASE WHEN chunks.source_hash=EXCLUDED.source_hash
                                THEN chunks.embedding ELSE NULL END,
                            embedding_model=CASE WHEN chunks.source_hash=EXCLUDED.source_hash
                                THEN chunks.embedding_model ELSE NULL END
                        """.formatted(conflict))
                .param("id", id).param("owner", request.ownerId()).param("course", request.courseId())
                .param("session", request.sessionId()).param("block", contentBlockId)
                .param("segment", transcriptSegmentId).param("text", text).param("reference", reference)
                .param("hash", sourceHash).param("now", Timestamp.from(clock.instant())).update();
        jobs.enqueue(new JobQueue.EnqueueRequest("chunk_embed", request.ownerId(), request.courseId(),
                request.sessionId(), null, null, null, null, null, request.inputVersion(), sourceHash,
                "embedding", "configured", "none"));
        return id;
    }

    public List<IndexedChunk> chunks(UUID ownerId, UUID courseId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT id, text_content, source_ref::text, source_hash
                        FROM chunks WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                        ORDER BY created_at, id
                        """).param("owner", ownerId).param("course", courseId).param("session", sessionId)
                .query((row, ignored) -> new IndexedChunk(row.getObject("id", UUID.class),
                        row.getString("text_content"), row.getString("source_ref"), row.getString("source_hash")))
                .list();
    }

    private String json(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid source reference.", exception);
        }
    }

    private static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record IndexRequest(String sourceType, Map<String, Object> sourceReference,
                               UUID ownerId, UUID courseId, UUID sessionId, int inputVersion, String text) {
        public IndexRequest {
            sourceReference = Map.copyOf(sourceReference);
            if (!sourceType.equals(sourceReference.get("sourceType"))) {
                throw new IllegalArgumentException("Source type must match its reference.");
            }
        }
    }

    public record IndexedChunk(UUID id, String text, String sourceReference, String sourceHash) {}
}
