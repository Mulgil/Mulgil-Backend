package com.mulgil.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.storage.CloudStoragePort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@Import(AnnotationWorkflowIT.Fakes.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnnotationWorkflowIT {
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
    @Autowired FakeVision vision;
    @Autowired FakeStorage storage;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM users").update();
        vision.result("unconfirmed writing", 0.79);
    }

    @Test
    void noteLeaveIndexesCurrentVersion_onceAndRejectsStaleOrForeignWrites() throws Exception {
        String owner = login("note-owner");
        String session = session(owner);
        JsonNode note = ok(send("POST", "/api/v1/sessions/" + session + "/notes", owner,
                Map.of("bodyMarkdown", "first paragraph\n\nsecond paragraph")), 201);
        String noteId = note.path("id").asText();

        JsonNode patched = ok(send("PATCH", "/api/v1/notes/" + noteId, owner,
                Map.of("bodyMarkdown", "updated", "expectedVersion", 1)), 200);
        assertThat(patched.path("version").asInt()).isEqualTo(2);
        error(send("PATCH", "/api/v1/notes/" + noteId, owner,
                Map.of("bodyMarkdown", "stale", "expectedVersion", 1)), 409, "VERSION_CONFLICT");
        error(send("PATCH", "/api/v1/notes/" + noteId, login("note-foreign"),
                Map.of("bodyMarkdown", "foreign", "expectedVersion", 2)), 404, "NOTE_NOT_FOUND");

        JsonNode accepted = ok(send("POST", "/api/v1/notes/" + noteId + "/leave", owner,
                Map.of("changedVersion", 2)), 202);
        assertThat(accepted.path("status").asText()).isEqualTo("queued");
        ok(send("POST", "/api/v1/notes/" + noteId + "/leave", owner,
                Map.of("changedVersion", 2)), 204);
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE note_id=:id")
                .param("id", UUID.fromString(noteId)).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT source_ref->>'sourceType' FROM chunks WHERE content_block_id IN "
                        + "(SELECT id FROM content_blocks WHERE note_id=:id)")
                .param("id", UUID.fromString(noteId)).query(String.class).single()).isEqualTo("note");
        System.out.println("ANNOTATION_WORKFLOW scenario=note-version-leave observable=409,404,202,204,one-note-chunk result=PASS");
    }

    @Test
    void annotationLeaveUsesPenUnion_andLowConfidenceWaitsForConfirmation() throws Exception {
        String owner = login("annotation-owner");
        String session = session(owner);
        UUID material = material(owner, session);
        Map<String, Object> boxA = box(0.10, 0.20, 0.20, 0.20);
        Map<String, Object> boxB = box(0.50, 0.10, 0.20, 0.50);
        Map<String, Object> highlightBox = box(0.80, 0.80, 0.10, 0.10);
        UUID penA = UUID.randomUUID();
        UUID penB = UUID.randomUUID();

        JsonNode document = ok(send("PUT", "/api/v1/materials/" + material + "/annotations", owner,
                Map.of("expectedVersion", 0, "inkStrokes", List.of(
                                stroke(penA, "pen", boxA), stroke(penB, "pen", boxB),
                                stroke(UUID.randomUUID(), "highlight", highlightBox)),
                        "emphasisRegions", List.of(Map.of("id", UUID.randomUUID(), "pageNumber", 1,
                                "bboxNorm", box(0.3, 0.3, 0.1, 0.1), "tapCount", 4)))), 200);
        assertThat(document.path("version").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT tool FROM ink_strokes WHERE annotation_document_id=:id ORDER BY tool,id")
                .param("id", UUID.fromString(document.path("id").asText())).query(String.class).list())
                .containsExactly("highlight", "pen", "pen");
        assertThat(jdbc.sql("SELECT tap_count FROM emphasis_regions WHERE annotation_document_id=:id")
                .param("id", UUID.fromString(document.path("id").asText())).query(Integer.class).single())
                .isEqualTo(4);
        error(send("PUT", "/api/v1/materials/" + material + "/annotations", login("annotation-foreign"),
                Map.of("expectedVersion", 1, "inkStrokes", List.of(), "emphasisRegions", List.of())),
                404, "ANNOTATION_NOT_FOUND");
        error(send("PUT", "/api/v1/materials/" + material + "/annotations", owner,
                Map.of("expectedVersion", 0, "inkStrokes", List.of(), "emphasisRegions", List.of())),
                409, "VERSION_CONFLICT");
        error(send("PUT", "/api/v1/materials/" + material + "/annotations", owner,
                Map.of("expectedVersion", 1, "inkStrokes", List.of(stroke(UUID.randomUUID(), "pen",
                        box(0.9, 0.9, 0.2, 0.2))), "emphasisRegions", List.of())),
                422, "VALIDATION_FAILED");

        ok(send("POST", "/api/v1/materials/" + material + "/annotations/leave", owner,
                Map.of("changedVersion", 1)), 202);
        ok(send("POST", "/api/v1/materials/" + material + "/annotations/leave", owner,
                Map.of("changedVersion", 1)), 204);
        runAll("handwriting_ocr");

        UUID handwriting = jdbc.sql("SELECT id FROM handwriting_blocks WHERE annotation_document_id=:id")
                .param("id", UUID.fromString(document.path("id").asText())).query(UUID.class).single();
        assertThat(vision.width()).isEqualTo(126);
        assertThat(vision.height()).isEqualTo(105);
        assertThat(jdbc.sql("SELECT status FROM handwriting_blocks WHERE id=:id").param("id", handwriting)
                .query(String.class).single()).isEqualTo("needs_user_review");
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE handwriting_block_id=:id")
                .param("id", handwriting).query(Integer.class).single()).isZero();

        ok(send("PATCH", "/api/v1/handwriting-blocks/" + handwriting + "/confirm", owner,
                Map.of("confirmedText", "confirmed writing")), 202);
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE content_block_id IN "
                        + "(SELECT id FROM content_blocks WHERE handwriting_block_id=:id)")
                .param("id", handwriting).query(Integer.class).single()).isOne();

        vision.result("auto accepted", 0.80);
        ok(send("PUT", "/api/v1/materials/" + material + "/annotations", owner,
                Map.of("expectedVersion", 1, "inkStrokes", List.of(stroke(UUID.randomUUID(), "pen", boxA)),
                        "emphasisRegions", List.of())), 200);
        ok(send("POST", "/api/v1/materials/" + material + "/annotations/leave", owner,
                Map.of("changedVersion", 2)), 202);
        runAll("handwriting_ocr");
        assertThat(jdbc.sql("SELECT status FROM handwriting_blocks WHERE annotation_document_id=:id "
                        + "AND input_version=2").param("id", UUID.fromString(document.path("id").asText()))
                .query(String.class).single()).isEqualTo("confirmed");
        System.out.println("ANNOTATION_WORKFLOW scenario=pen-union-low-confidence-confirm observable=penCropPng126x105,normalized,owner404,202,204,review,no-chunk,confirm-one-chunk,threshold-auto-confirm result=PASS");
    }

    private Map<String, Object> stroke(UUID id, String tool, Map<String, Object> bbox) {
        return Map.of("id", id, "pageNumber", 1, "tool", tool, "color", "#000000", "widthNorm", 0.01,
                "points", List.of(Map.of("x", bbox.get("x"), "y", bbox.get("y"))), "bboxNorm", bbox);
    }

    private static Map<String, Object> box(double x, double y, double width, double height) {
        return Map.of("x", x, "y", y, "width", width, "height", height);
    }

    private UUID material(String token, String session) {
        UUID owner = jdbc.sql("SELECT id FROM users WHERE provider_subject='annotation-owner'")
                .query(UUID.class).single();
        UUID course = jdbc.sql("SELECT course_id FROM class_sessions WHERE id=:id")
                .param("id", UUID.fromString(session)).query(UUID.class).single();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO materials
                    (id,owner_id,course_id,session_id,source_phase,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,'review_pdf',:key,'source.pdf','application/pdf',
                        10,1,:hash,1,'uploaded',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course)
                .param("session", UUID.fromString(session)).param("key", "material/" + id)
                .param("hash", "a".repeat(64)).param("now", now).update();
        storage.put("material/" + id, pdf());
        return id;
    }

    private static byte[] pdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(100, 100)));
            document.save(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String session(String token) throws Exception {
        String course = ok(send("POST", "/api/v1/courses", token, Map.of("name", "Workflow course")), 201)
                .path("id").asText();
        return ok(send("POST", "/api/v1/courses/" + course + "/sessions", token, Map.of(
                "sessionNumber", 1, "title", "Workflow session", "sessionDate", "2026-09-01")), 201)
                .path("id").asText();
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        return ok(send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fake)), 200)
                .path("accessToken").asText();
    }

    private void runAll(String type) throws Exception {
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
        JobQueue.ClaimedJob job;
        while ((job = jobs.claim("annotation-it", Set.of(type))) != null) {
            assertThat(jobs.complete(job, handler.handle(job))).isTrue();
        }
    }

    private HttpResult send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private JsonNode ok(HttpResult result, int status) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        return result.body().isEmpty() ? json.nullNode() : json.readTree(result.body());
    }

    private void error(HttpResult result, int status, String code) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        assertThat(json.readTree(result.body()).path("code").asText()).isEqualTo(code);
    }

    record HttpResult(int status, String body) {}

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeVision fakeVision() {
            return new FakeVision();
        }

        @Bean @Primary FakeStorage fakeStorage() {
            return new FakeStorage();
        }
    }

    static final class FakeVision implements VisionOcrPort {
        private OcrResult result;
        private int width;
        private int height;

        void result(String text, double confidence) {
            result = new OcrResult(List.of(new OcrBlock(text, confidence,
                    new NormalizedBox(0, 0, 1, 1))), "fake-vision", "fake-handwriting");
            width = 0;
            height = 0;
        }

        int width() { return width; }
        int height() { return height; }

        @Override
        public OcrResult extract(byte[] image) {
            try {
                BufferedImage crop = ImageIO.read(new ByteArrayInputStream(image));
                width = crop.getWidth();
                height = crop.getHeight();
                return result;
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }
    }

    static final class FakeStorage implements CloudStoragePort {
        private final java.util.Map<String, byte[]> objects = new java.util.HashMap<>();

        void put(String key, byte[] value) { objects.put(key, value); }
        @Override public URI createUploadUrl(String key, String type, long length, Instant expiry) {
            return URI.create("https://storage.test/upload");
        }
        @Override public URI createDownloadUrl(String key, Instant expiry) {
            return URI.create("https://storage.test/download");
        }
        @Override public StoredObjectMetadata metadata(String key) { return null; }
        @Override public byte[] read(String key) { return objects.get(key); }
    }
}
