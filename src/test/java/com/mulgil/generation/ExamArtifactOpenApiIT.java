package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExamArtifactOpenApiIT {
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

    @Test
    void documentsExamReadsAndScopedAttempts_withoutPublicSolutionFields() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = json.readTree(response.body());
        JsonNode summary = document.at("/paths/~1api~1v1~1exams~1{examId}~1summary/get");
        JsonNode quiz = document.at("/paths/~1api~1v1~1exams~1{examId}~1predicted-quiz/get");
        JsonNode attempt = document.at("/paths/~1api~1v1~1quiz~1questions~1{questionId}~1attempts/post");

        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(document.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(document.at("/components/securitySchemes/bearerAuth/bearerFormat").asText()).isEqualTo("JWT");
        assertThat(document.has("security")).isFalse();
        assertThat(document.at("/paths/~1api~1v1~1auth~1oauth~1google/post/security").isEmpty()).isTrue();
        assertThat(summary.path("security").toString()).isEqualTo("[{\"bearerAuth\":[]}]");
        assertThat(quiz.path("security").toString()).isEqualTo("[{\"bearerAuth\":[]}]");
        assertThat(attempt.path("security").toString()).isEqualTo("[{\"bearerAuth\":[]}]");

        assertThat(summary.path("summary").asText()).isNotBlank();
        assertThat(summary.at("/responses/200/content/application~1json/schema/$ref").asText())
                .endsWith("/ExamGeneration");
        assertThat(summary.path("responses").fieldNames()).toIterable().contains("200", "404", "409");
        assertThat(summary.at("/responses/404/description").asText())
                .contains("EXAM_NOT_FOUND", "GENERATION_NOT_FOUND");
        assertThat(summary.at("/responses/409/description").asText())
                .contains("INSUFFICIENT_SOURCE_DATA", "EMBEDDING_NOT_READY");
        assertThat(document.at("/components/schemas/ExamGeneration/properties").fieldNames()).toIterable()
                .contains("id", "type", "inputVersion", "items", "tables");
        assertThat(document.at("/components/schemas/ExamGeneration/properties/type/enum/0").asText())
                .isEqualTo("exam");
        assertThat(quiz.at("/responses/200/content/application~1json/schema/type").asText()).isEqualTo("array");
        assertThat(quiz.at("/responses/200/content/application~1json/schema/items/$ref").asText())
                .endsWith("/QuizQuestion");
        assertThat(quiz.path("responses").fieldNames()).toIterable().contains("200", "404", "409");
        assertThat(quiz.at("/responses/404/description").asText()).contains("EXAM_NOT_FOUND");
        assertThat(quiz.at("/responses/409/description").asText()).contains("QUIZ_NOT_READY");
        assertThat(document.at("/components/schemas/QuizQuestion/properties").has("answer")).isFalse();
        assertThat(document.at("/components/schemas/QuizQuestion/properties").has("explanation")).isFalse();
        assertThat(attempt.at("/responses/201/content/application~1json/schema/$ref").asText())
                .endsWith("/QuizAttemptResult");
        assertThat(attempt.path("responses").fieldNames()).toIterable().contains("201", "404", "422");
        assertThat(attempt.at("/responses/404/description").asText()).contains("QUIZ_NOT_FOUND");
        assertThat(attempt.at("/responses/422/description").asText())
                .contains("VALIDATION_FAILED", "QUIZ_INVALID");
        assertThat(document.at("/components/schemas/QuizProgress/properties").fieldNames()).toIterable()
                .contains("scopeType", "scopeId", "correctCount", "incorrectCount", "lastAttemptAt");
        assertThat(document.at("/components/schemas/QuizProgress/properties").has("sessionId")).isFalse();
        assertThat(document.at("/components/schemas/QuizProgress/properties").has("examId")).isFalse();
        assertThat(document.at("/components/schemas/SessionGeneration/properties/summary/$ref").asText())
                .doesNotEndWith("/ExamGeneration");
    }
}
