package com.mulgil.resource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ResourceRepository {
    private final JdbcClient jdbc;

    ResourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<SessionScope> lockSession(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT session.id, session.course_id, session.title, session.starts_at, session.ends_at
                        FROM class_sessions session
                        JOIN courses course ON course.id = session.course_id AND course.owner_id = session.owner_id
                        WHERE session.owner_id = :ownerId AND session.id = :sessionId AND course.deleted_at IS NULL
                        FOR UPDATE OF session, course
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .query((row, ignored) -> sessionScope(row)).optional();
    }

    boolean ownsSession(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM class_sessions session
                            JOIN courses course ON course.id = session.course_id AND course.owner_id = session.owner_id
                            WHERE session.owner_id = :ownerId AND session.id = :id AND course.deleted_at IS NULL
                        )
                        """)
                .param("ownerId", ownerId).param("id", sessionId).query(Boolean.class).single();
    }

    boolean ownsExam(UUID ownerId, UUID examId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM exams exam
                            JOIN courses course ON course.id = exam.course_id AND course.owner_id = exam.owner_id
                            WHERE exam.owner_id = :ownerId AND exam.id = :id AND course.deleted_at IS NULL
                        )
                        """)
                .param("ownerId", ownerId).param("id", examId).query(Boolean.class).single();
    }

    boolean lockExam(UUID ownerId, UUID examId) {
        return jdbc.sql("""
                        SELECT exam.id FROM exams exam
                        JOIN courses course ON course.id=exam.course_id AND course.owner_id=exam.owner_id
                        WHERE exam.owner_id=:ownerId AND exam.id=:id AND course.deleted_at IS NULL
                        FOR SHARE OF exam, course
                        """)
                .param("ownerId", ownerId).param("id", examId).query(UUID.class).optional().isPresent();
    }

    void lockExamSessions(UUID ownerId, UUID examId) {
        jdbc.sql("""
                        SELECT session.id FROM exam_session_members member
                        JOIN class_sessions session ON session.id=member.session_id
                          AND session.owner_id=member.owner_id AND session.course_id=member.course_id
                        JOIN courses course ON course.id=session.course_id AND course.owner_id=session.owner_id
                        WHERE member.owner_id=:ownerId AND member.exam_id=:examId AND course.deleted_at IS NULL
                        ORDER BY session.id
                        FOR UPDATE OF session, course
                        """)
                .param("ownerId", ownerId).param("examId", examId).query(UUID.class).list();
    }

    int materialCount(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT count(*) FROM materials material
                        JOIN courses course ON course.id = material.course_id AND course.owner_id = material.owner_id
                        WHERE material.owner_id = :ownerId AND material.session_id = :sessionId
                          AND material.status NOT IN ('cancelled', 'outdated') AND course.deleted_at IS NULL
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId).query(Integer.class).single();
    }

    int expireMaterialUploads(UUID ownerId, UUID sessionId, Instant now) {
        return jdbc.sql("""
                        UPDATE materials SET status = 'cancelled', updated_at = :now
                        WHERE owner_id = :ownerId AND session_id = :sessionId
                          AND status = 'created' AND upload_expires_at <= :now
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .param("now", Timestamp.from(now)).update();
    }

    int expireMaterialUploads(Instant now) {
        return jdbc.sql("""
                        UPDATE materials SET status = 'cancelled', updated_at = :now
                        WHERE status = 'created' AND upload_expires_at <= :now
                        """)
                .param("now", Timestamp.from(now)).update();
    }

    Material createMaterial(UUID ownerId, SessionScope scope, UUID id, MaterialWrite write,
                            Instant uploadExpiresAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO materials
                            (id, owner_id, course_id, session_id, source_phase, object_key,
                             original_filename, mime_type, byte_size, version, status, upload_expires_at,
                             created_at, updated_at)
                        VALUES
                            (:id, :ownerId, :courseId, :sessionId, :sourcePhase, :objectKey,
                             :filename, :mimeType, :byteSize, 1, 'created', :uploadExpiresAt, :now, :now)
                        RETURNING id, course_id, session_id, original_filename, mime_type, byte_size, page_count,
                                  source_phase, version, status, object_key, upload_expires_at,
                                  created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("courseId", scope.courseId())
                .param("sessionId", scope.id()).param("sourcePhase", write.sourcePhase())
                .param("objectKey", write.objectKey()).param("filename", write.filename())
                .param("mimeType", write.mimeType()).param("byteSize", write.byteSize())
                .param("uploadExpiresAt", Timestamp.from(uploadExpiresAt))
                .param("now", Timestamp.from(now)).query((row, ignored) -> material(row)).single();
    }

    Optional<Material> material(UUID ownerId, UUID materialId) {
        return jdbc.sql("""
                        SELECT material.id, material.course_id, material.session_id, material.original_filename, material.mime_type,
                               material.byte_size, material.page_count, material.source_phase, material.version,
                               material.status, material.object_key, material.upload_expires_at,
                               material.created_at, material.updated_at
                        FROM materials material
                        JOIN courses course ON course.id = material.course_id AND course.owner_id = material.owner_id
                        WHERE material.owner_id = :ownerId AND material.id = :id AND course.deleted_at IS NULL
                        FOR SHARE OF material, course
                        """)
                .param("ownerId", ownerId).param("id", materialId)
                .query((row, ignored) -> material(row)).optional();
    }

    Optional<Material> materialForUpdate(UUID ownerId, UUID materialId) {
        return jdbc.sql("""
                        SELECT material.id, material.course_id, material.session_id, material.original_filename, material.mime_type,
                               material.byte_size, material.page_count, material.source_phase, material.version,
                               material.status, material.object_key, material.upload_expires_at,
                               material.created_at, material.updated_at
                        FROM materials material
                        JOIN courses course ON course.id = material.course_id AND course.owner_id = material.owner_id
                        WHERE material.owner_id = :ownerId AND material.id = :id AND course.deleted_at IS NULL
                        FOR UPDATE OF material
                        """)
                .param("ownerId", ownerId).param("id", materialId)
                .query((row, ignored) -> material(row)).optional();
    }

    List<Material> materials(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT material.id, material.course_id, material.session_id, material.original_filename, material.mime_type,
                               material.byte_size, material.page_count, material.source_phase, material.version,
                               material.status, material.object_key, material.upload_expires_at,
                               material.created_at, material.updated_at
                        FROM materials material
                        JOIN courses course ON course.id = material.course_id AND course.owner_id = material.owner_id
                        WHERE material.owner_id = :ownerId AND material.session_id = :sessionId
                          AND course.deleted_at IS NULL
                        ORDER BY material.created_at, material.id
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .query((row, ignored) -> material(row)).list();
    }

    Optional<Material> finalizeMaterial(UUID ownerId, UUID id, int pageCount, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE materials material SET page_count = :pageCount, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        FROM courses course
                        WHERE material.owner_id = :ownerId AND material.id = :id
                          AND course.id = material.course_id AND course.owner_id = material.owner_id
                          AND course.deleted_at IS NULL
                          AND material.status = 'created' AND material.upload_expires_at > :now
                        RETURNING material.id, material.course_id, material.session_id, material.original_filename, material.mime_type,
                                  material.byte_size, material.page_count, material.source_phase, material.version,
                                  material.status, material.object_key, material.upload_expires_at,
                                  material.created_at, material.updated_at
                        """)
                .param("pageCount", pageCount).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> material(row)).optional();
    }

    ExamResource createExamResource(UUID ownerId, UUID id, ExamResourceWrite write,
                                    Instant uploadExpiresAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO exam_resources
                            (id, owner_id, course_id, exam_id, resource_type, object_key,
                             original_filename, mime_type, byte_size, status, upload_expires_at, created_at, updated_at)
                        SELECT :id, :ownerId, e.course_id, e.id, 'past_exam', :objectKey,
                               :filename, :mimeType, :byteSize, 'created', :uploadExpiresAt, :now, :now
                        FROM exams e
                        JOIN courses course ON course.id = e.course_id AND course.owner_id = e.owner_id
                        WHERE e.owner_id = :ownerId AND e.id = :examId AND course.deleted_at IS NULL
                        RETURNING id, course_id, exam_id, resource_type, original_filename, mime_type, byte_size,
                                  page_count, status, object_key, upload_expires_at, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("examId", write.examId())
                .param("objectKey", write.objectKey()).param("filename", write.filename())
                .param("mimeType", write.mimeType()).param("byteSize", write.byteSize())
                .param("uploadExpiresAt", Timestamp.from(uploadExpiresAt))
                .param("now", Timestamp.from(now)).query((row, ignored) -> examResource(row)).optional()
                .orElse(null);
    }

    Optional<ExamResource> examResource(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        SELECT resource.id, resource.course_id, resource.exam_id, resource.resource_type, resource.original_filename,
                               resource.mime_type, resource.byte_size, resource.page_count, resource.status,
                               resource.object_key, resource.upload_expires_at, resource.created_at, resource.updated_at
                        FROM exam_resources resource
                        JOIN courses course ON course.id = resource.course_id AND course.owner_id = resource.owner_id
                        WHERE resource.owner_id = :ownerId AND resource.id = :id AND course.deleted_at IS NULL
                        FOR SHARE OF resource, course
                        """)
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> examResource(row)).optional();
    }

    Optional<ExamResource> examResourceForUpdate(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        SELECT resource.id, resource.course_id, resource.exam_id, resource.resource_type,
                               resource.original_filename, resource.mime_type, resource.byte_size,
                               resource.page_count, resource.status, resource.object_key, resource.upload_expires_at,
                               resource.created_at, resource.updated_at
                        FROM exam_resources resource
                        JOIN courses course ON course.id = resource.course_id AND course.owner_id = resource.owner_id
                        WHERE resource.owner_id = :ownerId AND resource.id = :id AND course.deleted_at IS NULL
                        FOR UPDATE OF resource
                        """)
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> examResource(row)).optional();
    }

    List<ExamResource> examResources(UUID ownerId, UUID examId) {
        return jdbc.sql("""
                        SELECT resource.id, resource.course_id, resource.exam_id, resource.resource_type, resource.original_filename,
                               resource.mime_type, resource.byte_size, resource.page_count, resource.status,
                               resource.object_key, resource.upload_expires_at, resource.created_at, resource.updated_at
                        FROM exam_resources resource
                        JOIN courses course ON course.id = resource.course_id AND course.owner_id = resource.owner_id
                        WHERE resource.owner_id = :ownerId AND resource.exam_id = :examId
                          AND course.deleted_at IS NULL
                        ORDER BY resource.created_at, resource.id
                        """)
                .param("ownerId", ownerId).param("examId", examId)
                .query((row, ignored) -> examResource(row)).list();
    }

    ExamResource finalizeExamResource(UUID ownerId, UUID id, int pageCount, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE exam_resources resource SET page_count = :pageCount, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        FROM courses course
                        WHERE resource.owner_id = :ownerId AND resource.id = :id
                          AND course.id = resource.course_id AND course.owner_id = resource.owner_id
                          AND course.deleted_at IS NULL
                        RETURNING resource.id, resource.course_id, resource.exam_id, resource.resource_type,
                                  resource.original_filename, resource.mime_type, resource.byte_size,
                                  resource.page_count, resource.status, resource.object_key, resource.upload_expires_at,
                                  resource.created_at, resource.updated_at
                        """)
                .param("pageCount", pageCount).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> examResource(row)).single();
    }

    void scheduleObjectDeletion(String objectKey, Instant notBefore, Instant now) {
        jdbc.sql("""
                        INSERT INTO resource_object_deletions
                            (object_key,not_before,attempt_count,status,last_error,created_at,updated_at)
                        VALUES (:key,:notBefore,0,'pending',NULL,:now,:now)
                        ON CONFLICT (object_key) DO NOTHING
                        """)
                .param("key", objectKey).param("notBefore", Timestamp.from(notBefore))
                .param("now", Timestamp.from(now)).update();
    }

    boolean deleteMaterial(UUID ownerId, UUID id) {
        return jdbc.sql("DELETE FROM materials WHERE owner_id=:ownerId AND id=:id")
                .param("ownerId", ownerId).param("id", id).update() == 1;
    }

    boolean deleteExamResource(UUID ownerId, UUID id) {
        return jdbc.sql("DELETE FROM exam_resources WHERE owner_id=:ownerId AND id=:id")
                .param("ownerId", ownerId).param("id", id).update() == 1;
    }

    Recording createRecording(UUID ownerId, UUID id, RecordingWrite write, Instant now) {
        return jdbc.sql("""
                        INSERT INTO audio_recordings
                            (id, owner_id, object_key, original_filename, mime_type, byte_size,
                             started_at, version, status, created_at, updated_at)
                        SELECT :id, u.id, :objectKey, :filename, :mimeType, :byteSize,
                               :startedAt, 1, 'created', :now, :now
                        FROM users u WHERE u.id = :ownerId
                        RETURNING id, original_filename, mime_type, byte_size, started_at,
                                  duration_seconds, status, object_key, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("objectKey", write.objectKey())
                .param("filename", write.filename()).param("mimeType", write.mimeType())
                .param("byteSize", write.byteSize()).param("startedAt", Timestamp.from(write.startedAt()))
                .param("now", Timestamp.from(now)).query((row, ignored) -> recording(row)).single();
    }

    Optional<Recording> recording(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        SELECT recording.id, recording.original_filename, recording.mime_type, recording.byte_size,
                               recording.started_at, recording.duration_seconds, recording.status,
                               recording.object_key, recording.created_at, recording.updated_at
                        FROM audio_recordings recording
                        LEFT JOIN courses course ON course.id=recording.course_id
                          AND course.owner_id=recording.owner_id
                        WHERE recording.owner_id = :ownerId AND recording.id = :id AND recording.status='created'
                          AND (recording.course_id IS NULL
                               OR (course.id IS NOT NULL AND course.deleted_at IS NULL))
                        FOR UPDATE OF recording
                        """)
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> recording(row)).optional();
    }

    Optional<Recording> finalizeRecording(UUID ownerId, UUID id, long duration, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE audio_recordings recording SET duration_seconds = :duration, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        WHERE recording.owner_id = :ownerId AND recording.id = :id AND recording.status='created'
                          AND (recording.course_id IS NULL OR EXISTS(
                              SELECT 1 FROM courses course
                              WHERE course.id=recording.course_id AND course.owner_id=recording.owner_id
                                AND course.deleted_at IS NULL
                          ))
                        RETURNING recording.id, recording.original_filename, recording.mime_type,
                                  recording.byte_size, recording.started_at, recording.duration_seconds,
                                  recording.status, recording.object_key, recording.created_at, recording.updated_at
                        """)
                .param("duration", duration).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> recording(row)).optional();
    }

    List<SessionScope> overlappingSessions(UUID ownerId, Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT session.id, session.course_id, session.title, session.starts_at, session.ends_at
                        FROM class_sessions session
                        JOIN courses course ON course.id = session.course_id AND course.owner_id = session.owner_id
                        WHERE session.owner_id = :ownerId AND session.starts_at IS NOT NULL AND session.ends_at IS NOT NULL
                          AND session.starts_at < :recordingEnd AND session.ends_at > :recordingStart
                          AND course.deleted_at IS NULL
                        ORDER BY session.starts_at, session.id
                        """)
                .param("ownerId", ownerId).param("recordingStart", Timestamp.from(start))
                .param("recordingEnd", Timestamp.from(end))
                .query((row, ignored) -> sessionScope(row)).list();
    }

    private static Material material(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Material(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getObject("session_id", UUID.class),
                row.getString("original_filename"), row.getString("mime_type"), row.getLong("byte_size"),
                (Integer) row.getObject("page_count"), row.getString("source_phase"), row.getInt("version"),
                row.getString("status"), row.getString("object_key"), nullableInstant(row, "upload_expires_at"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static ExamResource examResource(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ExamResource(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getObject("exam_id", UUID.class),
                row.getString("resource_type"), row.getString("original_filename"), row.getString("mime_type"),
                row.getLong("byte_size"), (Integer) row.getObject("page_count"), row.getString("status"),
                row.getString("object_key"), nullableInstant(row, "upload_expires_at"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Recording recording(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Recording(row.getObject("id", UUID.class), row.getString("original_filename"),
                row.getString("mime_type"), row.getLong("byte_size"), instant(row, "started_at"),
                (Long) row.getObject("duration_seconds"), row.getString("status"), row.getString("object_key"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static SessionScope sessionScope(java.sql.ResultSet row) throws java.sql.SQLException {
        return new SessionScope(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getString("title"), nullableInstant(row, "starts_at"), nullableInstant(row, "ends_at"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record SessionScope(UUID id, UUID courseId, String title, Instant startsAt, Instant endsAt) {}
    record Material(UUID id, UUID courseId, UUID sessionId, String filename, String mimeType, long byteSize,
                    Integer pageCount,
                    String sourcePhase, int version, String status, String objectKey,
                    Instant uploadExpiresAt, Instant createdAt, Instant updatedAt) {}
    record ExamResource(UUID id, UUID courseId, UUID examId, String resourceType, String filename, String mimeType,
                        long byteSize, Integer pageCount, String status, String objectKey,
                        Instant uploadExpiresAt, Instant createdAt, Instant updatedAt) {}
    record Recording(UUID id, String filename, String mimeType, long byteSize, Instant startedAt,
                     Long durationSeconds, String status, String objectKey,
                     Instant createdAt, Instant updatedAt) {}
    record MaterialWrite(String filename, String mimeType, long byteSize, String sourcePhase, String objectKey) {}
    record ExamResourceWrite(UUID examId, String filename, String mimeType, long byteSize, String objectKey) {}
    record RecordingWrite(String filename, String mimeType, long byteSize, Instant startedAt, String objectKey) {}
}
