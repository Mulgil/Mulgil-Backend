package com.mulgil.note;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoteWorkflowIT {
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
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM users").update();
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
        JsonNode fetched = ok(send("GET", "/api/v1/notes/" + noteId, owner, null), 200);
        assertThat(fetched.path("bodyMarkdown").asText()).isEqualTo("updated");
        assertThat(fetched.path("version").asInt()).isEqualTo(2);
        JsonNode listed = ok(send("GET", "/api/v1/sessions/" + session + "/notes", owner, null), 200);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("id").asText()).isEqualTo(noteId);
        assertThat(listed.get(0).path("bodyMarkdown").asText()).isEqualTo("updated");
        error(send("PATCH", "/api/v1/notes/" + noteId, owner,
                Map.of("bodyMarkdown", "stale", "expectedVersion", 1)), 409, "VERSION_CONFLICT");
        error(send("PATCH", "/api/v1/notes/" + noteId, login("note-foreign"),
                Map.of("bodyMarkdown", "foreign", "expectedVersion", 2)), 404, "NOTE_NOT_FOUND");
        error(send("GET", "/api/v1/notes/" + noteId, login("note-reader-foreign"), null),
                404, "NOTE_NOT_FOUND");
        error(send("GET", "/api/v1/sessions/" + session + "/notes", login("note-list-foreign"), null),
                404, "NOTE_NOT_FOUND");

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
        System.out.println("NOTE_WORKFLOW scenario=note-version-leave observable=409,404,202,204,one-note-chunk result=PASS");
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
}
