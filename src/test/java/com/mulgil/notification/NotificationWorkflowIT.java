package com.mulgil.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.mulgil.MulgilApplication.class, NotificationWorkflowIT.FixedTime.class},
        properties = "mulgil.fcm.enabled=true")
class NotificationWorkflowIT {
    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");
    private static final Path EVIDENCE = Path.of(".omo/evidence/mvp-backend-implementation/task-9");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired NotificationScheduler scheduler;
    @Autowired NotificationSendJobHandler handler;
    @Autowired JobQueue jobs;
    @Autowired TestFcmAdapter fcm;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        fcm.reset();
    }

    @Test
    void schedulesAndSendsPrivacySafeNotifications_whenOwnerRegistersDevice() throws Exception {
        // Given
        String ownerToken = login("notification-owner");
        String foreignToken = login("notification-foreign");
        JsonNode device = successful(send("PUT", "/api/v1/devices/fcm-token", ownerToken,
                Map.of("token", "owner-fcm-token", "platform", "android", "timezone", "Asia/Seoul")), 200);
        String courseId = successful(send("POST", "/api/v1/courses", ownerToken,
                Map.of("name", "Algorithms")), 201).path("id").asText();
        successful(send("POST", "/api/v1/timetable/slots", ownerToken, Map.of(
                "courseId", courseId, "weekday", 2, "startTime", "09:00", "endTime", "10:00",
                "timezone", "Asia/Seoul")), 201);
        String sessionId = successful(send("POST", "/api/v1/courses/" + courseId + "/sessions", ownerToken,
                Map.of("sessionNumber", 1, "title", "Graphs", "sessionDate", "2026-09-01")), 201)
                .path("id").asText();
        String examId = successful(send("POST", "/api/v1/courses/" + courseId + "/exams", ownerToken,
                Map.of("title", "Midterm", "examAt", "2026-09-15T00:00:00Z", "sessionIds", List.of(sessionId))),
                201).path("id").asText();

        // When
        scheduler.schedule();
        JobQueue.ClaimedJob claimed = jobs.claim("notification-test", Set.of("notification_send"));
        assertThat(jobs.complete(claimed, handler.handle(claimed))).isTrue();

        // Then
        assertThat(device.path("timezone").asText()).isEqualTo("Asia/Seoul");
        assertThat(jdbc.sql("SELECT scheduled_at FROM notifications WHERE notification_type='post_class_reminder'")
                .query(Instant.class).single()).isEqualTo(Instant.parse("2026-09-01T04:00:00Z"));
        assertThat(jdbc.sql("SELECT scheduled_at FROM notifications WHERE notification_type='exam_reminder' ORDER BY scheduled_at")
                .query(Instant.class).list()).containsExactly(
                        Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z"),
                        Instant.parse("2026-09-14T00:00:00Z"));
        assertThat(fcm.sent()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_usage")
                .query(Integer.class).single()).isZero();
        FcmPort.Message payload = fcm.sent().getFirst().message();
        assertThat(payload.resourceId()).isEqualTo(examId);
        assertThat(payload.deepLink()).isEqualTo("mulgil://exams/" + examId);
        assertThat(successful(send("GET", "/api/v1/notifications", ownerToken, null), 200)).hasSize(4);
        assertThat(send("DELETE", "/api/v1/courses/" + courseId, ownerToken, null).statusCode()).isEqualTo(204);
        assertThat(successful(send("GET", "/api/v1/notifications", ownerToken, null), 200)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE course_id=:course")
                .param("course", UUID.fromString(courseId)).query(Integer.class).single()).isEqualTo(4);
        assertThat(successful(send("GET", "/api/v1/notifications", foreignToken, null), 200)).isEmpty();
        assertThat(send("DELETE", "/api/v1/devices/fcm-token", foreignToken,
                Map.of("token", "owner-fcm-token")).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM device_tokens WHERE token='owner-fcm-token'")
                .query(Integer.class).single()).isEqualTo(1);

        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("notification-payload.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("title", payload.title(), "body", payload.body(),
                        "resourceId", payload.resourceId(), "deepLink", payload.deepLink())));
    }

    @Test
    void rejectsInvalidBoundariesAndSanitizesFailures_whenProviderFails() throws Exception {
        // Given
        String token = login("notification-failure");
        assertError(send("PUT", "/api/v1/devices/fcm-token", token,
                Map.of("token", "contains space", "platform", "android", "timezone", "Asia/Seoul")),
                422, "VALIDATION_FAILED");
        assertError(send("PUT", "/api/v1/devices/fcm-token", token,
                Map.of("token", "valid-token", "platform", "android", "timezone", "Mars/Olympus")),
                422, "VALIDATION_FAILED");
        assertError(send("PUT", "/api/v1/devices/fcm-token", token,
                Map.of("token", "plus-timezone-token", "platform", "android", "timezone", "+")),
                422, "VALIDATION_FAILED");
        successful(send("PUT", "/api/v1/devices/fcm-token", token,
                Map.of("token", "failure-token", "platform", "android", "timezone", "UTC")), 200);
        UUID owner = jdbc.sql("SELECT id FROM users WHERE provider_subject='notification-failure'")
                .query(UUID.class).single();
        UUID course = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.sql("INSERT INTO courses(id,owner_id,name,created_at,updated_at) VALUES(:id,:owner,'Course',:now,:now)")
                .param("id", course).param("owner", owner).param("now", Timestamp.from(NOW)).update();
        jdbc.sql("""
                INSERT INTO class_sessions(id,owner_id,course_id,session_number,title,session_date,created_at,updated_at)
                VALUES(:id,:owner,:course,1,'Session','2026-09-01',:now,:now)
                """).param("id", session).param("owner", owner).param("course", course)
                .param("now", Timestamp.from(NOW)).update();
        scheduler.onCompleted(new JobQueue.CompletionEvent(UUID.randomUUID(), "chunk_embed", owner, course, session,
                null, null, null, null, null, 1, "sensitive chunk input"));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE notification_type='processing_complete'")
                .query(Integer.class).single()).isZero();
        fcm.failNext("PROVIDER_TIMEOUT", true);
        scheduler.onCompleted(new JobQueue.CompletionEvent(UUID.randomUUID(), "preview_generate", owner, course, session,
                null, null, null, null, null, 1, "source text transcript signed URL token object key"));
        JobQueue.ClaimedJob claimed = jobs.claim("notification-failure-test", Set.of("notification_send"));

        // When
        JobHandler.JobExecutionException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                JobHandler.JobExecutionException.class, () -> handler.handle(claimed));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        // Then
        String notificationStatus = jdbc.sql("SELECT status FROM notifications WHERE notification_type='processing_complete'")
                .query(String.class).single();
        JobQueue.AiJob failedJob = jobs.get(owner, claimed.id());
        String combined = notificationStatus + " " + failedJob.status() + " " + failedJob.errorCode() + " "
                + jdbc.sql("SELECT COALESCE(error_message,'') FROM ai_jobs WHERE id=:id")
                .param("id", claimed.id()).query(String.class).single();
        assertThat(notificationStatus).isEqualTo("failed");
        assertThat(failedJob.status()).isEqualTo("failed");
        assertThat(failedJob.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(combined).doesNotContain("source text", "transcript", "signed URL", "failure-token", "object key");
        assertThatThrownBy(() -> fcm.sent().getFirst()).isInstanceOf(java.util.NoSuchElementException.class);

        fcm.failNext("INVALID_ARGUMENT", false);
        scheduler.onCompleted(new JobQueue.CompletionEvent(UUID.randomUUID(), "preview_generate", owner, course, session,
                null, null, null, null, null, 1, "different-sensitive-input"));
        JobQueue.ClaimedJob nonRetryable = jobs.claim("notification-nonretryable-test", Set.of("notification_send"));
        JobHandler.JobExecutionException terminalFailure = org.assertj.core.api.Assertions.catchThrowableOfType(
                JobHandler.JobExecutionException.class, () -> handler.handle(nonRetryable));
        jobs.fail(nonRetryable, terminalFailure.code(), terminalFailure.getMessage(), terminalFailure.retryable());
        assertThat(jobs.get(owner, nonRetryable.id()).errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE notification_type='processing_complete' AND status='failed'")
                .query(Integer.class).single()).isEqualTo(2);

        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("notification-privacy-failure.txt"),
                "malformed-token=422\nmalformed-timezone=422\nmalformed-plus-timezone=422\n"
                        + "notification-status=failed\njob-status=failed\n"
                        + "retryable-job-error=PROVIDER_TIMEOUT\nnonretryable-job-error=INVALID_ARGUMENT\n"
                        + "forbidden-content-scan=PASS\n");
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(NOW.plusSeconds(300).getEpochSecond()));
        return successful(send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fake)), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json; charset=utf-8");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode successful(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        return response.body().isEmpty() ? json.nullNode() : json.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
