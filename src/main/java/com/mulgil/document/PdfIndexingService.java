package com.mulgil.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.ocr.OcrProviderException;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
final class PdfIndexingService {
    private static final Map<String, Double> FULL_PAGE = Map.of("x", 0d, "y", 0d, "width", 1d, "height", 1d);
    private final JdbcClient jdbc;
    private final CloudStoragePort storage;
    private final ObjectProvider<VisionOcrPort> vision;
    private final ContentIndexingService indexing;
    private final JobQueue jobs;
    private final MulgilProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final PdfPageAnalyzer analyzer = new PdfPageAnalyzer();

    PdfIndexingService(JdbcClient jdbc, CloudStoragePort storage, ObjectProvider<VisionOcrPort> vision,
                       ContentIndexingService indexing, JobQueue jobs, MulgilProperties properties,
                       ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.vision = vision;
        this.indexing = indexing;
        this.jobs = jobs;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    PreparedPdf extract(JobQueue.ClaimedJob job) throws JobHandler.JobExecutionException {
        byte[] pdf = sourceBytes(job);
        try {
            return new PreparedPdf(pdf, analyzer.analyze(pdf));
        } catch (IOException exception) {
            throw new JobHandler.JobExecutionException("INVALID_PDF", "PDF extraction failed.", false);
        }
    }

    PreparedOcr ocr(JobQueue.ClaimedJob job) throws JobHandler.JobExecutionException {
        VisionOcrPort port = vision.getIfAvailable();
        if (port == null) throw new JobHandler.JobExecutionException(
                "PROVIDER_UNAVAILABLE", "OCR provider unavailable.", true);
        PreparedPdf prepared = extract(job);
        List<OcrPage> pages = new ArrayList<>();
        for (PdfPageAnalyzer.Page page : prepared.pages()) {
            if (!needsOcr(page)) continue;
            try {
                pages.add(new OcrPage(page.number(), port.extract(analyzer.render(prepared.bytes(), page.number()))));
            } catch (OcrProviderException exception) {
                throw new JobHandler.JobExecutionException(exception.code(), exception.getMessage(),
                        exception.retryable());
            } catch (IOException exception) {
                throw new JobHandler.JobExecutionException("INVALID_PDF", "PDF rendering failed.", false);
            }
        }
        return new PreparedOcr(List.copyOf(pages));
    }

    void publishExtract(JobQueue.ClaimedJob job, PreparedPdf prepared) {
        boolean needsOcr = false;
        for (PdfPageAnalyzer.Page page : prepared.pages()) {
            upsertPage(job, page.number(), page.text(), "pdf_text");
            if (needsOcr(page)) {
                needsOcr = true;
            } else if (!page.text().isBlank()) {
                reconcileBlocks(job, page.number(), 1);
                upsertBlockAndIndex(job, page.number(), 0, page.text(), FULL_PAGE,
                        "pdfbox", "pdfbox-3", null);
            }
        }
        if (needsOcr) enqueueOcr(job);
    }

    void publishOcr(JobQueue.ClaimedJob job, PreparedOcr prepared) {
        for (OcrPage page : prepared.pages()) {
            List<VisionOcrPort.OcrBlock> blocks = page.result().blocks().stream()
                    .filter(block -> block.text() != null && !block.text().isBlank()).toList();
            String pageText = blocks.stream().map(VisionOcrPort.OcrBlock::text)
                    .filter(text -> text != null && !text.isBlank()).map(String::strip)
                    .reduce((left, right) -> left + "\n" + right).orElse("");
            upsertPage(job, page.number(), pageText, "ocr");
            reconcileBlocks(job, page.number(), blocks.size());
            int index = 0;
            for (VisionOcrPort.OcrBlock block : blocks) {
                VisionOcrPort.NormalizedBox box = block.box();
                Map<String, Double> bbox = Map.of("x", box.x(), "y", box.y(),
                        "width", box.width(), "height", box.height());
                upsertBlockAndIndex(job, page.number(), index++, block.text().strip(), bbox,
                        page.result().provider(), page.result().model(), block.confidence());
            }
        }
    }

    private byte[] sourceBytes(JobQueue.ClaimedJob job) throws JobHandler.JobExecutionException {
        String table = job.materialId() != null ? "materials" : "exam_resources";
        UUID sourceId = job.materialId() != null ? job.materialId() : job.examResourceId();
        String objectKey = jdbc.sql("SELECT object_key FROM " + table + " WHERE id=:id AND owner_id=:owner AND course_id=:course")
                .param("id", sourceId).param("owner", job.ownerId()).param("course", job.courseId())
                .query(String.class).optional().orElse(null);
        byte[] bytes = objectKey == null ? null : storage.read(objectKey);
        if (bytes == null) throw new JobHandler.JobExecutionException(
                "PROVIDER_UNAVAILABLE", "PDF source unavailable.", true);
        if (!sha256(bytes).equalsIgnoreCase(job.sourceHash())) throw new JobHandler.JobExecutionException(
                "CHECKSUM_MISMATCH", "PDF checksum mismatch.", false);
        return bytes;
    }

    private boolean needsOcr(PdfPageAnalyzer.Page page) {
        long characters = page.text().codePoints().filter(value -> !Character.isWhitespace(value)).count();
        return characters < properties.ocr().minEmbeddedTextCharacters()
                || page.imageCoverage() >= properties.ocr().imageCoverageThreshold();
    }

    private void upsertPage(JobQueue.ClaimedJob job, int pageNumber, String text, String method) {
        UUID id = pageId(job, pageNumber);
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id, owner_id, course_id, session_id, material_id, exam_resource_id,
                             page_number, text_content, text_hash, extraction_method, created_at)
                        VALUES (:id, :owner, :course, :session, :material, :exam, :page,
                                :text, :hash, :method, :now)
                        ON CONFLICT (id) DO UPDATE SET text_content=EXCLUDED.text_content,
                            text_hash=EXCLUDED.text_hash, extraction_method=EXCLUDED.extraction_method
                        """).param("id", id).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("material", job.materialId())
                .param("exam", job.examResourceId()).param("page", pageNumber).param("text", text)
                .param("hash", ContentIndexingService.sha256(text)).param("method", method)
                .param("now", Timestamp.from(clock.instant())).update();
    }

    private void upsertBlockAndIndex(JobQueue.ClaimedJob job, int pageNumber, int blockIndex, String text,
                                     Map<String, Double> bbox, String provider, String model, Double confidence) {
        UUID pageId = pageId(job, pageNumber);
        UUID blockId = blockId(pageId, blockIndex);
        String sourceHash = ContentIndexingService.sha256(job.sourceHash() + ":" + pageNumber + ":" + text);
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id, owner_id, course_id, session_id, material_id, exam_resource_id, page_id,
                             block_type, text_content, bbox_norm, provider, model_id, confidence,
                             source_hash, created_at)
                        VALUES (:id, :owner, :course, :session, :material, :exam, :page,
                                'text', :text, CAST(:bbox AS jsonb), :provider, :model, :confidence, :hash, :now)
                        ON CONFLICT (id) DO UPDATE SET text_content=EXCLUDED.text_content,
                            bbox_norm=EXCLUDED.bbox_norm, provider=EXCLUDED.provider,
                            model_id=EXCLUDED.model_id, confidence=EXCLUDED.confidence,
                            source_hash=EXCLUDED.source_hash
                        """).param("id", blockId).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("material", job.materialId())
                .param("exam", job.examResourceId()).param("page", pageId).param("text", text)
                .param("bbox", json(bbox)).param("provider", provider).param("model", model)
                .param("confidence", confidence).param("hash", sourceHash)
                .param("now", Timestamp.from(clock.instant())).update();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("sourceType", job.examResourceId() == null ? "pdf_text" : "past_exam");
        reference.put(job.examResourceId() == null ? "materialId" : "examResourceId",
                job.examResourceId() == null ? job.materialId().toString() : job.examResourceId().toString());
        reference.put("contentBlockId", blockId.toString());
        reference.put("pageNumber", pageNumber);
        reference.put("bboxNorm", bbox);
        reference.put("inputVersion", job.inputVersion());
        indexing.index(new ContentIndexingService.IndexRequest(reference.get("sourceType").toString(), reference,
                job.ownerId(), job.courseId(), job.sessionId(), job.inputVersion(), text));
    }

    private void reconcileBlocks(JobQueue.ClaimedJob job, int pageNumber, int count) {
        UUID pageId = pageId(job, pageNumber);
        var statement = jdbc.sql("""
                        DELETE FROM content_blocks
                        WHERE owner_id=:owner AND course_id=:course AND session_id=:session AND page_id=:page
                        """ + (count == 0 ? "" : " AND id NOT IN (:ids)"))
                .param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("page", pageId);
        if (count > 0) {
            statement.param("ids", java.util.stream.IntStream.range(0, count)
                    .mapToObj(index -> blockId(pageId, index)).toList());
        }
        statement.update();
    }

    private static UUID blockId(UUID pageId, int blockIndex) {
        return UUID.nameUUIDFromBytes((pageId + ":" + blockIndex).getBytes(StandardCharsets.UTF_8));
    }

    private void enqueueOcr(JobQueue.ClaimedJob job) {
        JobQueue.EnqueueRequest request = job.materialId() != null
                ? JobQueue.EnqueueRequest.material("pdf_ocr", job.ownerId(), job.courseId(), job.sessionId(),
                    job.materialId(), job.inputVersion(), job.sourceHash(), "google-vision",
                    properties.vision().feature(), "none")
                : JobQueue.EnqueueRequest.examResource("pdf_ocr", job.ownerId(), job.courseId(), job.sessionId(),
                    job.examResourceId(), job.inputVersion(), job.sourceHash(), "google-vision",
                    properties.vision().feature(), "none");
        jobs.enqueue(request);
    }

    private UUID pageId(JobQueue.ClaimedJob job, int pageNumber) {
        UUID source = job.materialId() != null ? job.materialId() : job.examResourceId();
        return UUID.nameUUIDFromBytes((job.ownerId() + ":" + job.courseId() + ":" + job.sessionId()
                + ":" + source + ":" + pageNumber).getBytes(StandardCharsets.UTF_8));
    }

    private String json(Map<String, Double> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid bounding box.", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record PreparedPdf(byte[] bytes, List<PdfPageAnalyzer.Page> pages) {}
    record PreparedOcr(List<OcrPage> pages) {}
    record OcrPage(int number, VisionOcrPort.OcrResult result) {}
}
