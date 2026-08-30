package com.mulgil.generation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
final class GenerationReadinessService {
    private final JdbcClient jdbc;

    GenerationReadinessService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Readiness session(UUID owner, UUID course, UUID session, String phase) {
        int expected = jdbc.sql("""
                        SELECT count(*) FROM (
                          SELECT id FROM materials WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                           AND source_phase=:materialPhase AND status NOT IN ('cancelled','outdated')
                          UNION ALL SELECT id FROM notes WHERE :phase='review' AND owner_id=:owner
                           AND course_id=:course AND session_id=:session AND last_left_version=version
                          UNION ALL SELECT id FROM handwriting_blocks WHERE :phase='review' AND owner_id=:owner
                           AND course_id=:course AND session_id=:session AND status='confirmed'
                          UNION ALL SELECT id FROM audio_recordings WHERE :phase='review' AND owner_id=:owner
                           AND course_id=:course AND session_id=:session AND status NOT IN ('cancelled','outdated')
                        ) eligible
                        """).param("owner", owner).param("course", course).param("session", session)
                .param("phase", phase).param("materialPhase", phase + "_pdf")
                .query(Integer.class).single();
        boolean blocked = jdbc.sql("""
                        SELECT EXISTS(
                          SELECT 1 FROM materials WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                           AND source_phase=:materialPhase AND status IN ('queued','running','failed','needs_user_review')
                          UNION ALL SELECT 1 FROM handwriting_blocks WHERE :phase='review' AND owner_id=:owner
                           AND course_id=:course AND session_id=:session
                           AND status IN ('queued','succeeded','failed','needs_user_review')
                          UNION ALL SELECT 1 FROM audio_recordings WHERE :phase='review' AND owner_id=:owner
                           AND course_id=:course AND session_id=:session
                           AND status IN ('queued','running','failed','needs_user_review')
                        )
                        """).param("owner", owner).param("course", course).param("session", session)
                .param("phase", phase).param("materialPhase", phase + "_pdf")
                .query(Boolean.class).single();
        return new Readiness(expected, blocked || blockingSessionJob(owner, course, session, phase));
    }

    Readiness exam(UUID owner, UUID exam, boolean predicted) {
        int expected = jdbc.sql("""
                        SELECT count(*) FROM (
                          SELECT m.id FROM exam_session_members member JOIN materials m
                            ON m.owner_id=member.owner_id AND m.course_id=member.course_id
                           AND m.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam
                             AND m.status NOT IN ('cancelled','outdated')
                          UNION ALL SELECT n.id FROM exam_session_members member JOIN notes n
                            ON n.owner_id=member.owner_id AND n.course_id=member.course_id
                           AND n.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam AND n.last_left_version=n.version
                          UNION ALL SELECT h.id FROM exam_session_members member JOIN handwriting_blocks h
                            ON h.owner_id=member.owner_id AND h.course_id=member.course_id
                           AND h.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam AND h.status='confirmed'
                          UNION ALL SELECT r.id FROM exam_session_members member JOIN audio_recordings r
                            ON r.owner_id=member.owner_id AND r.course_id=member.course_id
                           AND r.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam
                             AND r.status NOT IN ('cancelled','outdated')
                          UNION ALL SELECT er.id FROM exam_resources er WHERE :predicted AND er.owner_id=:owner
                           AND er.exam_id=:exam AND er.resource_type='past_exam'
                           AND er.status NOT IN ('cancelled','outdated')
                        ) eligible
                        """).param("owner", owner).param("exam", exam).param("predicted", predicted)
                .query(Integer.class).single();
        boolean blocked = jdbc.sql("""
                        SELECT EXISTS(
                          SELECT 1 FROM exam_session_members member JOIN materials m
                            ON m.owner_id=member.owner_id AND m.course_id=member.course_id
                           AND m.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam
                             AND m.status IN ('queued','running','failed','needs_user_review')
                          UNION ALL SELECT 1 FROM exam_session_members member JOIN handwriting_blocks h
                            ON h.owner_id=member.owner_id AND h.course_id=member.course_id
                           AND h.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam
                             AND h.status IN ('queued','succeeded','failed','needs_user_review')
                          UNION ALL SELECT 1 FROM exam_session_members member JOIN audio_recordings r
                            ON r.owner_id=member.owner_id AND r.course_id=member.course_id
                           AND r.session_id=member.session_id
                           WHERE member.owner_id=:owner AND member.exam_id=:exam
                             AND r.status IN ('queued','running','failed','needs_user_review')
                          UNION ALL SELECT 1 FROM exam_resources er WHERE :predicted AND er.owner_id=:owner
                           AND er.exam_id=:exam AND er.status IN ('queued','running','failed','needs_user_review')
                        )
                        """).param("owner", owner).param("exam", exam).param("predicted", predicted)
                .query(Boolean.class).single();
        return new Readiness(expected, blocked || blockingExamJob(owner, exam, predicted));
    }

