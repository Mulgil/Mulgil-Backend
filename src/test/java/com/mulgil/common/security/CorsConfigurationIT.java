package com.mulgil.common.security;

import org.junit.jupiter.api.Test;
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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mulgil.cors.allowed-origin-patterns=https://web-three-ochre-20.vercel.app,http://localhost:*"
})
class CorsConfigurationIT {
    private static final String ALLOWED_ORIGIN = "https://web-three-ochre-20.vercel.app";
    private static final String BLOCKED_ORIGIN = "https://blocked.example";

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

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void allowsLoginPostPreflight() throws Exception {
        HttpResponse<String> response = preflight(
                ALLOWED_ORIGIN, "/api/v1/auth/oauth/google", "POST", "Content-Type");

        assertPreflightAllowed(response, "POST", "Content-Type");
    }

    @Test
    void allowsBearerApiPreflight() throws Exception {
        HttpResponse<String> response = preflight(
                ALLOWED_ORIGIN, "/api/v1/notifications", "GET", "Authorization");

        assertPreflightAllowed(response, "GET", "Authorization");
    }

    @Test
    void allowsPutPreflightForBackendPutApis() throws Exception {
        for (String path : List.of(
                "/api/v1/materials/00000000-0000-0000-0000-000000000001/annotations",
                "/api/v1/devices/fcm-token")) {
            HttpResponse<String> response = preflight(
                    ALLOWED_ORIGIN, path, "PUT", "Authorization, Content-Type");

            assertPreflightAllowed(response, "PUT", "Authorization", "Content-Type");
        }
    }

    @Test
    void rejectsBlockedOriginsAndHeaders() throws Exception {
        HttpResponse<String> blockedOrigin = preflight(
                BLOCKED_ORIGIN, "/api/v1/auth/oauth/google", "POST", "Content-Type");
        assertThat(blockedOrigin.statusCode()).isEqualTo(403);
        assertThat(header(blockedOrigin, "Access-Control-Allow-Origin")).isEmpty();

        HttpResponse<String> blockedHeader = preflight(
                ALLOWED_ORIGIN, "/api/v1/notifications", "GET", "X-Mulgil-Debug");
        assertThat(blockedHeader.statusCode()).isEqualTo(403);
        assertThat(header(blockedHeader, "Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void keepsCorsHeadersOnAuthenticationFailure() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/notifications"))
                .header("Origin", ALLOWED_ORIGIN)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        assertThat(response.body()).contains("UNAUTHENTICATED");
    }

    private HttpResponse<String> preflight(
            String origin,
            String path,
            String requestedMethod,
            String requestedHeaders
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", requestedMethod)
                .header("Access-Control-Request-Headers", requestedHeaders)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertPreflightAllowed(
            HttpResponse<String> response,
            String expectedMethod,
            String... expectedHeaders
    ) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        assertThat(header(response, "Access-Control-Allow-Methods")).contains(expectedMethod);

        String allowedHeaders = header(response, "Access-Control-Allow-Headers").toLowerCase(Locale.ROOT);
        for (String expectedHeader : expectedHeaders) {
            assertThat(allowedHeaders).contains(expectedHeader.toLowerCase(Locale.ROOT));
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }
}
