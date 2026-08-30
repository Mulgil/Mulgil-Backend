package com.mulgil.annotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.ocr.OcrProviderException;
import com.mulgil.ocr.VisionOcrPort;
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
    private final ObjectMapper json;
    private final MulgilProperties properties;
    private final AiProviderUsageLedger usage;
    private final HandwritingRasterizer rasterizer = new HandwritingRasterizer();

    HandwritingOcrJobHandler(JdbcClient jdbc, ObjectProvider<VisionOcrPort> providers,
                             HandwritingService service, MulgilProperties properties,
                             AiProviderUsageLedger usage, ObjectMapper json) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.service = service;
        this.properties = properties;
        this.usage = usage;
        this.json = json;
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
        UUID documentId = jdbc.sql("""
                SELECT document.id FROM annotation_documents document
                WHERE document.owner_id=:owner AND document.course_id=:course AND document.session_id=:session
                ORDER BY document.id
                """).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId())
                .query(UUID.class).list().stream()
                .filter(value -> ContentIndexingService.sha256(value + ":" + job.inputVersion())
                        .equals(job.sourceHash()))
                .findFirst().orElse(null);
        if (documentId == null) return () -> {};
        List<Crop> crops = jdbc.sql("""
                SELECT block.id,block.page_number,
                       (block.bbox_norm->>'x')::double precision x,
                       (block.bbox_norm->>'y')::double precision y,
                       (block.bbox_norm->>'width')::double precision width,
                       (block.bbox_norm->>'height')::double precision height,
                       jsonb_agg(jsonb_build_object('widthNorm',stroke.width_norm,
                           'points',stroke.points_json) ORDER BY stroke.id)::text strokes
                FROM handwriting_blocks block
                JOIN ink_strokes stroke ON stroke.annotation_document_id=block.annotation_document_id
                  AND block.stroke_ids @> jsonb_build_array(stroke.id::text)
                WHERE block.annotation_document_id=:document AND block.input_version=:version
                  AND block.status='queued' AND stroke.tool='pen'
                GROUP BY block.id,block.page_number,block.bbox_norm
                ORDER BY block.page_number,block.id
                """).param("document", documentId).param("version", job.inputVersion())
                .query((row, ignored) -> new Crop(row.getObject("id", UUID.class),
                        new HandwritingRasterizer.Box(row.getDouble("x"), row.getDouble("y"),
                                row.getDouble("width"), row.getDouble("height")),
                        strokes(row.getString("strokes")))).list();
        List<HandwritingService.OcrResult> results = new ArrayList<>();
        for (Crop crop : crops) {
            byte[] image;
            try {
                image = rasterizer.render(new HandwritingRasterizer.Input(crop.box(), crop.strokes()));
            } catch (IOException exception) {
                throw new JobExecutionException("INVALID_ANNOTATION", "Handwriting raster failed.", false);
            }
            VisionOcrPort.OcrResult extracted;
            try {
                extracted = usage.observe(job, "vision.ocr", "google-vision", properties.vision().feature(),
                        "image", 1L, ignored -> 1L,
                        exception -> exception instanceof OcrProviderException ocr
                                ? ocr.code() : "PROVIDER_FAILED", () -> provider.extract(image));
            } catch (OcrProviderException exception) {
                throw new JobExecutionException(exception.code(), exception.getMessage(), exception.retryable());
            }
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

    private List<HandwritingRasterizer.Stroke> strokes(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Crop(UUID id, HandwritingRasterizer.Box box, List<HandwritingRasterizer.Stroke> strokes) {}
}