    private boolean blockingSessionJob(UUID owner, UUID course, UUID session, String phase) {
        return jdbc.sql("""
                        SELECT EXISTS(
                          SELECT 1 FROM ai_jobs job
                          LEFT JOIN materials material ON material.id=job.material_id
                          WHERE job.owner_id=:owner AND job.course_id=:course AND job.session_id=:session
                            AND job.status IN ('queued','running','failed','needs_user_review')
                            AND ((job.job_type IN ('pdf_extract','pdf_ocr')
                                  AND material.source_phase=:materialPhase)
                              OR (job.job_type='chunk_embed' AND EXISTS(
                                SELECT 1 FROM chunks chunk
                                LEFT JOIN content_blocks block ON block.id=chunk.content_block_id
                                LEFT JOIN materials source_material ON source_material.id=block.material_id
                                LEFT JOIN notes note ON note.id=block.note_id
                                LEFT JOIN handwriting_blocks handwriting ON handwriting.id=block.handwriting_block_id
                                LEFT JOIN transcript_segments segment ON segment.id=chunk.transcript_segment_id
                                WHERE chunk.owner_id=:owner AND chunk.course_id=:course
                                  AND chunk.session_id=:session AND chunk.source_hash=job.source_hash
                                  AND CASE WHEN :phase='preview' THEN source_material.source_phase='preview_pdf'
                                    ELSE source_material.source_phase='review_pdf' OR note.id IS NOT NULL
                                      OR handwriting.id IS NOT NULL OR segment.id IS NOT NULL END)))
                        )
                        """).param("owner", owner).param("course", course).param("session", session)
                .param("phase", phase).param("materialPhase", phase + "_pdf")
                .query(Boolean.class).single();
    }

    private boolean blockingExamJob(UUID owner, UUID exam, boolean predicted) {
        return jdbc.sql("""
                        SELECT EXISTS(
                          SELECT 1 FROM ai_jobs job
                          JOIN exam_session_members member ON member.owner_id=job.owner_id
                           AND member.course_id=job.course_id AND member.session_id=job.session_id
                          LEFT JOIN exam_resources resource ON resource.id=job.exam_resource_id
                          WHERE member.owner_id=:owner AND member.exam_id=:exam
                            AND job.status IN ('queued','running','failed','needs_user_review')
                            AND ((job.job_type IN ('pdf_extract','pdf_ocr')
                                  AND (job.material_id IS NOT NULL
                                    OR (:predicted AND resource.exam_id=:exam)))
                              OR (job.job_type='chunk_embed' AND EXISTS(
                                SELECT 1 FROM chunks chunk
                                LEFT JOIN content_blocks block ON block.id=chunk.content_block_id
                                LEFT JOIN exam_resources source_resource ON source_resource.id=block.exam_resource_id
                                WHERE chunk.owner_id=job.owner_id AND chunk.course_id=job.course_id
                                  AND chunk.session_id=job.session_id AND chunk.source_hash=job.source_hash
                                  AND (source_resource.id IS NULL
                                    OR (:predicted AND source_resource.exam_id=:exam)))))
                        )
                        """).param("owner", owner).param("exam", exam).param("predicted", predicted)
                .query(Boolean.class).single();
    }

    record Readiness(int expectedSources, boolean blocked) {}
}
