package com.mulgil.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
final class NotificationSendJobHandler implements JobHandler {
    private final JdbcClient jdbc;
    private final ObjectProvider<FcmPort> providers;
    private final ObjectMapper json;
    private final Clock clock;

    NotificationSendJobHandler(JdbcClient jdbc, ObjectProvider<FcmPort> providers, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String jobType() {
        return "notification_send";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        Delivery delivery = find(job);
        if (delivery == null) throw new JobExecutionException(
                "NOTIFICATION_NOT_FOUND", "Notification is unavailable.", false);
        if (delivery.deviceToken() == null) {
            fail(delivery.id());
            throw new JobExecutionException("DEVICE_TOKEN_UNAVAILABLE", "Device token is unavailable.", false);
        }
        FcmPort provider = providers.getIfAvailable();
        if (provider == null) {
            fail(delivery.id());
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "FCM provider unavailable.", true);
        }
        try {
            provider.send(delivery.deviceToken(), new FcmPort.Message(delivery.title(), delivery.body(),
                    delivery.resourceId(), delivery.deepLink()));
        } catch (FcmPort.FcmException exception) {
            fail(delivery.id());
            throw new JobExecutionException(exception.code(), "FCM delivery failed.", exception.retryable());
        }
        return () -> jdbc.sql("""
                        UPDATE notifications SET status='sent',sent_at=:now
                        WHERE id=:id AND owner_id=:owner AND status IN ('scheduled','failed')
                        """).param("now", Timestamp.from(clock.instant())).param("id", delivery.id())
                .param("owner", job.ownerId()).update();
    }

    private Delivery find(JobQueue.ClaimedJob job) {
        List<Delivery> candidates = jdbc.sql("""
                        SELECT notification.id,notification.title,notification.body,notification.data_json::text,
                               notification.deep_link,token.token
                        FROM notifications notification
                        LEFT JOIN device_tokens token
                          ON token.id=notification.device_token_id AND token.owner_id=notification.owner_id
                        WHERE notification.owner_id=:owner AND notification.course_id=:course
                          AND notification.session_id=:session AND notification.status IN ('scheduled','failed')
                          AND notification.scheduled_at<=:now
                        """).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("now", Timestamp.from(clock.instant()))
                .query((row, ignored) -> delivery(row.getObject("id", UUID.class), row.getString("title"),
                        row.getString("body"), row.getString("data_json"), row.getString("deep_link"),
                        row.getString("token"))).list();
        return candidates.stream().filter(candidate -> NotificationScheduler.hash(candidate.id())
                .equals(job.sourceHash())).findFirst().orElse(null);
    }

    private Delivery delivery(UUID id, String title, String body, String data, String deepLink, String token) {
        try {
            JsonNode value = json.readTree(data);
            return new Delivery(id, title, body, value.path("resourceId").asText(), deepLink, token);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void fail(UUID notificationId) {
        jdbc.sql("UPDATE notifications SET status='failed' WHERE id=:id AND status IN ('scheduled','failed')")
                .param("id", notificationId).update();
    }

    private record Delivery(UUID id, String title, String body, String resourceId, String deepLink,
                            String deviceToken) {}
}
