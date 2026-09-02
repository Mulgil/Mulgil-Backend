package com.mulgil.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
class NotificationScheduler implements JobCompletionListener {
    private static final Set<String> PROCESSING_COMPLETIONS = Set.of(
            "chunk_embed", "preview_generate", "review_generate", "exam_summary_generate", "exam_quiz_generate");

    private final JdbcClient jdbc;
    private final ObjectProvider<JobQueue> jobs;
    private final ObjectMapper json;
    private final MulgilProperties properties;
    private final Clock clock;

    NotificationScheduler(JdbcClient jdbc, ObjectProvider<JobQueue> jobs, ObjectMapper json,
                          MulgilProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${NOTIFICATION_POLL_INTERVAL_MILLIS:60000}")
    @Transactional
    public void schedule() {
        Instant now = clock.instant();
        schedulePostClass(now);
        scheduleExams(now);
        enqueueDue(now);
    }

    @Override
    @Transactional
    public void onCompleted(JobQueue.CompletionEvent event) {
        if (!PROCESSING_COMPLETIONS.contains(event.type()) || !courseActive(event.ownerId(), event.courseId())) return;
        Instant now = clock.instant();
        List<UUID> tokens = jdbc.sql("SELECT id FROM device_tokens WHERE owner_id=:owner ORDER BY id")
                .param("owner", event.ownerId()).query(UUID.class).list();
        for (UUID tokenId : tokens) {
            insert(new Pending(event.ownerId(), event.courseId(), event.sessionId(), tokenId,
                    "processing_complete", "처리가 완료됐어요", "결과를 확인해 보세요.",
                    event.sessionId().toString(), "mulgil://sessions/" + event.sessionId(), now,
                    key("processing", event.jobId(), tokenId)), now);
        }
        enqueueDue(now);
    }

    private void schedulePostClass(Instant now) {
        List<PostClassCandidate> candidates = jdbc.sql("""
                SELECT session.owner_id,session.course_id,session.id AS session_id,token.id AS token_id,
                       COALESCE(session.ends_at,
                           (session.session_date + slot.end_time) AT TIME ZONE slot.timezone) AS class_ends_at
                FROM class_sessions session
                JOIN courses course ON course.id=session.course_id AND course.owner_id=session.owner_id
                JOIN device_tokens token ON token.owner_id=session.owner_id
                LEFT JOIN LATERAL (
                    SELECT timetable.end_time,timetable.timezone FROM timetable_slots timetable
                    WHERE timetable.owner_id=session.owner_id AND timetable.course_id=session.course_id
                      AND timetable.weekday=EXTRACT(ISODOW FROM session.session_date)
                    ORDER BY timetable.start_time,timetable.id LIMIT 1
                ) slot ON true
                WHERE course.deleted_at IS NULL AND (session.ends_at IS NOT NULL OR slot.end_time IS NOT NULL)
                """).query((row, ignored) -> new PostClassCandidate(row.getObject("owner_id", UUID.class),
                row.getObject("course_id", UUID.class), row.getObject("session_id", UUID.class),
                row.getObject("token_id", UUID.class), row.getTimestamp("class_ends_at").toInstant())).list();
        for (PostClassCandidate candidate : candidates) {
            Instant scheduledAt = candidate.classEndsAt().plusSeconds(
                    properties.notifications().postClassReminderHours() * 3600L);
            insert(new Pending(candidate.ownerId(), candidate.courseId(), candidate.sessionId(), candidate.tokenId(),
                    "post_class_reminder", "수업 복습 알림", "복습할 시간이에요.",
                    candidate.sessionId().toString(), "mulgil://sessions/" + candidate.sessionId() + "/summary/review",
                    scheduledAt, key("post-class", candidate.sessionId(), candidate.tokenId())), now);
        }
    }

    private void scheduleExams(Instant now) {
        List<ExamCandidate> candidates = jdbc.sql("""
                SELECT exam.owner_id,exam.course_id,exam.id AS exam_id,exam.exam_at,
                       token.id AS token_id,member.session_id
                FROM exams exam
                JOIN courses course ON course.id=exam.course_id AND course.owner_id=exam.owner_id
                JOIN device_tokens token ON token.owner_id=exam.owner_id
                JOIN LATERAL (
                    SELECT session_id FROM exam_session_members
                    WHERE owner_id=exam.owner_id AND course_id=exam.course_id AND exam_id=exam.id
                    ORDER BY session_id LIMIT 1
                ) member ON true
                WHERE course.deleted_at IS NULL
                """).query((row, ignored) -> new ExamCandidate(row.getObject("owner_id", UUID.class),
                row.getObject("course_id", UUID.class), row.getObject("session_id", UUID.class),
                row.getObject("exam_id", UUID.class), row.getObject("token_id", UUID.class),
                row.getTimestamp("exam_at").toInstant())).list();
        for (ExamCandidate candidate : candidates) {
            for (int days : properties.notifications().examReminderDays()) {
                Instant scheduledAt = candidate.examAt().minusSeconds(days * 86400L);
                insert(new Pending(candidate.ownerId(), candidate.courseId(), candidate.sessionId(), candidate.tokenId(),
                        "exam_reminder", "시험 리마인더", "시험 일정을 확인해 보세요.",
                        candidate.examId().toString(), "mulgil://exams/" + candidate.examId(), scheduledAt,
                        key("exam-" + days, candidate.examId(), candidate.tokenId())), now);
            }
        }
    }

    private void insert(Pending pending, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO notifications
                            (id,owner_id,course_id,session_id,device_token_id,notification_type,title,body,
                             data_json,deep_link,scheduled_at,status,created_at)
                        VALUES (:id,:owner,:course,:session,:token,:type,:title,:body,CAST(:data AS jsonb),
                                :deepLink,:scheduled,'scheduled',:created)
                        ON CONFLICT (id) DO NOTHING
                        """).param("id", pending.id()).param("owner", pending.ownerId())
                .param("course", pending.courseId()).param("session", pending.sessionId())
                .param("token", pending.tokenId()).param("type", pending.type()).param("title", pending.title())
                .param("body", pending.body()).param("data", data(pending.resourceId(), pending.deepLink()))
                .param("deepLink", pending.deepLink()).param("scheduled", Timestamp.from(pending.scheduledAt()))
                .param("created", Timestamp.from(createdAt)).update();
    }

    private void enqueueDue(Instant now) {
        List<DueNotification> due = jdbc.sql("""
                SELECT notification.id,notification.owner_id,notification.course_id,notification.session_id
                FROM notifications notification
                JOIN courses course ON course.id=notification.course_id
                  AND course.owner_id=notification.owner_id
                WHERE notification.status='scheduled' AND notification.scheduled_at<=:now
                  AND course.deleted_at IS NULL
                ORDER BY notification.scheduled_at,notification.id
                """).param("now", Timestamp.from(now)).query((row, ignored) -> new DueNotification(
                row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
                row.getObject("course_id", UUID.class), row.getObject("session_id", UUID.class))).list();
        for (DueNotification notification : due) {
            jobs.getObject().enqueue(new JobQueue.EnqueueRequest("notification_send", notification.ownerId(),
                    notification.courseId(), notification.sessionId(), null, null, null, null, null,
                    1, hash(notification.id()), "fcm", "firebase-admin", "none"));
        }
    }

    private String data(String resourceId, String deepLink) {
        try {
            return json.writeValueAsString(Map.of("resourceId", resourceId, "deepLink", deepLink));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean courseActive(UUID ownerId, UUID courseId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM courses
                            WHERE owner_id=:owner AND id=:course AND deleted_at IS NULL
                        )
                        """)
                .param("owner", ownerId).param("course", courseId).query(Boolean.class).single();
    }

    static String hash(UUID notificationId) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(notificationId.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static UUID key(String type, UUID resourceId, UUID tokenId) {
        return UUID.nameUUIDFromBytes((type + ":" + resourceId + ":" + tokenId).getBytes(StandardCharsets.UTF_8));
    }

    private record PostClassCandidate(UUID ownerId, UUID courseId, UUID sessionId, UUID tokenId,
                                      Instant classEndsAt) {}
    private record ExamCandidate(UUID ownerId, UUID courseId, UUID sessionId, UUID examId, UUID tokenId,
                                 Instant examAt) {}
    private record DueNotification(UUID id, UUID ownerId, UUID courseId, UUID sessionId) {}
    private record Pending(UUID ownerId, UUID courseId, UUID sessionId, UUID tokenId, String type,
                           String title, String body, String resourceId, String deepLink, Instant scheduledAt,
                           UUID id) {}
}
