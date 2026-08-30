package com.mulgil.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.recording.FinalRecordingTestFakes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mulgil.demo.cache-enabled=true", "mulgil.demo.max-ai-jobs-per-day=30",
        "JOB_POLL_INTERVAL_MILLIS=600000", "NOTIFICATION_POLL_INTERVAL_MILLIS=600000"})
@Import({FinalIntegrationFakes.class, FinalRecordingTestFakes.class})
class FinalIntegrationQaTest {
    private static final String SECRET = "sentinel-secret";
    private static final String RAW = "raw-user-content";

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
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired MulgilProperties properties;
    @Autowired FinalIntegrationFakes.FakeGcs gcs;
    @Autowired FinalIntegrationFakes.FakeProbe probe;
    @Autowired FinalIntegrationFakes.FakeVision vision;
    @Autowired @Qualifier("vertex") FinalIntegrationFakes.VertexState vertex;
    @Autowired FinalIntegrationFakes.FakeFcm fcm;
    @Autowired FinalRecordingTestFakes.Counters recording;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void drivesFinalBackendDemo_withProviderAndCostFailureGuards(CapturedOutput output) throws Exception {
        gcs.reset(); probe.reset(); vision.reset(); vertex.reset(); fcm.reset(); recording.reset();
        String ownerToken = login("final-owner");
        String foreignToken = login("final-foreign");
        UUID owner = jdbc.sql("SELECT id FROM users WHERE provider_subject='final-owner'")
                .query(UUID.class).single();
        UUID course = id(ok(send("POST", "/api/v1/courses", ownerToken, Map.of("name", "Demo")), 201));
        UUID session = id(ok(send("POST", "/api/v1/courses/" + course + "/sessions", ownerToken, Map.of(
                "sessionNumber", 1, "title", "Integrated", "sessionDate", "2026-09-01",
                "startsAt", "2026-09-01T00:00:00Z", "endsAt", "2026-09-01T03:00:00Z")), 201));
        UUID exam = id(ok(send("POST", "/api/v1/courses/" + course + "/exams", ownerToken, Map.of(
                "title", "Final", "examAt", "2026-10-01T00:00:00Z", "sessionIds", List.of(session))), 201));
        error(send("GET", "/api/v1/sessions/" + session, foreignToken, null), 404, "RESOURCE_NOT_FOUND");
        UUID preview = uploadMaterial(ownerToken, session, pdf("preview ".repeat(12)), "preview_pdf");
        UUID review = uploadMaterial(ownerToken, session, pdf(""), "review_pdf");
        runAll("pdf_extract"); runAll("pdf_ocr"); runAll("chunk_embed");
        assertThat(vision.calls()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM document_pages WHERE material_id IN (:ids)")
                .param("ids", List.of(preview, review)).query(Integer.class).single()).isEqualTo(2);

        UUID stroke = UUID.randomUUID();
        ok(send("PUT", "/api/v1/materials/" + review + "/annotations", ownerToken, Map.of(
                "expectedVersion", 0, "inkStrokes", List.of(Map.of("id", stroke, "pageNumber", 1,
                        "tool", "pen", "color", "#000000", "widthNorm", 0.01,
                        "points", List.of(Map.of("x", 0.1, "y", 0.1), Map.of("x", 0.3, "y", 0.3)),
                        "bboxNorm", Map.of("x", 0.1, "y", 0.1, "width", 0.2, "height", 0.2))),
                "emphasisRegions", List.of())), 200);
        ok(send("POST", "/api/v1/materials/" + review + "/annotations/leave", ownerToken,
                Map.of("changedVersion", 1)), 202);
        runAll("handwriting_ocr");
        UUID handwriting = jdbc.sql("SELECT id FROM handwriting_blocks WHERE owner_id=:owner AND status='needs_user_review'")
                .param("owner", owner).query(UUID.class).single();
        ok(send("PATCH", "/api/v1/handwriting-blocks/" + handwriting + "/confirm", ownerToken,
                Map.of("confirmedText", "confirmed handwriting")), 202);
        runAll("chunk_embed");

        JsonNode note = ok(send("POST", "/api/v1/sessions/" + session + "/notes", ownerToken,
                Map.of("bodyMarkdown", "review note")), 201);
        ok(send("POST", "/api/v1/notes/" + note.path("id").asText() + "/leave", ownerToken,
                Map.of("changedVersion", 1)), 202);
        runAll("chunk_embed");

        UUID firstRecording = uploadRecording(ownerToken, 6000);
        UUID secondRecording = uploadRecording(ownerToken, 6000);
        List<Integer> mappingStatuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> send("POST", "/api/v1/recordings/" + firstRecording
                    + "/confirm-mapping", ownerToken, Map.of("sessionId", session)).status());
            var second = pool.submit(() -> send("POST", "/api/v1/recordings/" + secondRecording
                    + "/confirm-mapping", ownerToken, Map.of("sessionId", session)).status());
            mappingStatuses.add(first.get()); mappingStatuses.add(second.get());
        }
        assertThat(mappingStatuses).containsExactlyInAnyOrder(202, 422);
        runAll("stt"); runAll("chunk_embed"); runAll("review_generate");
        assertThat(recording.starts()).isOne();

        JsonNode generated = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review",
                ownerToken, null), 200);
        assertThat(generated.path("summary").path("items").get(0).path("sourceRefs")).isNotEmpty();
        JsonNode quiz = ok(send("GET", "/api/v1/sessions/" + session + "/quiz", ownerToken, null), 200);
        UUID question = UUID.fromString(quiz.get(0).path("id").asText());
        JsonNode attempt = ok(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of("answer", true)), 201);
        assertThat(attempt.path("isCorrect").asBoolean()).isTrue();

        uploadExamResource(ownerToken, exam, pdf("past exam ".repeat(10)));
        runAll("pdf_extract"); runAll("pdf_ocr"); runAll("chunk_embed");
        int beforeGeneration = vertex.generationCalls();
        JsonNode predicted = ok(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate",
                ownerToken, Map.of()), 202);
        JsonNode cached = ok(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate",
                ownerToken, Map.of()), 202);
        assertThat(cached.path("jobId").asText()).isEqualTo(predicted.path("jobId").asText());
        ok(send("PUT", "/api/v1/devices/fcm-token", ownerToken,
                Map.of("token", "device-token-redacted", "platform", "android", "timezone", "Asia/Seoul")), 200);
        runAll("exam_quiz_generate");
        assertThat(vertex.generationCalls() - beforeGeneration).isOne();

        runAll("notification_send");
        JsonNode notifications = ok(send("GET", "/api/v1/notifications", ownerToken, null), 200);
        assertThat(notifications).isNotEmpty();
        assertThat(fcm.last().deepLink()).startsWith("mulgil://");
        assertThat(fcm.last().toString()).doesNotContain("transcript", "source.pdf", "device-token-redacted");

        exerciseFailures(ownerToken, owner, course, session, exam);
        assertThat(properties.jobs().maxRetry()).isEqualTo(2);
        assertThat(properties.uploads().maxPdfsPerSession()).isEqualTo(5);
        assertThat(properties.uploads().maxAudioDurationSeconds()).isEqualTo(10_800);
        assertThat(gcs.calls()).isPositive();
        assertThat(vision.calls()).isPositive();
        assertThat(vertex.embeddingCalls()).isPositive();
        assertThat(vertex.generationCalls()).isPositive();
        assertThat(fcm.calls()).isPositive();
        assertThat(recording.starts()).isPositive();
        assertThat(output.getAll()).doesNotContain(SECRET, RAW, "device-token-redacted", "review note",
                "confirmed handwriting", "lecture transcript");
        recordEvidence(cached.path("jobId").asText().equals(predicted.path("jobId").asText()));
        System.out.println("FINAL_INTEGRATION scenario=demo-e2e observable=two-owner-full-flow-cache-quota-safe-logs result=PASS");
    }

    private void recordEvidence(boolean cacheHit) throws Exception {
        Path directory = Path.of(".omo/evidence/mvp-backend-implementation/task-12");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("provider-call-counts.txt"), """
                scenario=random-port-test-profile-fakes
                real_provider_calls=0
                gcs_calls=%d
                vision_calls=%d
                vertex_embedding_calls=%d
                vertex_generation_calls=%d
                stt_start_calls=%d
                fcm_calls=%d
                """.formatted(gcs.calls(), vision.calls(), vertex.embeddingCalls(), vertex.generationCalls(),
                recording.starts(), fcm.calls()));
        Files.writeString(directory.resolve("cache-and-guards.txt"), """
                cache_same_job=%s
                repeated_cache_provider_call_delta=1
                daily_generation_limit=30
                quota_status=429
                audio_duration_limit_seconds=10800
                stale_input=observed
                provider_timeout=observed
                invalid_source_reference=observed
                malformed_provider_output=observed
                raw_or_secret_log_match=0
                """.formatted(cacheHit));
        Files.writeString(directory.resolve("backend-e2e.txt"), """
                invocation=./gradlew test --tests com.mulgil.integration.FinalIntegrationQaTest --no-daemon
                surface=random-port HTTP API with PostgreSQL+pgvector Testcontainer
                identities=2
                owner_denial_status=404
                flow=login-course-session-exam-upload-ocr-handwriting-recording-stt-generation-quiz-progress-past-exam-predicted-notification
                result=PASS
                """);
    }

    private void exerciseFailures(String token, UUID owner, UUID course, UUID session, UUID exam) throws Exception {
        UUID failureSession = id(ok(send("POST", "/api/v1/courses/" + course + "/sessions", token, Map.of(
                "sessionNumber", 2, "title", "Failure guards", "sessionDate", "2026-09-02",
                "startsAt", "2026-09-02T00:00:00Z", "endsAt", "2026-09-02T01:00:00Z")), 201));
        probe.duration(10_801);
        JsonNode capped = ok(send("POST", "/api/v1/recordings/upload-url", token, Map.of(
                "filename", "too-long.m4a", "mimeType", "audio/m4a", "byteSize", 4,
                "startedAt", "2026-09-01T00:00:00Z")), 201);
        byte[] audio = new byte[]{1, 2, 3, 4}; gcs.putLast(audio);
        error(send("POST", "/api/v1/recordings/" + capped.path("id").asText() + "/upload-complete", token,
                Map.of("checksumSha256", FinalIntegrationFakes.FakeGcs.hash(audio))), 422, "UPLOAD_LIMIT_EXCEEDED");
        probe.duration(1200);

        UUID stale = uploadMaterial(token, failureSession, pdf("stale version source"), "preview_pdf");
        jdbc.sql("UPDATE materials SET version=version+1 WHERE id=:id").param("id", stale).update();
        runAll("pdf_extract");
        assertThat(hasError("pdf_extract", "STALE_INPUT")).isTrue();

        addIndexedNote(token, failureSession, "timeout source");
        runAll("chunk_embed");
        vertex.mode(FinalIntegrationFakes.VertexState.Mode.TIMEOUT);
        runAll("review_generate");
        assertThat(hasError("review_generate", "PROVIDER_UNAVAILABLE")).isTrue();
        UUID timeoutJob = jdbc.sql("""
                SELECT id FROM ai_jobs
                WHERE job_type='review_generate' AND error_code='PROVIDER_UNAVAILABLE'
                ORDER BY created_at DESC,id DESC LIMIT 1
                """).query(UUID.class).single();
        vertex.mode(FinalIntegrationFakes.VertexState.Mode.VALID);
        ok(send("POST", "/api/v1/jobs/" + timeoutJob + "/retry", token, Map.of()), 202);
        runAll("review_generate");
        vertex.mode(FinalIntegrationFakes.VertexState.Mode.INVALID_REF);
        addIndexedNote(token, failureSession, "invalid ref source"); runAll("chunk_embed"); runAll("review_generate");
        assertThat(hasError("review_generate", "INVALID_SOURCE_REFERENCES")).isTrue();
        vertex.mode(FinalIntegrationFakes.VertexState.Mode.MALFORMED);
        int malformedCalls = vertex.generationCalls();
        int failedGenerations = failedJobs("review_generate");
        addIndexedNote(token, failureSession, "malformed source"); runAll("chunk_embed"); runAll("review_generate");
        assertThat(vertex.generationCalls()).isGreaterThan(malformedCalls);
        assertThat(failedJobs("review_generate")).isGreaterThan(failedGenerations);
        vertex.mode(FinalIntegrationFakes.VertexState.Mode.VALID);

        int count = jdbc.sql("""
                        SELECT count(*) FROM ai_jobs WHERE owner_id=:owner AND created_at>=date_trunc('day',now())
                          AND job_type IN ('pdf_ocr','handwriting_ocr','stt','chunk_embed',
                                           'preview_generate','review_generate',
                                           'exam_summary_generate','exam_quiz_generate')
                        """)
                .param("owner", owner).query(Integer.class).single();
        for (int index = count; index < 30; index++) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO ai_jobs(id,owner_id,course_id,session_id,job_type,status,input_version,
                        idempotency_key,attempt_count,max_attempts,source_hash,created_at)
                    VALUES (:id,:owner,:course,:session,'review_generate','succeeded',1,:key,1,3,:hash,now())
                    """).param("id", id).param("owner", owner).param("course", course).param("session", session)
                    .param("key", "quota-" + id).param("hash", "f".repeat(64)).update();
        }
        UUID quotaExam = id(ok(send("POST", "/api/v1/courses/" + course + "/exams", token, Map.of(
                "title", "Quota", "examAt", "2026-11-01T00:00:00Z", "sessionIds", List.of(session))), 201));
        error(send("POST", "/api/v1/exams/" + quotaExam + "/summary/generate", token, Map.of()),
                429, "AI_DAILY_LIMIT_REACHED");
    }

    private void addIndexedNote(String token, UUID session, String body) throws Exception {
        JsonNode note = ok(send("POST", "/api/v1/sessions/" + session + "/notes", token,
                Map.of("bodyMarkdown", body)), 201);
        ok(send("POST", "/api/v1/notes/" + note.path("id").asText() + "/leave", token,
                Map.of("changedVersion", 1)), 202);
    }

    private UUID uploadMaterial(String token, UUID session, byte[] bytes, String phase) throws Exception {
        JsonNode issued = ok(send("POST", "/api/v1/sessions/" + session + "/materials/upload-url", token,
                Map.of("filename", phase + ".pdf", "mimeType", "application/pdf", "byteSize", bytes.length,
                        "sourcePhase", phase)), 201);
        gcs.putLast(bytes);
        ok(send("POST", "/api/v1/materials/" + issued.path("id").asText() + "/upload-complete", token,
                Map.of("checksumSha256", FinalIntegrationFakes.FakeGcs.hash(bytes))), 202);
        return id(issued);
    }

    private void uploadExamResource(String token, UUID exam, byte[] bytes) throws Exception {
        JsonNode issued = ok(send("POST", "/api/v1/exams/" + exam + "/resources", token,
                Map.of("filename", "past.pdf", "mimeType", "application/pdf", "byteSize", bytes.length)), 201);
        gcs.putLast(bytes);
        ok(send("POST", "/api/v1/exam-resources/" + issued.path("id").asText() + "/upload-complete", token,
                Map.of("checksumSha256", FinalIntegrationFakes.FakeGcs.hash(bytes))), 200);
    }

    private UUID uploadRecording(String token, long duration) throws Exception {
        probe.duration(duration);
        JsonNode issued = ok(send("POST", "/api/v1/recordings/upload-url", token, Map.of(
                "filename", "lecture.m4a", "mimeType", "audio/m4a", "byteSize", 4,
                "startedAt", "2026-09-01T00:00:00Z")), 201);
        byte[] bytes = new byte[]{1, 2, 3, 4}; gcs.putLast(bytes);
        ok(send("POST", "/api/v1/recordings/" + issued.path("id").asText() + "/upload-complete", token,
                Map.of("checksumSha256", FinalIntegrationFakes.FakeGcs.hash(bytes))), 200);
        return id(issued);
    }

    private void runAll(String type) throws Exception {
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
        JobQueue.ClaimedJob job;
        while ((job = jobs.claim("final-e2e", Set.of(type))) != null) {
            try { jobs.complete(job, handler.handle(job)); }
            catch (JobHandler.JobExecutionException exception) {
                jobs.fail(job, exception.code(), exception.getMessage(), exception.retryable());
            }
        }
    }

    private boolean hasError(String type, String code) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM ai_jobs WHERE job_type=:type AND error_code=:code)
                        """).param("type", type).param("code", code).query(Boolean.class).single();
    }

    private int failedJobs(String type) {
        return jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type AND status='failed'")
                .param("type", type).query(Integer.class).single();
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        return ok(send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fake)), 200)
                .path("accessToken").asText();
    }

    private Result send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Result(response.statusCode(), response.body());
    }

    private JsonNode ok(Result result, int status) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        return result.body().isEmpty() ? json.nullNode() : json.readTree(result.body());
    }

    private void error(Result result, int status, String code) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo(code);
    }

    private static UUID id(JsonNode value) { return UUID.fromString(value.path("id").asText()); }
    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER); document.addPage(page);
            if (!text.isEmpty()) try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(20, 760); content.showText(text); content.endText();
            }
            document.save(bytes); return bytes.toByteArray();
        }
    }

    private record Result(int status, String body) {}
}
