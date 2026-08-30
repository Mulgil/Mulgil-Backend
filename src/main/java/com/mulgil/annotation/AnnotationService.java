package com.mulgil.annotation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class AnnotationService {
    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final ObjectMapper json;
    private final Clock clock;

    AnnotationService(JdbcClient jdbc, JobQueue jobs, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    Document save(UUID ownerId, UUID materialId, AnnotationController.Write request) {
        Scope scope = material(ownerId, materialId);
        request.inkStrokes().forEach(stroke -> validate(stroke.bboxNorm()));
        request.emphasisRegions().forEach(region -> validate(region.bboxNorm()));
        StoredDocument existing = document(ownerId, materialId, true);
        Timestamp now = Timestamp.from(clock.instant());
        StoredDocument current;
        if (existing == null) {
            if (request.expectedVersion() != 0) throw conflict();
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO annotation_documents
                        (id,owner_id,course_id,session_id,material_id,version,last_left_version,created_at,updated_at)
                    VALUES (:id,:owner,:course,:session,:material,1,0,:now,:now)
                    """).param("id", id).param("owner", ownerId).param("course", scope.courseId())
                    .param("session", scope.sessionId()).param("material", materialId).param("now", now).update();
            current = new StoredDocument(id, scope.courseId(), scope.sessionId(), 1, 0);
        } else {
            if (existing.version() != request.expectedVersion()) throw conflict();
            int version = existing.version() + 1;
            jdbc.sql("UPDATE annotation_documents SET version=:version,updated_at=:now WHERE id=:id")
                    .param("version", version).param("now", now).param("id", existing.id()).update();
            jdbc.sql("DELETE FROM emphasis_regions WHERE annotation_document_id=:id")
                    .param("id", existing.id()).update();
            current = new StoredDocument(existing.id(), existing.courseId(), existing.sessionId(),
                    version, existing.lastLeftVersion());
        }
        persist(current.id(), request, now);
        return new Document(current.id(), materialId, current.version());
    }

    @Transactional
    JobQueue.JobAccepted leave(UUID ownerId, UUID materialId, int changedVersion) {
        StoredDocument document = document(ownerId, materialId, true);
        if (document == null) throw notFound();
        if (document.version() != changedVersion) throw conflict();
        if (document.lastLeftVersion() >= changedVersion) return null;
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("UPDATE annotation_documents SET last_left_version=:version,updated_at=:now WHERE id=:id")
                .param("version", changedVersion).param("now", now).param("id", document.id()).update();
        jdbc.sql("""
                DELETE FROM content_blocks content
                USING handwriting_blocks handwriting
                WHERE content.handwriting_block_id=handwriting.id
                  AND handwriting.annotation_document_id=:id AND handwriting.input_version<:version
                """).param("id", document.id()).param("version", changedVersion).update();
        jdbc.sql("""
                UPDATE handwriting_blocks SET status='outdated',confirmed_text=NULL,updated_at=:now
                WHERE annotation_document_id=:id AND input_version<:version AND status<>'outdated'
                """).param("now", now).param("id", document.id()).param("version", changedVersion).update();
        List<Stroke> strokes = jdbc.sql("""
                SELECT stroke.id,stroke.page_number,stroke.bbox_norm::text FROM ink_strokes stroke
                WHERE stroke.annotation_document_id=:id AND stroke.tool='pen'
                  AND NOT EXISTS (
                      SELECT 1 FROM handwriting_blocks prior
                      WHERE prior.annotation_document_id=:id AND prior.input_version<:version
                        AND prior.stroke_ids @> jsonb_build_array(stroke.id::text)
                        AND prior.created_at>=stroke.created_at
                  )
                ORDER BY stroke.page_number,stroke.created_at,stroke.id
                """).param("id", document.id()).param("version", changedVersion)
                .query((row, ignored) -> new Stroke(
                        row.getObject("id", UUID.class), row.getInt("page_number"),
                        readBox(row.getString("bbox_norm")))).list();
        if (strokes.isEmpty()) return null;
        Map<Integer, List<Stroke>> pages = new LinkedHashMap<>();
        strokes.forEach(stroke -> pages.computeIfAbsent(stroke.page(), ignored -> new ArrayList<>()).add(stroke));
        pages.forEach((page, pageStrokes) -> {
            AnnotationController.Box union = union(pageStrokes);
            jdbc.sql("""
                    INSERT INTO handwriting_blocks
                        (id,owner_id,course_id,session_id,annotation_document_id,page_number,bbox_norm,
                         stroke_ids,status,input_version,created_at,updated_at)
                    VALUES (:id,:owner,:course,:session,:document,:page,CAST(:bbox AS jsonb),
                            CAST(:strokes AS jsonb),'queued',:version,:now,:now)
                    """).param("id", UUID.randomUUID()).param("owner", ownerId)
                    .param("course", document.courseId()).param("session", document.sessionId())
                    .param("document", document.id()).param("page", page).param("bbox", write(union))
                    .param("strokes", write(pageStrokes.stream().map(Stroke::id).toList()))
                    .param("version", changedVersion).param("now", now).update();
        });
        String sourceHash = ContentIndexingService.sha256(document.id() + ":" + changedVersion);
        JobQueue.AiJob job = jobs.enqueue(new JobQueue.EnqueueRequest("handwriting_ocr", ownerId,
                document.courseId(), document.sessionId(), null, null, null, null, null, changedVersion,
                sourceHash, "vision", "configured", "none"));
        return new JobQueue.JobAccepted(job.id(), job.status());
    }

    private void persist(UUID documentId, AnnotationController.Write request, Timestamp now) {
        List<UUID> strokeIds = request.inkStrokes().stream().map(AnnotationController.InkStroke::id).toList();
        if (strokeIds.isEmpty()) {
            jdbc.sql("DELETE FROM ink_strokes WHERE annotation_document_id=:document")
                    .param("document", documentId).update();
        } else {
            jdbc.sql("DELETE FROM ink_strokes WHERE annotation_document_id=:document AND id NOT IN (:ids)")
                    .param("document", documentId).param("ids", strokeIds).update();
        }
        for (AnnotationController.InkStroke stroke : request.inkStrokes()) {
            jdbc.sql("""
                    INSERT INTO ink_strokes
                        (id,annotation_document_id,page_number,tool,color,width_norm,points_json,bbox_norm,created_at)
                    VALUES (:id,:document,:page,:tool,:color,:width,CAST(:points AS jsonb),CAST(:bbox AS jsonb),:now)
                    ON CONFLICT (id) DO UPDATE SET
                        created_at=CASE WHEN ink_strokes.page_number=EXCLUDED.page_number
                            AND ink_strokes.tool=EXCLUDED.tool AND ink_strokes.color=EXCLUDED.color
                            AND ink_strokes.width_norm=EXCLUDED.width_norm
                            AND ink_strokes.points_json=EXCLUDED.points_json
                            AND ink_strokes.bbox_norm=EXCLUDED.bbox_norm
                            THEN ink_strokes.created_at ELSE EXCLUDED.created_at END,
                        page_number=EXCLUDED.page_number,tool=EXCLUDED.tool,color=EXCLUDED.color,
                        width_norm=EXCLUDED.width_norm,points_json=EXCLUDED.points_json,
                        bbox_norm=EXCLUDED.bbox_norm
                    WHERE ink_strokes.annotation_document_id=EXCLUDED.annotation_document_id
                    """).param("id", stroke.id()).param("document", documentId).param("page", stroke.pageNumber())
                    .param("tool", stroke.tool().name()).param("color", stroke.color())
                    .param("width", stroke.widthNorm()).param("points", write(stroke.points()))
                    .param("bbox", write(stroke.bboxNorm())).param("now", now).update();
        }
        for (AnnotationController.EmphasisRegion region : request.emphasisRegions()) {
            jdbc.sql("""
                    INSERT INTO emphasis_regions
                        (id,annotation_document_id,page_number,bbox_norm,tap_count,created_at,updated_at)
                    VALUES (:id,:document,:page,CAST(:bbox AS jsonb),:taps,:now,:now)
                    """).param("id", region.id()).param("document", documentId).param("page", region.pageNumber())
                    .param("bbox", write(region.bboxNorm())).param("taps", region.tapCount())
                    .param("now", now).update();
        }
    }

    private StoredDocument document(UUID ownerId, UUID materialId, boolean lock) {
        return jdbc.sql("""
                SELECT id,course_id,session_id,version,last_left_version FROM annotation_documents
                WHERE owner_id=:owner AND material_id=:material
                """ + (lock ? " FOR UPDATE" : ""))
                .param("owner", ownerId).param("material", materialId)
                .query((row, ignored) -> new StoredDocument(row.getObject("id", UUID.class),
                        row.getObject("course_id", UUID.class), row.getObject("session_id", UUID.class),
                        row.getInt("version"), row.getInt("last_left_version"))).optional().orElse(null);
    }

    private Scope material(UUID ownerId, UUID materialId) {
        return jdbc.sql("SELECT course_id,session_id FROM materials WHERE owner_id=:owner AND id=:id FOR UPDATE")
                .param("owner", ownerId).param("id", materialId)
                .query((row, ignored) -> new Scope(row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class))).optional().orElseThrow(AnnotationService::notFound);
    }

    private AnnotationController.Box readBox(String value) {
        try {
            return json.readValue(value, AnnotationController.Box.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid annotation JSON.", exception);
        }
    }

    private static AnnotationController.Box union(List<Stroke> strokes) {
        double x = strokes.stream().map(Stroke::box).mapToDouble(AnnotationController.Box::x).min().orElseThrow();
        double y = strokes.stream().map(Stroke::box).mapToDouble(AnnotationController.Box::y).min().orElseThrow();
        double right = strokes.stream().map(Stroke::box)
                .mapToDouble(box -> box.x() + box.width()).max().orElseThrow();
        double bottom = strokes.stream().map(Stroke::box)
                .mapToDouble(box -> box.y() + box.height()).max().orElseThrow();
        return new AnnotationController.Box(x, y, right - x, bottom - y);
    }

    private static void validate(AnnotationController.Box box) {
        if (box.x() + box.width() > 1 || box.y() + box.height() > 1) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Request validation failed.", Map.of("field", "bboxNorm"));
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ANNOTATION_NOT_FOUND", "Annotation not found.");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Version conflict.");
    }

    record Document(UUID id, UUID materialId, int version) {}
    private record Scope(UUID courseId, UUID sessionId) {}
    private record StoredDocument(UUID id, UUID courseId, UUID sessionId, int version, int lastLeftVersion) {}
    private record Stroke(UUID id, int page, AnnotationController.Box box) {}
}
