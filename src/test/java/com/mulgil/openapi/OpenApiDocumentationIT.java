package com.mulgil.openapi;

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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIT {
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

    @Test
    void publishesEveryMvpEndpointWithPlanBasedDocumentation() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.at("/info/title").asText()).isEqualTo("Mulgil MVP Backend API");
        assertThat(document.at("/components/securitySchemes/bearerAuth/type").asText()).isEqualTo("http");
        assertThat(documentedMvpOperationCount(document)).isEqualTo(44);
        assertThat(document.at("/paths/~1api~1v1~1sessions~1{sessionId}~1notes/get/summary").asText())
                .isEqualTo("차시 노트 목록 조회");
        assertThat(document.at("/paths/~1api~1v1~1notes~1{noteId}/get/summary").asText())
                .isEqualTo("노트 단건 조회");
        assertThat(document.at("/paths/~1api~1v1~1sessions~1{sessionId}~1quiz/get/summary").asText()).isNotBlank();
        assertThat(document.at("/paths/~1api~1v1~1exams~1{examId}~1predicted-quiz~1generate/post/description").asText())
                .contains("past_exam");
        assertThat(document.at("/paths/~1api~1v1~1auth~1oauth~1google/post/security").size()).isZero();
        assertThat(document.at("/paths/~1api~1v1~1sessions~1{sessionId}~1quiz/get/security/0/bearerAuth").isArray()).isTrue();
        HttpResponse<String> swaggerUi = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(swaggerUi.statusCode()).isEqualTo(200);
    }

    private static int documentedMvpOperationCount(JsonNode document) {
        Set<String> methods = Set.of("get", "post", "put", "patch", "delete");
        int count = 0;
        for (java.util.Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/v1/")) {
                continue;
            }
            for (java.util.Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (!methods.contains(operation.getKey())) {
                    continue;
                }
                assertThat(operation.getValue().path("summary").asText()).isNotBlank();
                assertThat(operation.getValue().path("description").asText()).isNotBlank();
                assertThat(operation.getValue().path("tags").size()).isEqualTo(1);
                count++;
            }
        }
        return count;
    }
}
