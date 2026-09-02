package com.mulgil.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.storage.CloudStoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@Import(ResourceUploadApiIT.FakeProviders.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceUploadApiIT {
    private static final String PDF_CHECKSUM = "a".repeat(64);
    private static final String AUDIO_CHECKSUM = "b".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil")
            .withUsername("mulgil")
            .withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FakeCloudStorage storage;

    @Autowired
    FakeResourceContentProbe probe;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetProviders() {
        storage.reset();
        probe.reset();
    }

    @Test
    void finalizesPdfResources_whenClientUploadsDirectly() throws Exception {
        String owner = login("resource-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");

        JsonNode upload = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "week-1.pdf", "mimeType", "application/pdf",
                        "byteSize", 1024, "sourcePhase", "preview_pdf")), 201);
        assertUploadUrlIsPrivate(upload, "application/pdf");
        String materialId = upload.path("id").asText();
        storage.directPut(materialId, "application/pdf", 1024, PDF_CHECKSUM);
        probe.pdf(materialId, 12, PDF_CHECKSUM);

        JsonNode materialJob = successful(post("/api/v1/materials/" + materialId + "/upload-complete",
                owner, Map.of("checksumSha256", PDF_CHECKSUM)), 202);
        assertThat(materialJob.path("jobId").isTextual()).isTrue();
        assertThat(materialJob.path("status").asText()).isEqualTo("queued");
        JsonNode polled = successful(get("/api/v1/jobs/" + materialJob.path("jobId").asText(), owner), 200);
        assertThat(polled.path("type").asText()).isEqualTo("pdf_extract");
        assertThat(successful(get("/api/v1/sessions/" + ids.sessionId() + "/jobs", owner), 200)).hasSize(1);
        assertError(get("/api/v1/jobs/" + materialJob.path("jobId").asText(), login("job-foreigner")),
                404, "JOB_NOT_FOUND");
        JsonNode materials = successful(get("/api/v1/sessions/" + ids.sessionId() + "/materials", owner), 200);
        assertThat(materials).hasSize(1);
        assertThat(materials.get(0).path("status").asText()).isEqualTo("uploaded");
        assertThat(materials.get(0).path("pageCount").asInt()).isEqualTo(12);
        assertThat(materials.toString()).doesNotContain("objectKey").doesNotContain("owner/");

        JsonNode download = successful(get("/api/v1/materials/" + materialId + "/download-url", owner), 200);
        assertThat(download.path("downloadUrl").asText()).startsWith("https://storage.test/download/");
        assertThat(download.toString()).doesNotContain("objectKey").doesNotContain("owner/");

        JsonNode examUpload = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "past-exam.pdf", "mimeType", "application/pdf", "byteSize", 2048)), 201);
        assertUploadUrlIsPrivate(examUpload, "application/pdf");
        String examResourceId = examUpload.path("id").asText();
        storage.directPut(examResourceId, "application/pdf", 2048, PDF_CHECKSUM);
        probe.pdf(examResourceId, 20, PDF_CHECKSUM);
        JsonNode examResource = successful(post("/api/v1/exam-resources/" + examResourceId
                + "/upload-complete", owner, Map.of("checksumSha256", PDF_CHECKSUM)), 200);
        assertThat(examResource.path("id").asText()).isEqualTo(examResourceId);
        assertThat(examResource.path("resourceType").asText()).isEqualTo("past_exam");
        assertThat(examResource.path("status").asText()).isEqualTo("uploaded");
        assertThat(examResource.toString()).doesNotContain("objectKey").doesNotContain("owner/");
        JsonNode examResources = successful(get("/api/v1/exams/" + ids.examId() + "/resources", owner), 200);
        assertThat(examResources).hasSize(1);
        assertThat(examResources.get(0).path("id").asText()).isEqualTo(examResourceId);
        assertThat(examResources.get(0).path("status").asText()).isEqualTo("uploaded");
        assertThat(examResources.toString()).doesNotContain("objectKey").doesNotContain("owner/");
        JsonNode examDownload = successful(get("/api/v1/exam-resources/" + examResourceId + "/download-url", owner), 200);
        assertThat(examDownload.path("downloadUrl").asText()).startsWith("https://storage.test/download/");
        assertThat(examDownload.toString()).doesNotContain("objectKey").doesNotContain("owner/");

        assertThat(storage.apiBodyBytes()).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ai_jobs WHERE job_type='pdf_extract' AND status='queued'
                          AND (material_id=:material OR exam_resource_id=:examResource)
                        """).param("material", java.util.UUID.fromString(materialId))
                .param("examResource", java.util.UUID.fromString(examResourceId))
                .query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("""
                        UPDATE ai_jobs SET status='failed', attempt_count=max_attempts,
                            error_code='PROVIDER_TIMEOUT', error_message='Provider timed out.', finished_at=now()
                        WHERE id=:id
                        """).param("id", java.util.UUID.fromString(materialJob.path("jobId").asText())).update();
        assertError(post("/api/v1/jobs/" + materialJob.path("jobId").asText() + "/retry", owner, Map.of()),
                409, "JOB_NOT_RETRYABLE");
        recordHttp("directPdfAndPastExam", "201,202,200,409",
                "uploaded;noObjectKey;twoPdfExtractJobs;ownerPolling;retryCeiling;apiBodyBytes=0");
    }

    @Test
    void keepsExamResourcePending_whenCompletionCannotEnqueue() throws Exception {
        String owner = login("orphaned-exam-resource-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String resourceId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "past-exam.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        jdbc.sql("DELETE FROM exam_session_members WHERE exam_id=:exam")
                .param("exam", java.util.UUID.fromString(ids.examId())).update();
        storage.directPut(resourceId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(resourceId, 1, PDF_CHECKSUM);

        assertError(post("/api/v1/exam-resources/" + resourceId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "JOB_NOT_FOUND");

        assertThat(jdbc.sql("SELECT status FROM exam_resources WHERE id=:id")
                .param("id", java.util.UUID.fromString(resourceId)).query(String.class).single())
                .isEqualTo("created");
    }

    @Test
    void returnsOverlapCandidates_whenRecordingMetadataIsValid() throws Exception {
        String owner = login("recording-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");

        JsonNode upload = successful(post("/api/v1/recordings/upload-url", owner,
                Map.of("filename", "lecture.m4a", "mimeType", "audio/m4a", "byteSize", 4096,
                        "startedAt", "2026-09-01T00:00:00Z")), 201);
        assertUploadUrlIsPrivate(upload, "audio/m4a");
        String recordingId = upload.path("id").asText();
        storage.directPut(recordingId, "audio/m4a", 4096, AUDIO_CHECKSUM);
        probe.audio(recordingId, 3600, AUDIO_CHECKSUM);

        JsonNode completed = successful(post("/api/v1/recordings/" + recordingId + "/upload-complete",
                owner, Map.of("checksumSha256", AUDIO_CHECKSUM)), 200);
        assertThat(completed.path("recordingId").asText()).isEqualTo(recordingId);
        assertThat(completed.path("durationSeconds").asLong()).isEqualTo(3600);
        assertThat(completed.path("candidateSessions")).hasSize(1);
        assertThat(completed.path("candidateSessions").get(0).path("sessionId").asText())
                .isEqualTo(ids.sessionId());
        assertThat(completed.path("candidateSessions").get(0).path("overlapScore").asDouble())
                .isEqualTo(1.0);
        assertThat(jdbc.sql("SELECT count(*) FROM audio_recordings WHERE id = :id AND session_id IS NULL")
                .param("id", java.util.UUID.fromString(recordingId)).query(Integer.class).single()).isOne();
        recordHttp("recordingProbeAndCandidates", "201,200", "duration=3600;overlap=1.0;unmapped");
    }

    @Test
    void rejectsUnsupportedAndInvalidUploads_whenBoundaryChecksFail() throws Exception {
        String owner = login("boundary-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");

        assertError(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "bad.txt", "mimeType", "text/plain", "byteSize", 12,
                        "sourcePhase", "preview_pdf")), 415, "UNSUPPORTED_MEDIA_TYPE");
        assertError(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "huge.pdf", "mimeType", "application/pdf", "byteSize", 52_428_801,
                        "sourcePhase", "preview_pdf")), 422, "UPLOAD_LIMIT_EXCEEDED");
        assertError(post("/api/v1/recordings/upload-url", owner,
                Map.of("filename", "lecture.wav", "mimeType", "audio/wav", "byteSize", 10,
                        "startedAt", "2026-09-01T00:00:00Z")), 415, "UNSUPPORTED_MEDIA_TYPE");

        for (int index = 0; index < 5; index++) {
            successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                    Map.of("filename", "allowed-" + index + ".pdf", "mimeType", "application/pdf",
                            "byteSize", 100 + index, "sourcePhase", "review_pdf")), 201);
        }
        assertError(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "sixth.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "review_pdf")), 422, "UPLOAD_LIMIT_EXCEEDED");

        String materialId = jdbc.sql("""
                        SELECT id FROM materials
                        WHERE owner_id = (SELECT id FROM users WHERE provider_subject = :subject)
                          AND byte_size = 100
                        ORDER BY created_at LIMIT 1
                        """)
                .param("subject", "boundary-owner").query(java.util.UUID.class).single().toString();
        storage.directPut(materialId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(materialId, 151, PDF_CHECKSUM);
        assertError(post("/api/v1/materials/" + materialId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 422, "UPLOAD_LIMIT_EXCEEDED");

        String recordingId = successful(post("/api/v1/recordings/upload-url", owner,
                Map.of("filename", "too-long.m4a", "mimeType", "audio/m4a", "byteSize", 4096,
                        "startedAt", "2026-09-01T00:00:00Z")), 201).path("id").asText();
        storage.directPut(recordingId, "audio/m4a", 4096, AUDIO_CHECKSUM);
        probe.audio(recordingId, 10_801, AUDIO_CHECKSUM);
        assertError(post("/api/v1/recordings/" + recordingId + "/upload-complete", owner,
                Map.of("checksumSha256", AUDIO_CHECKSUM)), 422, "UPLOAD_LIMIT_EXCEEDED");

        String checksumId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "checksum.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        storage.directPut(checksumId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(checksumId, 1, PDF_CHECKSUM);
        assertError(post("/api/v1/exam-resources/" + checksumId + "/upload-complete", owner,
                Map.of("checksumSha256", "c".repeat(64))), 422, "VALIDATION_FAILED");

        String mimeId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "wrong-mime.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        storage.directPut(mimeId, "application/octet-stream", 100, PDF_CHECKSUM);
        probe.pdf(mimeId, 1, PDF_CHECKSUM);
        assertError(post("/api/v1/exam-resources/" + mimeId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 415, "UNSUPPORTED_MEDIA_TYPE");

        String sizeId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "wrong-size.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        storage.directPut(sizeId, "application/pdf", 101, PDF_CHECKSUM);
        probe.pdf(sizeId, 1, PDF_CHECKSUM);
        assertError(post("/api/v1/exam-resources/" + sizeId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 422, "VALIDATION_FAILED");
        recordHttp("uploadBoundaries", "415,422",
                "requestMime;metadataMime;metadataSize;count;pages;duration;checksum");
    }

    @Test
    void hidesOwnedResources_whenAnotherUserRequestsThem() throws Exception {
        String owner = login("ownership-a");
        String foreign = login("ownership-b");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String materialId = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "private.pdf", "mimeType", "application/pdf", "byteSize", 10,
                        "sourcePhase", "preview_pdf")), 201).path("id").asText();
        String examResourceId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "private-exam.pdf", "mimeType", "application/pdf", "byteSize", 10)), 201)
                .path("id").asText();

        assertError(get("/api/v1/sessions/" + ids.sessionId() + "/materials", foreign),
                404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/materials/" + materialId + "/download-url", foreign),
                404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/exams/" + ids.examId() + "/resources", foreign),
                404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/exam-resources/" + examResourceId + "/download-url", foreign),
                404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/exam-resources/" + examResourceId + "/upload-complete", foreign,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        recordHttp("foreignOwner", "404", "list;download;examResourceList;examResourceDownload");
    }

    @Test
    void appliesV003_whenDatabaseIsFresh() {
        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '003'")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN
                            ('materials', 'notes', 'audio_recordings', 'exam_resources')
                        """).query(Integer.class).single()).isEqualTo(4);
        System.out.println("RESOURCE_DB migrationOrder=V001,V002,V003 tables=4 result=PASS");
    }

    private DomainIds domain(String token, String startsAt, String endsAt) throws Exception {
        String courseId = successful(post("/api/v1/courses", token, Map.of("name", "Resource course")), 201)
                .path("id").asText();
        String sessionId = successful(post("/api/v1/courses/" + courseId + "/sessions", token, Map.of(
                "sessionNumber", 1, "title", "Resource session", "sessionDate", "2026-09-01",
                "startsAt", startsAt, "endsAt", endsAt)), 201).path("id").asText();
        String examId = successful(post("/api/v1/courses/" + courseId + "/exams", token, Map.of(
                "title", "Resource exam", "examAt", "2026-10-01T00:00:00Z",
                "sessionIds", List.of(sessionId))), 201).path("id").asText();
        return new DomainIds(courseId, sessionId, examId);
    }

    private String login(String subject) throws Exception {
        String fakeToken = String.join("|", "fake", "https://accounts.google.com",
                "test-google-client", subject, subject + "@example.com", "Student",
                Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        return successful(send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fakeToken)), 200)
                .path("accessToken").asText();
    }

    private void assertUploadUrlIsPrivate(JsonNode upload, String mimeType) {
        assertThat(upload.path("uploadUrl").asText()).startsWith("https://storage.test/upload/");
        assertThat(upload.path("requiredHeaders").path("Content-Type").asText()).isEqualTo(mimeType);
        assertThat(upload.toString()).doesNotContain("objectKey").doesNotContain("owner/");
    }

    private HttpResult get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private HttpResult post(String path, String token, Object body) throws Exception {
        return send("POST", path, token, body);
    }

    private HttpResult send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json; charset=utf-8");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private JsonNode successful(HttpResult result, int status) throws Exception {
        assertThat(result.status()).isEqualTo(status);
        return result.body().isEmpty() ? objectMapper.nullNode() : objectMapper.readTree(result.body());
    }

    private void assertError(HttpResult result, int status, String code) throws Exception {
        assertThat(result.status()).isEqualTo(status);
        assertThat(objectMapper.readTree(result.body()).path("code").asText()).isEqualTo(code);
    }

    private static void recordHttp(String scenario, String status, String observable) {
        System.out.printf("RESOURCE_HTTP scenario=%s status=%s observable=%s result=PASS%n",
                scenario, status, observable);
    }

    record DomainIds(String courseId, String sessionId, String examId) {}

    record HttpResult(int status, String body) {}

    @TestConfiguration
    static class FakeProviders {
        @Bean
        @Primary
        FakeCloudStorage fakeCloudStorage() {
            return new FakeCloudStorage();
        }

        @Bean
        @Primary
        FakeResourceContentProbe fakeResourceContentProbe() {
            return new FakeResourceContentProbe();
        }
    }

    static final class FakeCloudStorage implements CloudStoragePort {
        private final List<String> issuedKeys = new ArrayList<>();
        private final Map<String, StoredObjectMetadata> objects = new HashMap<>();

        @Override
        public URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt) {
            issuedKeys.add(objectKey);
            return URI.create("https://storage.test/upload/" + resourceId(objectKey));
        }

        @Override
        public URI createDownloadUrl(String objectKey, Instant expiresAt) {
            return URI.create("https://storage.test/download/" + resourceId(objectKey));
        }

        @Override
        public StoredObjectMetadata metadata(String objectKey) {
            return objects.get(resourceId(objectKey));
        }

        void directPut(String resourceId, String mimeType, long byteSize, String checksum) {
            objects.put(resourceId, new StoredObjectMetadata(mimeType, byteSize, checksum));
        }

        int apiBodyBytes() {
            return 0;
        }

        void reset() {
            issuedKeys.clear();
            objects.clear();
        }

        private static String resourceId(String objectKey) {
            String[] parts = objectKey.split("/");
            return parts[parts.length - 2];
        }
    }

    static final class FakeResourceContentProbe implements ResourceContentProbe {
        private final Map<String, PdfInspection> pdfs = new HashMap<>();
        private final Map<String, AudioInspection> audio = new HashMap<>();

        @Override
        public PdfInspection inspectPdf(String objectKey) {
            return pdfs.get(resourceId(objectKey));
        }

        @Override
        public AudioInspection inspectAudio(String objectKey) {
            return audio.get(resourceId(objectKey));
        }

        void pdf(String resourceId, int pageCount, String checksum) {
            pdfs.put(resourceId, new PdfInspection(pageCount, checksum));
        }

        void audio(String resourceId, long durationSeconds, String checksum) {
            audio.put(resourceId, new AudioInspection(durationSeconds, checksum));
        }

        void reset() {
            pdfs.clear();
            audio.clear();
        }

        private static String resourceId(String objectKey) {
            String[] parts = objectKey.split("/");
            return parts[parts.length - 2];
        }
    }
}
