package com.mulgil.annotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
class HandwritingService {
    static final double AUTO_ACCEPT_CONFIDENCE = 0.80;

    private final JdbcClient jdbc;
    private final ContentIndexingService indexing;
    private final JobQueue jobs;
    private final Clock clock;
    private final ObjectMapper json;

    HandwritingService(JdbcClient jdbc, ContentIndexingService indexing, JobQueue jobs, Clock clock,
                       ObjectMapper json) {
        this.jdbc = jdbc;
        this.indexing = indexing;
        this.jobs = jobs;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    JobQueue.JobAccepted confirm(UUID ownerId, UUID blockId, String text) {
        Block block = block(ownerId, blockId, true);
        if (block == null) throw notFound();
        jdbc.sql("""
                UPDATE handwriting_blocks
                SET confirmed_text=:text,status='confirmed',updated_at=:now WHERE id=:id
                """).param("text", text.strip()).param("now", Timestamp.from(clock.instant()))
                .param("id", blockId).update();
        return index(block, text.strip(), "user", "confirmed");
    }

    @Transactional
    void publish(OcrResult result) {
        Block block = block(result.ownerId(), result.blockId(), true);
        if (block == null) return;
        boolean current = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM annotation_documents
                    WHERE id=:document AND version=:version)
                """).param("document", block.documentId()).param("version", block.inputVersion())
                .query(Boolean.class).single();
        if (!current || !block.status().equals("queued")) {
            if (block.status().equals("queued")) {
                jdbc.sql("UPDATE handwriting_blocks SET status='outdated',updated_at=:now WHERE id=:id")
                        .param("now", Timestamp.from(clock.instant())).param("id", block.id()).update();
            }
            return;
        }
        String text = result.text().strip();
        if (text.isEmpty() || result.confidence() < AUTO_ACCEPT_CONFIDENCE) {
            jdbc.sql("""
                    UPDATE handwriting_blocks SET ocr_text=:text,confidence=:confidence,
                        status='needs_user_review',updated_at=:now WHERE id=:id
                    """).param("text", text).param("confidence", result.confidence())
                    .param("now", Timestamp.from(clock.instant())).param("id", block.id()).update();
            return;
        }
        jdbc.sql("""
                UPDATE handwriting_blocks SET ocr_text=:text,confidence=:confidence,confirmed_text=:text,
                    status='confirmed',updated_at=:now WHERE id=:id
                """).param("text", text).param("confidence", result.confidence())
                .param("now", Timestamp.from(clock.instant())).param("id", block.id()).update();
        index(block, text, result.provider(), result.model());
    }

    private JobQueue.JobAccepted index(Block block, String text, String provider, String model) {
        jdbc.sql("DELETE FROM content_blocks WHERE handwriting_block_id=:id")
                .param("id", block.id()).update();
        UUID contentBlockId = UUID.nameUUIDFromBytes((block.id() + ":" + block.inputVersion())
                .getBytes(StandardCharsets.UTF_8));
        String blockHash = ContentIndexingService.sha256(block.inputVersion() + ":" + text);
        jdbc.sql("""
                INSERT INTO content_blocks
                    (id,owner_id,course_id,session_id,handwriting_block_id,block_type,text_content,
                     bbox_norm,provider,model_id,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:block,'handwriting',:text,CAST(:bbox AS jsonb),
                        :provider,:model,:hash,:now)
                """).param("id", contentBlockId).param("owner", block.ownerId()).param("course", block.courseId())
                .param("session", block.sessionId()).param("block", block.id()).param("text", text)
                .param("bbox", block.bbox()).param("provider", provider).param("model", model)
                .param("hash", blockHash).param("now", Timestamp.from(clock.instant())).update();
        Map<String, Object> reference = Map.of("sourceType", "handwriting", "handwritingBlockId", block.id(),
                "contentBlockId", contentBlockId, "materialId", block.materialId(),
                "pageNumber", block.page(), "bboxNorm", bbox(block.bbox()), "inputVersion", block.inputVersion());
        indexing.index(new ContentIndexingService.IndexRequest("handwriting", reference, block.ownerId(),
                block.courseId(), block.sessionId(), block.inputVersion(), text));
        String chunkHash = ContentIndexingService.sha256(contentBlockId + ":" + text);
        JobQueue.AiJob job = jobs.enqueue(new JobQueue.EnqueueRequest("chunk_embed", block.ownerId(),
                block.courseId(), block.sessionId(), null, null, null, null, null, block.inputVersion(),
                chunkHash, "embedding", "configured", "none"));
        return new JobQueue.JobAccepted(job.id(), job.status());
    }

    private JsonNode bbox(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    Block block(UUID ownerId, UUID blockId, boolean lock) {
        return jdbc.sql("""
                SELECT block.id,block.owner_id,block.course_id,block.session_id,block.annotation_document_id,
                       document.material_id,block.page_number,block.bbox_norm::text,block.input_version,
                       block.status
                FROM handwriting_blocks block
                JOIN annotation_documents document ON document.id=block.annotation_document_id
                WHERE block.owner_id=:owner AND block.id=:id
                """ + (lock ? " FOR UPDATE OF block" : ""))
                .param("owner", ownerId).param("id", blockId)
                .query((row, ignored) -> new Block(row.getObject("id", UUID.class),
                        row.getObject("owner_id", UUID.class), row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getObject("annotation_document_id", UUID.class),
                        row.getObject("material_id", UUID.class), row.getInt("page_number"),
                        row.getString("bbox_norm"), row.getInt("input_version"), row.getString("status")))
                .optional().orElse(null);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "HANDWRITING_NOT_FOUND", "Handwriting block not found.");
    }

    record OcrResult(UUID ownerId, UUID blockId, String text, double confidence, String provider, String model) {}
    record Block(UUID id, UUID ownerId, UUID courseId, UUID sessionId, UUID documentId, UUID materialId,
                 int page, String bbox, int inputVersion, String status) {}
}
