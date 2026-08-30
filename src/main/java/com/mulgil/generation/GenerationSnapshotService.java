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
final class GenerationSnapshotService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    GenerationSnapshotService(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Snapshot session(UUID ownerId, UUID courseId, UUID sessionId, String phase) {
        List<Source> sources = jdbc.sql("""
                        SELECT c.text_content,c.source_ref::text,c.source_hash,c.embedding IS NOT NULL AS indexed,
                               c.source_ref->>'sourceType' AS source_type,
                               COALESCE(cb.material_id,cb.note_id,cb.handwriting_block_id,ts.recording_id) AS source_id,
                               COALESCE(m.version,n.version,h.input_version,r.version,1) AS input_version
                        FROM chunks c
                        LEFT JOIN content_blocks cb ON cb.id=c.content_block_id
                        LEFT JOIN materials m ON m.id=cb.material_id
                        LEFT JOIN notes n ON n.id=cb.note_id
                        LEFT JOIN handwriting_blocks h ON h.id=cb.handwriting_block_id
                        LEFT JOIN transcript_segments ts ON ts.id=c.transcript_segment_id
                        LEFT JOIN audio_recordings r ON r.id=ts.recording_id
                        WHERE c.owner_id=:owner AND c.course_id=:course AND c.session_id=:session
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
        int expected = expectedSessionSources(ownerId, courseId, sessionId, phase);
        boolean blocked = blockedSessionSources(ownerId, courseId, sessionId, phase);
        return snapshot(phase, ownerId, courseId, sessionId, null, sources, expected, blocked);
    }

    Snapshot exam(UUID ownerId, UUID examId, boolean predicted) {
        ExamScope scope = jdbc.sql("""
                        SELECT e.course_id,min(member.session_id::text)::uuid AS session_id
                        FROM exams e JOIN exam_session_members member ON member.exam_id=e.id
                        WHERE e.owner_id=:owner AND e.id=:exam GROUP BY e.course_id
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
                          AND ((:predicted AND er.resource_type='past_exam'
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
        int expected = expectedExamSources(ownerId, examId, predicted);
        boolean blocked = blockedExamSources(ownerId, examId, predicted);
        return snapshot(phase, ownerId, scope.courseId(), scope.sessionId(), examId,
                sources, expected, blocked);
    }

    private int expectedSessionSources(UUID owner, UUID course, UUID session, String phase) {
        return jdbc.sql("""
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
    }

    private boolean blockedSessionSources(UUID owner, UUID course, UUID session, String phase) {
        return jdbc.sql("""
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
    }

    private int expectedExamSources(UUID owner, UUID exam, boolean predicted) {
        return jdbc.sql("""
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
    }

    private boolean blockedExamSources(UUID owner, UUID exam, boolean predicted) {
        return jdbc.sql("""
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
    }

    private Snapshot snapshot(String phase, UUID owner, UUID course, UUID session, UUID exam,
                              List<Source> sources, int expected, boolean blocked) {
        int represented = sources.stream().map(Source::sourceId).distinct().toList().size();
        boolean ready = expected > 0 && expected == represented && !blocked
                && sources.stream().allMatch(Source::indexed);
        String canonical = sources.stream().sorted(Comparator.comparing(Source::canonical))
                .map(Source::canonical).reduce((left, right) -> left + "\u001e" + right).orElse("");
        return new Snapshot(phase, owner, course, session, exam, List.copyOf(sources), canonical,
                ContentIndexingService.sha256(canonical), ready);
    }

    private Source source(String type, UUID id, int version, String hash, String text,
                          String sourceReference, boolean indexed) {
        try {
            return new Source(type, id, version, hash, text, json.readTree(sourceReference), indexed);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored source reference is invalid.", exception);
        }
    }

    record Snapshot(String phase, UUID ownerId, UUID courseId, UUID sessionId, UUID examId,
                    List<Source> sources, String canonical, String snapshotHash, boolean ready) {}

    record Source(String type, UUID sourceId, int inputVersion, String sourceHash, String text,
                  JsonNode sourceReference, boolean indexed) {
        String canonical() {
            return String.join("\u001f", type, sourceId.toString(), Integer.toString(inputVersion), sourceHash);
        }
    }

    private record ExamScope(UUID courseId, UUID sessionId) {}
}
