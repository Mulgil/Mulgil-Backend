package com.mulgil.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class LearningDomainRepository {
    private final JdbcClient jdbc;

    LearningDomainRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Course createCourse(UUID ownerId, UUID id, String name, String instructor, String term, Instant now) {
        return jdbc.sql("""
                        INSERT INTO courses (id, owner_id, name, instructor, term, created_at, updated_at)
                        VALUES (:id, :ownerId, :name, :instructor, :term, :now, :now)
                        RETURNING id, name, instructor, term, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("name", name)
                .param("instructor", instructor).param("term", term).param("now", Timestamp.from(now))
                .query((row, ignored) -> course(row)).single();
    }

    List<Course> listCourses(UUID ownerId) {
        return jdbc.sql("""
                        SELECT id, name, instructor, term, created_at, updated_at
                        FROM courses WHERE owner_id = :ownerId AND deleted_at IS NULL ORDER BY created_at, id
                        """)
                .param("ownerId", ownerId).query((row, ignored) -> course(row)).list();
    }

    Course updateCourse(UUID ownerId, UUID courseId, String name, String instructor, String term, Instant now) {
        return jdbc.sql("""
                        UPDATE courses
                        SET name = :name, instructor = :instructor, term = :term, updated_at = :now
                        WHERE id = :courseId AND owner_id = :ownerId AND deleted_at IS NULL
                        RETURNING id, name, instructor, term, created_at, updated_at
                        """)
                .param("ownerId", ownerId).param("courseId", courseId).param("name", name)
                .param("instructor", instructor).param("term", term).param("now", Timestamp.from(now))
                .query((row, ignored) -> course(row)).optional().orElse(null);
    }

    int softDeleteCourse(UUID ownerId, UUID courseId, Instant now) {
        return jdbc.sql("""
                        UPDATE courses SET deleted_at = :now, updated_at = :now
                        WHERE id = :courseId AND owner_id = :ownerId AND deleted_at IS NULL
                        """)
                .param("ownerId", ownerId).param("courseId", courseId).param("now", Timestamp.from(now)).update();
    }

    boolean ownsCourse(UUID ownerId, UUID courseId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM courses WHERE owner_id = :ownerId AND id = :courseId "
                + "AND deleted_at IS NULL)")
                .param("ownerId", ownerId).param("courseId", courseId).query(Boolean.class).single();
    }

    TimetableSlot createSlot(UUID ownerId, UUID id, SlotWrite request, Instant now) {
        return jdbc.sql("""
                        INSERT INTO timetable_slots
                            (id, owner_id, course_id, weekday, start_time, end_time, timezone, created_at, updated_at)
                        SELECT :id, :ownerId, c.id, :weekday, :startTime, :endTime, :timezone, :now, :now
                        FROM courses c WHERE c.id = :courseId AND c.owner_id = :ownerId AND c.deleted_at IS NULL
                        RETURNING id, course_id, weekday, start_time, end_time, timezone, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("courseId", request.courseId())
                .param("weekday", request.weekday()).param("startTime", Time.valueOf(request.startTime()))
                .param("endTime", Time.valueOf(request.endTime())).param("timezone", request.timezone())
                .param("now", Timestamp.from(now)).query((row, ignored) -> slot(row)).optional()
                .orElse(null);
    }

    List<TimetableSlot> listSlots(UUID ownerId, UUID courseId) {
        String sql = courseId == null
                ? """
                  SELECT s.id, s.course_id, s.weekday, s.start_time, s.end_time, s.timezone, s.created_at, s.updated_at
                  FROM timetable_slots s JOIN courses c ON c.id = s.course_id AND c.owner_id = s.owner_id
                  WHERE s.owner_id = :ownerId AND c.deleted_at IS NULL ORDER BY s.weekday, s.start_time, s.id
                  """
                : """
                  SELECT s.id, s.course_id, s.weekday, s.start_time, s.end_time, s.timezone, s.created_at, s.updated_at
                  FROM timetable_slots s JOIN courses c ON c.id = s.course_id AND c.owner_id = s.owner_id
                  WHERE s.owner_id = :ownerId AND s.course_id = :courseId AND c.deleted_at IS NULL
                  ORDER BY s.weekday, s.start_time, s.id
                  """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("ownerId", ownerId);
        if (courseId != null) statement.param("courseId", courseId);
        return statement.query((row, ignored) -> slot(row)).list();
    }

    TimetableSlot updateSlot(UUID ownerId, UUID slotId, SlotWrite request, Instant now) {
        return jdbc.sql("""
                        UPDATE timetable_slots s
                        SET course_id = c.id, weekday = :weekday, start_time = :startTime,
                            end_time = :endTime, timezone = :timezone, updated_at = :now
                        FROM courses c
                        WHERE s.id = :slotId AND s.owner_id = :ownerId
                          AND c.id = :courseId AND c.owner_id = :ownerId AND c.deleted_at IS NULL
                          AND EXISTS (SELECT 1 FROM courses source
                                      WHERE source.id = s.course_id AND source.owner_id = s.owner_id
                                        AND source.deleted_at IS NULL)
                        RETURNING s.id, s.course_id, s.weekday, s.start_time, s.end_time,
                                  s.timezone, s.created_at, s.updated_at
                        """)
                .param("slotId", slotId).param("ownerId", ownerId).param("courseId", request.courseId())
                .param("weekday", request.weekday()).param("startTime", Time.valueOf(request.startTime()))
                .param("endTime", Time.valueOf(request.endTime())).param("timezone", request.timezone())
                .param("now", Timestamp.from(now)).query((row, ignored) -> slot(row)).optional()
                .orElse(null);
    }

    int deleteSlot(UUID ownerId, UUID slotId) {
        return jdbc.sql("""
                        DELETE FROM timetable_slots s USING courses c
                        WHERE s.id = :slotId AND s.owner_id = :ownerId
                          AND c.id = s.course_id AND c.owner_id = s.owner_id AND c.deleted_at IS NULL
                        """)
                .param("slotId", slotId).param("ownerId", ownerId).update();
    }

    ClassSession createSession(UUID ownerId, UUID courseId, UUID id, SessionWrite request, Instant now) {
        return jdbc.sql("""
                        INSERT INTO class_sessions
                            (id, owner_id, course_id, session_number, title, session_date,
                             starts_at, ends_at, created_at, updated_at)
                        SELECT :id, :ownerId, c.id, :sessionNumber, :title, :sessionDate,
                               :startsAt, :endsAt, :now, :now
                        FROM courses c WHERE c.id = :courseId AND c.owner_id = :ownerId AND c.deleted_at IS NULL
                        RETURNING id, course_id, session_number, title, session_date,
                                  starts_at, ends_at, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("courseId", courseId)
                .param("sessionNumber", request.sessionNumber()).param("title", request.title())
                .param("sessionDate", Date.valueOf(request.sessionDate()))
                .param("startsAt", timestamp(request.startsAt())).param("endsAt", timestamp(request.endsAt()))
                .param("now", Timestamp.from(now)).query((row, ignored) -> session(row)).optional()
                .orElse(null);
    }

    List<ClassSession> listSessions(UUID ownerId, UUID courseId) {
        return jdbc.sql("""
                        SELECT id, course_id, session_number, title, session_date,
                               starts_at, ends_at, created_at, updated_at
                        FROM class_sessions WHERE owner_id = :ownerId AND course_id = :courseId
                        ORDER BY session_date, session_number, id
                        """)
                .param("ownerId", ownerId).param("courseId", courseId)
                .query((row, ignored) -> session(row)).list();
    }

    Optional<ClassSession> getSession(UUID ownerId, UUID sessionId) {
        return jdbc.sql("""
                        SELECT s.id, s.course_id, s.session_number, s.title, s.session_date,
                               s.starts_at, s.ends_at, s.created_at, s.updated_at
                        FROM class_sessions s JOIN courses c ON c.id = s.course_id AND c.owner_id = s.owner_id
                        WHERE s.owner_id = :ownerId AND s.id = :sessionId AND c.deleted_at IS NULL
                        """)
                .param("ownerId", ownerId).param("sessionId", sessionId)
                .query((row, ignored) -> session(row)).optional();
    }

    int countSessionsInScope(UUID ownerId, UUID courseId, List<UUID> sessionIds) {
        return jdbc.sql("""
                        SELECT count(*) FROM class_sessions
                        WHERE owner_id = :ownerId AND course_id = :courseId AND id IN (:sessionIds)
                        """)
                .param("ownerId", ownerId).param("courseId", courseId).param("sessionIds", sessionIds)
                .query(Integer.class).single();
    }

    Exam createExam(UUID ownerId, UUID courseId, UUID id, ExamWrite request, Instant now) {
        Exam exam = jdbc.sql("""
                        INSERT INTO exams (id, owner_id, course_id, title, exam_at, created_at, updated_at)
                        SELECT :id, :ownerId, c.id, :title, :examAt, :now, :now
                        FROM courses c WHERE c.id = :courseId AND c.owner_id = :ownerId AND c.deleted_at IS NULL
                        RETURNING id, course_id, title, exam_at, created_at, updated_at
                        """)
                .param("id", id).param("ownerId", ownerId).param("courseId", courseId)
                .param("title", request.title()).param("examAt", Timestamp.from(request.examAt()))
                .param("now", Timestamp.from(now)).query((row, ignored) -> exam(row, request.sessionIds()))
                .optional().orElse(null);
        if (exam != null) {
            for (UUID sessionId : request.sessionIds()) {
                jdbc.sql("""
                                INSERT INTO exam_session_members (exam_id, session_id, owner_id, course_id, created_at)
                                SELECT e.id, s.id, e.owner_id, e.course_id, :now
                                FROM exams e JOIN class_sessions s
                                  ON s.owner_id = e.owner_id AND s.course_id = e.course_id
                                WHERE e.id = :examId AND e.owner_id = :ownerId AND e.course_id = :courseId
                                  AND s.id = :sessionId
                                """)
                        .param("examId", id).param("sessionId", sessionId).param("ownerId", ownerId)
                        .param("courseId", courseId).param("now", Timestamp.from(now)).update();
            }
        }
        return exam;
    }

    List<Exam> listExams(UUID ownerId, UUID courseId) {
        return jdbc.sql("""
                        SELECT e.id, e.course_id, e.title, e.exam_at, e.created_at, e.updated_at,
                               array_agg(m.session_id ORDER BY m.session_id) AS session_ids
                        FROM exams e JOIN exam_session_members m ON m.exam_id = e.id
                        WHERE e.owner_id = :ownerId AND e.course_id = :courseId
                        GROUP BY e.id ORDER BY e.exam_at, e.id
                        """)
                .param("ownerId", ownerId).param("courseId", courseId)
                .query((row, ignored) -> exam(row, List.of((UUID[]) row.getArray("session_ids").getArray())))
                .list();
    }

    private static Course course(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Course(row.getObject("id", UUID.class), row.getString("name"), row.getString("instructor"),
                row.getString("term"), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static TimetableSlot slot(java.sql.ResultSet row) throws java.sql.SQLException {
        return new TimetableSlot(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getInt("weekday"), row.getObject("start_time", LocalTime.class),
                row.getObject("end_time", LocalTime.class), row.getString("timezone"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static ClassSession session(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ClassSession(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getInt("session_number"), row.getString("title"), row.getObject("session_date", LocalDate.class),
                nullableInstant(row, "starts_at"), nullableInstant(row, "ends_at"),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Exam exam(java.sql.ResultSet row, List<UUID> sessionIds) throws java.sql.SQLException {
        return new Exam(row.getObject("id", UUID.class), row.getObject("course_id", UUID.class),
                row.getString("title"), instant(row, "exam_at"), sessionIds,
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    record Course(UUID id, String name, String instructor, String term, Instant createdAt, Instant updatedAt) {}
    record TimetableSlot(UUID id, UUID courseId, int weekday, LocalTime startTime, LocalTime endTime,
                         String timezone, Instant createdAt, Instant updatedAt) {}
    record ClassSession(UUID id, UUID courseId, int sessionNumber, String title, LocalDate sessionDate,
                        Instant startsAt, Instant endsAt, Instant createdAt, Instant updatedAt) {}
    record Exam(UUID id, UUID courseId, String title, Instant examAt, List<UUID> sessionIds,
                Instant createdAt, Instant updatedAt) {}
    record SlotWrite(UUID courseId, int weekday, LocalTime startTime, LocalTime endTime, String timezone) {}
    record SessionWrite(int sessionNumber, String title, LocalDate sessionDate, Instant startsAt, Instant endsAt) {}
    record ExamWrite(String title, Instant examAt, List<UUID> sessionIds) {}
}
