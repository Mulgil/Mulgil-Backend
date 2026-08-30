package com.mulgil.annotation;

import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
final class HandwritingOcrJobHandler implements JobHandler {
    private final JdbcClient jdbc;
    private final ObjectProvider<VisionOcrPort> providers;
    private final HandwritingService service;
    private final CloudStoragePort storage;
    private final HandwritingCropper cropper = new HandwritingCropper();

    HandwritingOcrJobHandler(JdbcClient jdbc, ObjectProvider<VisionOcrPort> providers,
                             HandwritingService service, CloudStoragePort storage) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.service = service;
        this.storage = storage;
    }

    @Override
    public String jobType() {
        return "handwriting_ocr";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        VisionOcrPort provider = providers.getIfAvailable();
        if (provider == null) {
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Handwriting OCR provider unavailable.", true);
        }
        Document document = jdbc.sql("""
                SELECT document.id,material.object_key FROM annotation_documents document
                JOIN materials material ON material.id=document.material_id
                WHERE document.owner_id=:owner AND document.course_id=:course AND document.session_id=:session
                ORDER BY document.id
                """).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId())
                .query((row, ignored) -> new Document(row.getObject("id", UUID.class), row.getString("object_key")))
                .list().stream()
                .filter(value -> ContentIndexingService.sha256(value.id() + ":" + job.inputVersion())
                        .equals(job.sourceHash()))
                .findFirst().orElse(null);
        if (document == null) return () -> {};
        List<Crop> crops = jdbc.sql("""
                SELECT id,page_number,(bbox_norm->>'x')::double precision x,
                       (bbox_norm->>'y')::double precision y,
                       (bbox_norm->>'width')::double precision width,
                       (bbox_norm->>'height')::double precision height
                FROM handwriting_blocks
                WHERE annotation_document_id=:document AND input_version=:version AND status='queued'
                ORDER BY page_number,id
                """).param("document", document.id()).param("version", job.inputVersion())
                .query((row, ignored) -> new Crop(row.getObject("id", UUID.class),
                        row.getInt("page_number"), row.getDouble("x"), row.getDouble("y"),
                        row.getDouble("width"), row.getDouble("height"))).list();
        byte[] pdf = storage.read(document.objectKey());
        if (pdf == null) throw new JobExecutionException("INVALID_PDF", "Handwriting source PDF unavailable.", false);
        List<HandwritingService.OcrResult> results = new ArrayList<>();
        for (Crop crop : crops) {
            byte[] image;
            try {
                image = cropper.crop(pdf, crop.page(), crop.x(), crop.y(), crop.width(), crop.height());
            } catch (IOException | IndexOutOfBoundsException exception) {
                throw new JobExecutionException("INVALID_PDF", "Handwriting crop failed.", false);
            }
            VisionOcrPort.OcrResult extracted = provider.extract(image);
            String text = extracted.blocks().stream().map(VisionOcrPort.OcrBlock::text)
                    .filter(value -> value != null && !value.isBlank()).reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            double confidence = extracted.blocks().stream().mapToDouble(VisionOcrPort.OcrBlock::confidence)
                    .min().orElse(0);
            results.add(new HandwritingService.OcrResult(job.ownerId(), crop.id(), text, confidence,
                    extracted.provider(), extracted.model()));
        }
        return () -> results.forEach(service::publish);
    }

    private record Document(UUID id, String objectKey) {}
    private record Crop(UUID id, int page, double x, double y, double width, double height) {}
}
