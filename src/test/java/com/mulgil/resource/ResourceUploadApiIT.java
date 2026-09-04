package com.mulgil.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ContentIndexingService;
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
import java.util.UUID;

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
    MaterialUploadCleanupScheduler scheduler;

    @Autowired
    ResourceObjectDeletionScheduler deletionScheduler;

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
        assertThat(polled.path("materialId").asText()).isEqualTo(materialId);
        JsonNode sessionJobs = successful(get("/api/v1/sessions/" + ids.sessionId() + "/jobs", owner), 200);
        assertThat(sessionJobs).hasSize(1);
        assertThat(sessionJobs.get(0).path("materialId").asText()).isEqualTo(materialId);
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
    void cleanupScheduler_cancelsOnlyExpiredReservations_andReleasesUploadCapacity() throws Exception {
        String owner = login("expired-reservation-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");

        List<String> materialIds = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            materialIds.add(successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                    Map.of("filename", "abandoned-" + index + ".pdf", "mimeType", "application/pdf",
                            "byteSize", 100 + index, "sourcePhase", "preview_pdf")), 201).path("id").asText());
        }
        jdbc.sql("""
                        UPDATE materials SET upload_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id AND status = 'created'
                        """).param("id", java.util.UUID.fromString(materialIds.getFirst())).update();

        scheduler.cleanupExpired();

        assertThat(jdbc.sql("SELECT status FROM materials WHERE id=:id")
                .param("id", java.util.UUID.fromString(materialIds.getFirst()))
                .query(String.class).single()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT count(*) FROM materials WHERE session_id=:session AND status='created'")
                .param("session", java.util.UUID.fromString(ids.sessionId()))
                .query(Integer.class).single()).isEqualTo(4);

        JsonNode replacement = successful(post(
                "/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "replacement.pdf", "mimeType", "application/pdf",
                        "byteSize", 200, "sourcePhase", "review_pdf")), 201);

        assertThat(replacement.path("expiresAt").asText()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM materials WHERE session_id=:session AND status='cancelled'")
                .param("session", java.util.UUID.fromString(ids.sessionId()))
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM materials WHERE session_id=:session AND status='created'")
                .param("session", java.util.UUID.fromString(ids.sessionId()))
                .query(Integer.class).single()).isEqualTo(5);
        recordHttp("expiredMaterialReservations", "201", "scheduler-cancelled-expired-only;replacement-created");
    }

    @Test
    void issuesUploadExpiry_withGcsSecondPrecision() throws Exception {
        String owner = login("upload-expiry-precision-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");

        JsonNode upload = successful(post(
                "/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "precision.pdf", "mimeType", "application/pdf",
                        "byteSize", 100, "sourcePhase", "preview_pdf")), 201);
        Instant expiresAt = Instant.parse(upload.path("expiresAt").asText());

        assertThat(expiresAt.getNano()).isZero();
        assertThat(storage.lastUploadExpiry).isEqualTo(expiresAt);
    }

    @Test
    void rejectsMaterialCompletion_whenUploadReservationExpired() throws Exception {
        String owner = login("expired-completion-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String materialId = successful(post(
                "/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "expired.pdf", "mimeType", "application/pdf",
                        "byteSize", 100, "sourcePhase", "preview_pdf")), 201).path("id").asText();
        jdbc.sql("UPDATE materials SET upload_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id")
                .param("id", java.util.UUID.fromString(materialId)).update();
        storage.directPut(materialId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(materialId, 1, PDF_CHECKSUM);

        assertError(post("/api/v1/materials/" + materialId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 410, "UPLOAD_URL_EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM materials WHERE id=:id")
                .param("id", java.util.UUID.fromString(materialId))
                .query(String.class).single()).isIn("created", "cancelled");
        recordHttp("expiredMaterialCompletion", "410", "expired-upload-rejected");
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
    void hidesArchivedCourseResources_butRetainsTheirRows() throws Exception {
        String owner = login("archived-resource-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String materialId = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "archived.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "preview_pdf")), 201).path("id").asText();
        storage.directPut(materialId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(materialId, 1, PDF_CHECKSUM);
        successful(post("/api/v1/materials/" + materialId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 202);

        String examResourceId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "archived-exam.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        storage.directPut(examResourceId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(examResourceId, 1, PDF_CHECKSUM);
        successful(post("/api/v1/exam-resources/" + examResourceId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 200);

        successful(put("/api/v1/materials/" + materialId + "/annotations", owner,
                Map.of("expectedVersion", 0, "inkStrokes", List.of(), "emphasisRegions", List.of())), 200);

        String recordingId = successful(post("/api/v1/recordings/upload-url", owner,
                Map.of("filename", "archived.m4a", "mimeType", "audio/m4a", "byteSize", 100,
                        "startedAt", "2026-09-01T00:00:00Z")), 201).path("id").asText();
        storage.directPut(recordingId, "audio/m4a", 100, AUDIO_CHECKSUM);
        probe.audio(recordingId, 60, AUDIO_CHECKSUM);
        successful(post("/api/v1/recordings/" + recordingId + "/upload-complete", owner,
                Map.of("checksumSha256", AUDIO_CHECKSUM)), 200);
        successful(post("/api/v1/recordings/" + recordingId + "/confirm-mapping", owner,
                Map.of("sessionId", ids.sessionId())), 202);

        assertThat(delete("/api/v1/courses/" + ids.courseId(), owner).status()).isEqualTo(204);

        assertError(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url", owner,
                Map.of("filename", "blocked.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "preview_pdf")), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/sessions/" + ids.sessionId() + "/materials", owner), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/materials/" + materialId + "/download-url", owner), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/materials/" + materialId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/materials/" + materialId + "/annotations", owner), 404, "ANNOTATION_NOT_FOUND");
        assertError(put("/api/v1/materials/" + materialId + "/annotations", owner,
                Map.of("expectedVersion", 1, "inkStrokes", List.of(), "emphasisRegions", List.of())),
                404, "ANNOTATION_NOT_FOUND");
        assertError(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "blocked-exam.pdf", "mimeType", "application/pdf", "byteSize", 100)),
                404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/exams/" + ids.examId() + "/resources", owner), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/exam-resources/" + examResourceId + "/download-url", owner), 404,
                "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/exam-resources/" + examResourceId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/recordings/" + recordingId + "/upload-complete", owner,
                Map.of("checksumSha256", AUDIO_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/recordings/" + recordingId + "/confirm-mapping", owner,
                Map.of("sessionId", ids.sessionId())), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM materials WHERE id=:id")
                .param("id", java.util.UUID.fromString(materialId)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM exam_resources WHERE id=:id")
                .param("id", java.util.UUID.fromString(examResourceId)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM annotation_documents WHERE material_id=:id")
                .param("id", java.util.UUID.fromString(materialId)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT status FROM audio_recordings WHERE id=:id")
                .param("id", java.util.UUID.fromString(recordingId)).query(String.class).single()).isEqualTo("queued");
        recordHttp("archivedCourseResources", "204,404", "material-exam-annotation-recording-hidden;rows-retained");
    }

    @Test
    void deletesMaterialWithItsDerivedDataAndSourceGroundedArtifacts() throws Exception {
        String owner = login("delete-material-owner");
        String foreign = login("delete-material-foreign");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String materialId = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "delete.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "preview_pdf")), 201).path("id").asText();
        UUID material = UUID.fromString(materialId);
        storage.directPut(materialId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(materialId, 1, PDF_CHECKSUM);
        UUID directJob = UUID.fromString(successful(post("/api/v1/materials/" + materialId + "/upload-complete",
                owner, Map.of("checksumSha256", PDF_CHECKSUM)), 202).path("jobId").asText());
        successful(put("/api/v1/materials/" + materialId + "/annotations", owner,
                Map.of("expectedVersion", 0, "inkStrokes", List.of(), "emphasisRegions", List.of())), 200);

        UUID ownerId = jdbc.sql("SELECT owner_id FROM materials WHERE id=:id")
                .param("id", material).query(UUID.class).single();
        UUID courseId = UUID.fromString(ids.courseId());
        UUID sessionId = UUID.fromString(ids.sessionId());
        UUID pageId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String text = "indexed material";
        String chunkHash = ContentIndexingService.sha256(blockId + ":" + text);
        String sourceRef = objectMapper.writeValueAsString(Map.of(
                "sourceType", "pdf_text", "materialId", materialId,
                "contentBlockId", blockId.toString(), "pageNumber", 1, "inputVersion", 1));
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id,owner_id,course_id,session_id,material_id,page_number,text_content,text_hash,
                             extraction_method,created_at)
                        VALUES (:id,:owner,:course,:session,:material,1,:text,:hash,'pdf_text',CURRENT_TIMESTAMP)
                        """).param("id", pageId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", material).param("text", text)
                .param("hash", ContentIndexingService.sha256(text)).update();
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id,owner_id,course_id,session_id,material_id,page_id,block_type,text_content,
                             source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:material,:page,'text',:text,:hash,CURRENT_TIMESTAMP)
                        """).param("id", blockId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("material", material).param("page", pageId).param("text", text)
                .param("hash", chunkHash).update();
        jdbc.sql("""
                        INSERT INTO chunks
                            (id,owner_id,course_id,session_id,content_block_id,chunk_index,text_content,source_ref,
                             source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:block,0,:text,CAST(:reference AS jsonb),:hash,
                                CURRENT_TIMESTAMP)
                        """).param("id", chunkId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("block", blockId).param("text", text)
                .param("reference", sourceRef).param("hash", chunkHash).update();

        UUID chunkJob = UUID.randomUUID();
        UUID generationJob = UUID.randomUUID();
        String generationHash = ContentIndexingService.sha256(
                "pdf_text\u001f" + materialId + "\u001f1\u001f" + chunkHash);
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,'chunk_embed','queued',1,:key,0,3,:hash,CURRENT_TIMESTAMP)
                        """).param("id", chunkJob).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "f".repeat(64)).param("hash", chunkHash).update();
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,'preview_generate','queued',1,:key,0,3,:hash,
                                CURRENT_TIMESTAMP)
                        """).param("id", generationJob).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "g".repeat(64)).param("hash", generationHash).update();
        String refs = "[{\"materialId\":\"" + materialId + "\"}]";
        String unrelatedRefs = "[{\"materialId\":\"" + UUID.randomUUID() + "\"}]";
        UUID matchingSummary = UUID.randomUUID();
        UUID unrelatedSummary = UUID.randomUUID();
        UUID mindmap = UUID.randomUUID();
        UUID question = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO summaries
                            (id,owner_id,course_id,session_id,summary_type,input_version,content_json,source_refs,
                             status,model_id,prompt_version,created_at,updated_at)
                        VALUES (:id,:owner,:course,:session,:type,1,CAST('{"items":[]}' AS jsonb),
                                CAST(:refs AS jsonb),'succeeded','test','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """).param("id", matchingSummary).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("type", "preview").param("refs", refs).update();
        jdbc.sql("""
                        INSERT INTO summaries
                            (id,owner_id,course_id,session_id,summary_type,input_version,content_json,source_refs,
                             status,model_id,prompt_version,created_at,updated_at)
                        VALUES (:id,:owner,:course,:session,'review',1,CAST('{"items":[]}' AS jsonb),
                                CAST(:refs AS jsonb),'succeeded','test','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """).param("id", unrelatedSummary).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("refs", unrelatedRefs).update();
        jdbc.sql("""
                        INSERT INTO mindmaps
                            (id,owner_id,course_id,session_id,input_version,nodes_json,edges_json,source_refs,status,
                             model_id,prompt_version,created_at,updated_at)
                        VALUES (:id,:owner,:course,:session,1,'[]','[]',CAST(:refs AS jsonb),'succeeded','test','test',
                                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """).param("id", mindmap).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("refs", refs).update();
        String questionJson = "{\"text\":\"Question\",\"sourceRefs\":" + refs + "}";
        String answerJson = "{\"value\":true,\"sourceRefs\":" + refs + "}";
        String explanationJson = "{\"text\":\"Explanation\",\"sourceRefs\":" + refs + "}";
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,session_id,quiz_scope,question_type,input_version,question_json,
                             answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:session,'practice','true_false',1,CAST(:question AS jsonb),
                                CAST(:answer AS jsonb),CAST(:explanation AS jsonb),'succeeded','test','test',
                                CURRENT_TIMESTAMP)
                        """).param("id", question).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("question", questionJson).param("answer", answerJson)
                .param("explanation", explanationJson).update();
        UUID usage = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ai_provider_usage
                            (id,job_id,owner_id,operation,provider,model_id,status,unit_type,started_at)
                        VALUES (:id,:job,:owner,'test','test','test','started','unit',CURRENT_TIMESTAMP)
                        """).param("id", usage).param("job", directJob).param("owner", ownerId).update();

        assertError(delete("/api/v1/materials/" + materialId, foreign), 404, "RESOURCE_NOT_FOUND");
        successful(delete("/api/v1/materials/" + materialId, owner), 204);

        assertThat(successful(get("/api/v1/sessions/" + ids.sessionId() + "/materials", owner), 200)).isEmpty();
        assertError(get("/api/v1/materials/" + materialId + "/download-url", owner), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/materials/" + materialId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/jobs/" + directJob, owner), 404, "JOB_NOT_FOUND");
        assertError(delete("/api/v1/materials/" + materialId, owner), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM annotation_documents WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document_pages WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE id=:id")
                .param("id", chunkId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id")
                .param("id", chunkJob).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id")
                .param("id", generationJob).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM summaries WHERE id=:id")
                .param("id", matchingSummary).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM mindmaps WHERE id=:id")
                .param("id", mindmap).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM quiz_questions WHERE id=:id")
                .param("id", question).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM summaries WHERE id=:id")
                .param("id", unrelatedSummary).query(String.class).single()).isEqualTo("succeeded");
        assertThat(jdbc.sql("SELECT job_id IS NULL FROM ai_provider_usage WHERE id=:id")
                .param("id", usage).query(Boolean.class).single()).isTrue();

        String materialKey = deletionKey(materialId);
        deletionScheduler.cleanupDue();
        assertThat(storage.hasObject(materialId)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM resource_object_deletions WHERE object_key=:key")
                .param("key", materialKey).query(Integer.class).single()).isZero();
        recordHttp("deleteMaterial", "204,404", "cascade;generated-artifacts-outdated;gcs-cleanup;usage-preserved");
    }

    @Test
    void deletesExamResourceAndHidesItImmediately() throws Exception {
        String owner = login("delete-exam-resource-owner");
        String foreign = login("delete-exam-resource-foreign");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String resourceId = successful(post("/api/v1/exams/" + ids.examId() + "/resources", owner,
                Map.of("filename", "past-exam.pdf", "mimeType", "application/pdf", "byteSize", 100)), 201)
                .path("id").asText();
        UUID resource = UUID.fromString(resourceId);
        storage.directPut(resourceId, "application/pdf", 100, PDF_CHECKSUM);
        probe.pdf(resourceId, 1, PDF_CHECKSUM);
        successful(post("/api/v1/exam-resources/" + resourceId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 200);
        UUID directJob = jdbc.sql("SELECT id FROM ai_jobs WHERE exam_resource_id=:id")
                .param("id", resource).query(UUID.class).single();
        UUID ownerId = jdbc.sql("SELECT owner_id FROM exam_resources WHERE id=:id")
                .param("id", resource).query(UUID.class).single();
        UUID courseId = UUID.fromString(ids.courseId());
        UUID sessionId = UUID.fromString(ids.sessionId());
        UUID examId = UUID.fromString(ids.examId());
        UUID pageId = UUID.randomUUID();
        UUID blockId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        String text = "past exam";
        String chunkHash = ContentIndexingService.sha256(blockId + ":" + text);
        String sourceRef = objectMapper.writeValueAsString(Map.of(
                "sourceType", "past_exam", "examResourceId", resourceId,
                "contentBlockId", blockId.toString(), "pageNumber", 1, "inputVersion", 1));
        jdbc.sql("""
                        INSERT INTO document_pages
                            (id,owner_id,course_id,session_id,exam_resource_id,page_number,text_content,text_hash,
                             extraction_method,created_at)
                        VALUES (:id,:owner,:course,:session,:resource,1,:text,:hash,'pdf_text',CURRENT_TIMESTAMP)
                        """).param("id", pageId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("resource", resource).param("text", text)
                .param("hash", ContentIndexingService.sha256(text)).update();
        jdbc.sql("""
                        INSERT INTO content_blocks
                            (id,owner_id,course_id,session_id,exam_resource_id,page_id,block_type,text_content,
                             source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:resource,:page,'text',:text,:hash,CURRENT_TIMESTAMP)
                        """).param("id", blockId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("resource", resource).param("page", pageId).param("text", text)
                .param("hash", chunkHash).update();
        jdbc.sql("""
                        INSERT INTO chunks
                            (id,owner_id,course_id,session_id,content_block_id,chunk_index,text_content,source_ref,
                             source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:block,0,:text,CAST(:reference AS jsonb),:hash,
                                CURRENT_TIMESTAMP)
                        """).param("id", chunkId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("block", blockId).param("text", text)
                .param("reference", sourceRef).param("hash", chunkHash).update();
        UUID chunkJob = UUID.randomUUID();
        UUID generationJob = UUID.randomUUID();
        String generationHash = ContentIndexingService.sha256(
                "past_exam\u001f" + resourceId + "\u001f1\u001f" + chunkHash);
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,'chunk_embed','queued',1,:key,0,3,:hash,CURRENT_TIMESTAMP)
                        """).param("id", chunkJob).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "h".repeat(64)).param("hash", chunkHash).update();
        jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,exam_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:exam,'exam_quiz_generate','queued',1,:key,0,3,:hash,
                                CURRENT_TIMESTAMP)
                        """).param("id", generationJob).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("exam", examId).param("key", "i".repeat(64))
                .param("hash", generationHash).update();
        String refs = "[{\"examResourceId\":\"" + resourceId + "\"}]";
        UUID question = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,exam_id,quiz_scope,question_type,input_version,question_json,
                             answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:exam,'past_exam_based','true_false',1,
                                CAST(:question AS jsonb),CAST(:answer AS jsonb),CAST(:explanation AS jsonb),
                                'succeeded','test','test',CURRENT_TIMESTAMP)
                        """).param("id", question).param("owner", ownerId).param("course", courseId)
                .param("exam", examId).param("question", "{\"text\":\"Question\",\"sourceRefs\":" + refs + "}")
                .param("answer", "{\"value\":true,\"sourceRefs\":" + refs + "}")
                .param("explanation", "{\"text\":\"Explanation\",\"sourceRefs\":" + refs + "}").update();

        assertError(delete("/api/v1/exam-resources/" + resourceId, foreign), 404, "RESOURCE_NOT_FOUND");
        successful(delete("/api/v1/exam-resources/" + resourceId, owner), 204);

        assertThat(successful(get("/api/v1/exams/" + ids.examId() + "/resources", owner), 200)).isEmpty();
        assertError(get("/api/v1/exam-resources/" + resourceId + "/download-url", owner), 404,
                "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/exam-resources/" + resourceId + "/upload-complete", owner,
                Map.of("checksumSha256", PDF_CHECKSUM)), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/jobs/" + directJob, owner), 404, "JOB_NOT_FOUND");
        assertError(delete("/api/v1/exam-resources/" + resourceId, owner), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM exam_resources WHERE id=:id")
                .param("id", resource).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document_pages WHERE exam_resource_id=:id")
                .param("id", resource).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE id=:id")
                .param("id", chunkId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id")
                .param("id", chunkJob).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id")
                .param("id", generationJob).query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT status FROM quiz_questions WHERE id=:id")
                .param("id", question).query(String.class).single()).isEqualTo("outdated");
        deletionScheduler.cleanupDue();
        assertThat(storage.hasObject(resourceId)).isFalse();
        recordHttp("deleteExamResource", "204,404", "list;download;job;gcs-cleanup");
    }

    @Test
    void defersCreatedObjectDeletionAndRecordsTerminalStorageFailures() throws Exception {
        String owner = login("delete-outbox-owner");
        DomainIds ids = domain(owner, "2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z");
        String deferredId = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "pending.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "preview_pdf")), 201).path("id").asText();
        storage.directPut(deferredId, "application/pdf", 100, PDF_CHECKSUM);
        successful(delete("/api/v1/materials/" + deferredId, owner), 204);

        deletionScheduler.cleanupDue();
        assertThat(storage.hasObject(deferredId)).isTrue();
        String deferredKey = deletionKey(deferredId);
        jdbc.sql("UPDATE resource_object_deletions SET not_before=created_at WHERE object_key=:key")
                .param("key", deferredKey).update();
        deletionScheduler.cleanupDue();
        assertThat(storage.hasObject(deferredId)).isFalse();

        String failedId = successful(post("/api/v1/sessions/" + ids.sessionId() + "/materials/upload-url",
                owner, Map.of("filename", "retry.pdf", "mimeType", "application/pdf", "byteSize", 100,
                        "sourcePhase", "preview_pdf")), 201).path("id").asText();
        storage.directPut(failedId, "application/pdf", 100, PDF_CHECKSUM);
        successful(delete("/api/v1/materials/" + failedId, owner), 204);
        String failedKey = deletionKey(failedId);
        jdbc.sql("UPDATE resource_object_deletions SET not_before=created_at WHERE object_key=:key")
                .param("key", failedKey).update();
        storage.failDeletes(3);
        for (int attempt = 1; attempt <= 3; attempt++) {
            deletionScheduler.cleanupDue();
            if (attempt < 3) {
                jdbc.sql("UPDATE resource_object_deletions SET not_before=created_at WHERE object_key=:key")
                        .param("key", failedKey).update();
            }
        }
        assertThat(storage.hasObject(failedId)).isTrue();
        assertThat(jdbc.sql("SELECT status FROM resource_object_deletions WHERE object_key=:key")
                .param("key", failedKey).query(String.class).single()).isEqualTo("failed");
        assertThat(jdbc.sql("SELECT attempt_count FROM resource_object_deletions WHERE object_key=:key")
                .param("key", failedKey).query(Integer.class).single()).isEqualTo(3);
        recordHttp("deleteOutbox", "204", "signed-url-delay;gcs-retry;terminal-failure-retained");
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

    private String deletionKey(String resourceId) {
        return jdbc.sql("""
                        SELECT object_key FROM resource_object_deletions
                        WHERE object_key LIKE :suffix
                        """).param("suffix", "%/" + resourceId + "/source.pdf")
                .query(String.class).single();
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

    private HttpResult put(String path, String token, Object body) throws Exception {
        return send("PUT", path, token, body);
    }

    private HttpResult delete(String path, String token) throws Exception {
        return send("DELETE", path, token, null);
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
        private Instant lastUploadExpiry;
        private int deleteFailures;

        @Override
        public URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt) {
            issuedKeys.add(objectKey);
            lastUploadExpiry = expiresAt;
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

        @Override
        public void delete(String objectKey) {
            if (deleteFailures > 0) {
                deleteFailures--;
                throw new IllegalStateException("planned deletion failure");
            }
            objects.remove(resourceId(objectKey));
        }

        void directPut(String resourceId, String mimeType, long byteSize, String checksum) {
            objects.put(resourceId, new StoredObjectMetadata(mimeType, byteSize, checksum));
        }

        int apiBodyBytes() {
            return 0;
        }

        boolean hasObject(String resourceId) {
            return objects.containsKey(resourceId);
        }

        void failDeletes(int count) {
            deleteFailures = count;
        }

        void reset() {
            issuedKeys.clear();
            objects.clear();
            lastUploadExpiry = null;
            deleteFailures = 0;
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
