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
                        SELECT id, course_id, title, starts_at, ends_at
                        FROM class_sessions
                        WHERE owner_id = :ownerId AND id = :sessionId
                        FOR UPDATE
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .query((row, ignored) -> sessionScope(row)).optional();
    }

    boolean ownsSession(UUID ownerId, UUID sessionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM class_sessions WHERE owner_id = :ownerId AND id = :id)")
                .param("ownerId", ownerId).param("id", sessionId).query(Boolean.class).single();
    }

    boolean ownsExam(UUID ownerId, UUID examId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM exams WHERE owner_id = :ownerId AND id = :id)")
                .param("ownerId", ownerId).param("id", examId).query(Boolean.class).single();
    }

    int materialCount(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT count(*) FROM materials
                        WHERE owner_id = :ownerId AND session_id = :sessionId
                          AND status NOT IN ('cancelled', 'outdated')
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId).query(Integer.class).single();
    }

    Material createMaterial(UUID ownerId, SessionScope scope, UUID id, MaterialWrite write, Instant now) {
        return jdbc.sql("""
                        INSERT INTO materials
                            (id, owner_id, course_id, session_id, source_phase, object_key,
                             original_filename, mime_type, byte_size, version, status, created_at, updated_at)
                        VALUES
                            (:id, :ownerId, :courseId, :sessionId, :sourcePhase, :objectKey,
                             :filename, :mimeType, :byteSize, 1, 'created', :now, :now)
                        RETURNING id, session_id, original_filename, mime_type, byte_size, page_count,
                                  source_phase, version, status, object_key, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("courseId", scope.courseId())
                .param("sessionId", scope.id()).param("sourcePhase", write.sourcePhase())
                .param("objectKey", write.objectKey()).param("filename", write.filename())
                .param("mimeType", write.mimeType()).param("byteSize", write.byteSize())
                .param("now", Timestamp.from(now)).query((row, ignored) -> material(row)).single();
    }

    Optional<Material> material(UUID ownerId, UUID materialId) {
        return jdbc.sql("""
                        SELECT id, session_id, original_filename, mime_type, byte_size, page_count,
                               source_phase, version, status, object_key, created_at, updated_at
                        FROM materials WHERE owner_id = :ownerId AND id = :id
                        """)
                .param("ownerId", ownerId).param("id", materialId)
                .query((row, ignored) -> material(row)).optional();
    }

    List<Material> materials(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT id, session_id, original_filename, mime_type, byte_size, page_count,
                               source_phase, version, status, object_key, created_at, updated_at
                        FROM materials
                        WHERE owner_id = :ownerId AND session_id = :sessionId
                        ORDER BY created_at, id
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .query((row, ignored) -> material(row)).list();
    }

    Material finalizeMaterial(UUID ownerId, UUID id, int pageCount, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE materials SET page_count = :pageCount, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        WHERE owner_id = :ownerId AND id = :id
                        RETURNING id, session_id, original_filename, mime_type, byte_size, page_count,
                                  source_phase, version, status, object_key, created_at, updated_at
                        """)
                .param("pageCount", pageCount).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> material(row)).single();
    }

    ExamResource createExamResource(UUID ownerId, UUID id, ExamResourceWrite write, Instant now) {
        return jdbc.sql("""
                        INSERT INTO exam_resources
                            (id, owner_id, course_id, exam_id, resource_type, object_key,
                             original_filename, mime_type, byte_size, status, created_at, updated_at)
                        SELECT :id, :ownerId, e.course_id, e.id, 'past_exam', :objectKey,
                               :filename, :mimeType, :byteSize, 'created', :now, :now
                        FROM exams e WHERE e.owner_id = :ownerId AND e.id = :examId
                        RETURNING id, exam_id, resource_type, original_filename, mime_type, byte_size,
                                  page_count, status, object_key, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("examId", write.examId())
                .param("objectKey", write.objectKey()).param("filename", write.filename())
                .param("mimeType", write.mimeType()).param("byteSize", write.byteSize())
                .param("now", Timestamp.from(now)).query((row, ignored) -> examResource(row)).optional()
                .orElse(null);
    }

    Optional<ExamResource> examResource(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        SELECT id, exam_id, resource_type, original_filename, mime_type, byte_size,
                               page_count, status, object_key, created_at, updated_at
                        FROM exam_resources WHERE owner_id = :ownerId AND id = :id
                        """)
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> examResource(row)).optional();
    }

    List<ExamResource> examResources(UUID ownerId, UUID examId) {
        return jdbc.sql("""
                        SELECT id, exam_id, resource_type, original_filename, mime_type, byte_size,
                               page_count, status, object_key, created_at, updated_at
                        FROM exam_resources
                        WHERE owner_id = :ownerId AND exam_id = :examId
                        ORDER BY created_at, id
                        """)
                .param("ownerId", ownerId).param("examId", examId)
                .query((row, ignored) -> examResource(row)).list();
    }

    ExamResource finalizeExamResource(UUID ownerId, UUID id, int pageCount, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE exam_resources SET page_count = :pageCount, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        WHERE owner_id = :ownerId AND id = :id
                        RETURNING id, exam_id, resource_type, original_filename, mime_type, byte_size,
                                  page_count, status, object_key, created_at, updated_at
                        """)
                .param("pageCount", pageCount).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> examResource(row)).single();
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
                        SELECT id, original_filename, mime_type, byte_size, started_at,
                               duration_seconds, status, object_key, created_at, updated_at
                        FROM audio_recordings WHERE owner_id = :ownerId AND id = :id
                        """)
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> recording(row)).optional();
    }

    Recording finalizeRecording(UUID ownerId, UUID id, long duration, String checksum, Instant now) {
        return jdbc.sql("""
                        UPDATE audio_recordings SET duration_seconds = :duration, checksum = :checksum,
                            status = 'uploaded', updated_at = :now
                        WHERE owner_id = :ownerId AND id = :id
                        RETURNING id, original_filename, mime_type, byte_size, started_at,
                                  duration_seconds, status, object_key, created_at, updated_at
                        """)
                .param("duration", duration).param("checksum", checksum).param("now", Timestamp.from(now))
                .param("ownerId", ownerId).param("id", id)
                .query((row, ignored) -> recording(row)).single();
    }

    List<SessionScope> overlappingSessions(UUID ownerId, Instant start, Instant end) {
        return jdbc.sql("""
                        SELECT id, course_id, title, starts_at, ends_at
                        FROM class_sessions
                        WHERE owner_id = :ownerId AND starts_at IS NOT NULL AND ends_at IS NOT NULL
                          AND starts_at < :recordingEnd AND ends_at > :recordingStart
                        ORDER BY starts_at, id
                        """)
                .param("ownerId", ownerId).param("recordingStart", Timestamp.from(start))
                .param("recordingEnd", Timestamp.from(end))
                .query((row, ignored) -> sessionScope(row)).list();
    }

    private static Material material(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Material(row.getObject("id", UUID.class), row.getObject("session_id", UUID.class),
                row.getString("original_filename"), row.getString("mime_type"), row.getLong("byte_size"),
                (Integer) row.getObject("page_count"), row.getString("source_phase"), row.getInt("version"),
                row.getString("status"), row.getString("object_key"), instant(row, "created_at"),
                instant(row, "updated_at"));
    }

    private static ExamResource examResource(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ExamResource(row.getObject("id", UUID.class), row.getObject("exam_id", UUID.class),
                row.getString("resource_type"), row.getString("original_filename"), row.getString("mime_type"),
                row.getLong("byte_size"), (Integer) row.getObject("page_count"), row.getString("status"),
                row.getString("object_key"), instant(row, "created_at"), instant(row, "updated_at"));
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
    record Material(UUID id, UUID sessionId, String filename, String mimeType, long byteSize, Integer pageCount,
                    String sourcePhase, int version, String status, String objectKey,
                    Instant createdAt, Instant updatedAt) {}
    record ExamResource(UUID id, UUID examId, String resourceType, String filename, String mimeType,
                        long byteSize, Integer pageCount, String status, String objectKey,
                        Instant createdAt, Instant updatedAt) {}
    record Recording(UUID id, String filename, String mimeType, long byteSize, Instant startedAt,
                     Long durationSeconds, String status, String objectKey,
                     Instant createdAt, Instant updatedAt) {}
    record MaterialWrite(String filename, String mimeType, long byteSize, String sourcePhase, String objectKey) {}
    record ExamResourceWrite(UUID examId, String filename, String mimeType, long byteSize, String objectKey) {}
    record RecordingWrite(String filename, String mimeType, long byteSize, Instant startedAt, String objectKey) {}
}
