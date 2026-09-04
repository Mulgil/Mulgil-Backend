package com.mulgil.resource;

import com.mulgil.generation.GenerationSnapshotService;
import com.mulgil.indexing.ContentIndexingService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
final class ResourceDeletionInvalidationService {
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;

    ResourceDeletionInvalidationService(JdbcClient jdbc, GenerationSnapshotService snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    void stopMaterialJobs(UUID ownerId, ResourceRepository.Material material, Instant now) {
        markOutdated(directMaterialJobs(ownerId, material), now);
        markOutdated(chunkJobsForMaterial(ownerId, material), now);
        markOutdated(handwritingJobsForMaterial(material), now);
    }

    void invalidateMaterialGeneration(UUID ownerId, ResourceRepository.Material material, Instant now) {
        String phase = switch (material.sourcePhase()) {
            case "preview_pdf" -> "preview";
            case "review_pdf" -> "review";
            default -> throw new IllegalStateException("Unsupported material source phase.");
        };
        GenerationSnapshotService.Snapshot session = snapshots.session(
                ownerId, material.courseId(), material.sessionId(), phase);
        if (contains(session, material.id())) {
            markSessionGeneration(ownerId, material.sessionId(), phase, session.snapshotHash(), now);
        }
        examsContaining(ownerId, material).forEach(examId -> {
            GenerationSnapshotService.Snapshot exam = snapshots.exam(ownerId, examId, false);
            if (contains(exam, material.id())) {
                markExamGeneration(ownerId, examId, "exam_summary_generate", exam.snapshotHash(), now);
            }
        });
        invalidateArtifacts(ownerId, "materialId", material.id(), now);
    }

    void stopExamResourceJobs(UUID ownerId, ResourceRepository.ExamResource resource, Instant now) {
        markOutdated(directExamResourceJobs(ownerId, resource), now);
        markOutdated(chunkJobsForExamResource(ownerId, resource), now);
    }

    void invalidateExamResourceGeneration(UUID ownerId, ResourceRepository.ExamResource resource, Instant now) {
        GenerationSnapshotService.Snapshot snapshot = snapshots.exam(ownerId, resource.examId(), true);
        if (contains(snapshot, resource.id())) {
            markExamGeneration(ownerId, resource.examId(), "exam_quiz_generate",
                    snapshot.snapshotHash(), now);
        }
        invalidateArtifacts(ownerId, "examResourceId", resource.id(), now);
    }

    private List<UUID> directMaterialJobs(UUID ownerId, ResourceRepository.Material material) {
        return jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE owner_id=:owner AND material_id=:material AND status IN ('queued','running')
                        """)
                .param("owner", ownerId).param("material", material.id())
                .query(UUID.class).list();
    }

    private List<UUID> directExamResourceJobs(UUID ownerId, ResourceRepository.ExamResource resource) {
        return jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE owner_id=:owner AND exam_resource_id=:resource AND status IN ('queued','running')
                        """)
                .param("owner", ownerId).param("resource", resource.id())
                .query(UUID.class).list();
    }

    private List<UUID> chunkJobsForMaterial(UUID ownerId, ResourceRepository.Material material) {
        return chunkJobs("block.material_id=:source", ownerId, material.id());
    }

    private List<UUID> chunkJobsForExamResource(UUID ownerId, ResourceRepository.ExamResource resource) {
        return chunkJobs("block.exam_resource_id=:source", ownerId, resource.id());
    }

    private List<UUID> chunkJobs(String parent, UUID ownerId, UUID sourceId) {
        return jdbc.sql("""
                        SELECT job.id FROM ai_jobs job
                        WHERE job.owner_id=:owner AND job.job_type='chunk_embed'
                          AND job.status IN ('queued','running')
                          AND EXISTS (
                              SELECT 1 FROM chunks chunk
                              JOIN content_blocks block ON block.id=chunk.content_block_id
                              WHERE %s AND chunk.owner_id=job.owner_id AND chunk.course_id=job.course_id
                                AND chunk.session_id=job.session_id AND chunk.source_hash=job.source_hash
                          )
                        """.formatted(parent))
                .param("owner", ownerId).param("source", sourceId).query(UUID.class).list();
    }

    private List<UUID> handwritingJobsForMaterial(ResourceRepository.Material material) {
        return jdbc.sql("""
                        SELECT job.id,job.input_version,job.source_hash,document.id AS document_id
                        FROM ai_jobs job
                        JOIN annotation_documents document ON document.owner_id=job.owner_id
                          AND document.course_id=job.course_id AND document.session_id=job.session_id
                        WHERE document.material_id=:material AND job.job_type='handwriting_ocr'
                          AND job.status IN ('queued','running')
                        """)
                .param("material", material.id())
                .query((row, ignored) -> new HandwritingJob(
                        row.getObject("id", UUID.class), row.getInt("input_version"),
                        row.getString("source_hash"), row.getObject("document_id", UUID.class)))
                .list().stream()
                .filter(job -> ContentIndexingService.sha256(job.documentId() + ":" + job.inputVersion())
                        .equals(job.sourceHash()))
                .map(HandwritingJob::id).toList();
    }

    private List<UUID> examsContaining(UUID ownerId, ResourceRepository.Material material) {
        return jdbc.sql("""
                        SELECT DISTINCT exam_id FROM exam_session_members
                        WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                        ORDER BY exam_id
                        """)
                .param("owner", ownerId).param("course", material.courseId())
                .param("session", material.sessionId()).query(UUID.class).list();
    }

    private void markSessionGeneration(UUID ownerId, UUID sessionId, String phase, String sourceHash, Instant now) {
        markOutdated(jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE owner_id=:owner AND session_id=:session AND exam_id IS NULL
                          AND job_type=:type AND source_hash=:hash AND status IN ('queued','running')
                        """)
                .param("owner", ownerId).param("session", sessionId).param("type", phase + "_generate")
                .param("hash", sourceHash).query(UUID.class).list(), now);
    }

    private void markExamGeneration(UUID ownerId, UUID examId, String type, String sourceHash, Instant now) {
        markOutdated(jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE owner_id=:owner AND exam_id=:exam AND job_type=:type
                          AND source_hash=:hash AND status IN ('queued','running')
                        """)
                .param("owner", ownerId).param("exam", examId).param("type", type)
                .param("hash", sourceHash).query(UUID.class).list(), now);
    }

    private void invalidateArtifacts(UUID ownerId, String sourceField, UUID sourceId, Instant now) {
        String reference = "[{\"%s\":\"%s\"}]".formatted(sourceField, sourceId);
        Timestamp timestamp = Timestamp.from(now);
        jdbc.sql("""
                        UPDATE summaries SET status='outdated',updated_at=:now
                        WHERE owner_id=:owner AND status='succeeded'
                          AND source_refs @> CAST(:reference AS jsonb)
                        """).param("now", timestamp).param("owner", ownerId)
                .param("reference", reference).update();
        jdbc.sql("""
                        UPDATE mindmaps SET status='outdated',updated_at=:now
                        WHERE owner_id=:owner AND status='succeeded'
                          AND source_refs @> CAST(:reference AS jsonb)
                        """).param("now", timestamp).param("owner", ownerId)
                .param("reference", reference).update();
        jdbc.sql("""
                        UPDATE quiz_questions SET status='outdated'
                        WHERE owner_id=:owner AND status='succeeded' AND (
                            question_json->'sourceRefs' @> CAST(:reference AS jsonb)
                            OR answer_json->'sourceRefs' @> CAST(:reference AS jsonb)
                            OR explanation_json->'sourceRefs' @> CAST(:reference AS jsonb)
                        )
                        """).param("owner", ownerId).param("reference", reference).update();
    }

    private void markOutdated(List<UUID> jobIds, Instant now) {
        if (jobIds.isEmpty()) return;
        jdbc.sql("""
                        UPDATE ai_jobs SET status='outdated',claimed_by=NULL,last_heartbeat_at=NULL,
                            lease_expires_at=NULL,error_code='STALE_INPUT',
                            error_message='Job input is outdated.',finished_at=:now
                        WHERE id IN (:ids) AND status IN ('queued','running')
                        """).param("ids", jobIds).param("now", Timestamp.from(now)).update();
    }

    private static boolean contains(GenerationSnapshotService.Snapshot snapshot, UUID sourceId) {
        return snapshot != null && snapshot.sources().stream().anyMatch(source -> source.sourceId().equals(sourceId));
    }

    private record HandwritingJob(UUID id, int inputVersion, String sourceHash, UUID documentId) {}
}
