package com.mulgil.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.error.ApiException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthApiIT {
    private static final String TEST_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

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
    GoogleIdTokenVerifierPort googleVerifier;

    @Autowired
    JwtService jwtService;

    @Test
    void returnsInternalUserAndSignedTokens_whenGoogleTokenIsValid(CapturedOutput output) throws Exception {
        assertThat(googleVerifier).isInstanceOf(FakeGoogleIdTokenVerifier.class);
        HttpResult login = post("/api/v1/auth/oauth/google", new TokenRequest(fakeToken(
                FakeGoogleIdTokenVerifier.ISSUER, "test-google-client", "valid-login")));

        assertThat(login.status()).isEqualTo(200);
        JsonNode loginBody = objectMapper.readTree(login.body());
        assertThat(loginBody.at("/user/email").asText()).isEqualTo("student@example.com");
        assertAccessClaims(loginBody.path("accessToken").asText());
        assertThat(output.getAll()).doesNotContain(
                TEST_SECRET, loginBody.path("accessToken").asText(), loginBody.path("refreshToken").asText());
        recordHttp("login", login);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGoogleTokens")
    void rejectsGoogleToken_whenClaimIsInvalid(
            String scenario,
            String issuer,
            String audience,
            long expiryOffsetSeconds
    ) throws Exception {
        HttpResult result = post("/api/v1/auth/oauth/google", new TokenRequest(fakeToken(
                issuer, audience, scenario, Instant.now().plusSeconds(expiryOffsetSeconds))));

        assertError(result, 401, "UNAUTHENTICATED");
        recordHttp(scenario, result);
    }

    static Stream<Arguments> invalidGoogleTokens() {
        return Stream.of(
                Arguments.of("invalidIssuer", "https://invalid.example", "test-google-client", 300L),
                Arguments.of("invalidAudience", FakeGoogleIdTokenVerifier.ISSUER, "other-client", 300L),
                Arguments.of("expiredGoogleToken", FakeGoogleIdTokenVerifier.ISSUER, "test-google-client", -1L));
    }

    @Test
    void revokesWholeFamily_whenRotatedRefreshTokenIsReused(CapturedOutput output) throws Exception {
        HttpResult login = post("/api/v1/auth/oauth/google", new TokenRequest(fakeToken(
                FakeGoogleIdTokenVerifier.ISSUER, "test-google-client", "refresh-reuse")));
        assertThat(login.status()).isEqualTo(200);
        JsonNode loginBody = objectMapper.readTree(login.body());
        String originalRefreshToken = loginBody.path("refreshToken").asText();
        recordHttp("refreshLogin", login);

        HttpResult refresh = post("/api/v1/auth/refresh", new RefreshRequest(originalRefreshToken));
        assertThat(refresh.status()).isEqualTo(200);
        JsonNode refreshBody = objectMapper.readTree(refresh.body());
        String successorRefreshToken = refreshBody.path("refreshToken").asText();
        assertThat(successorRefreshToken).isNotEqualTo(originalRefreshToken);
        recordHttp("refresh", refresh);

        HttpResult replay = post("/api/v1/auth/refresh", new RefreshRequest(originalRefreshToken));
        assertError(replay, 401, "UNAUTHENTICATED");
        recordHttp("refreshReuse", replay);

        UUID familyId = jdbc.sql("SELECT family_id FROM auth_refresh_tokens WHERE token_hash = :hash")
                .param("hash", TokenHash.sha256(originalRefreshToken))
                .query(UUID.class)
                .single();
        Integer familyRows = jdbc.sql("SELECT count(*) FROM auth_refresh_tokens WHERE family_id = :familyId")
                .param("familyId", familyId)
                .query(Integer.class)
                .single();
        Integer activeRows = jdbc.sql("""
                        SELECT count(*) FROM auth_refresh_tokens
                        WHERE family_id = :familyId AND revoked_at IS NULL
                        """)
                .param("familyId", familyId)
                .query(Integer.class)
                .single();
        assertThat(familyRows).isEqualTo(2);
        assertThat(activeRows).isZero();

        HttpResult successorAfterReuse = post("/api/v1/auth/refresh", new RefreshRequest(successorRefreshToken));
        assertError(successorAfterReuse, 401, "UNAUTHENTICATED");
        recordHttp("familyRevoked", successorAfterReuse);
        assertThat(output.getAll()).doesNotContain(
                TEST_SECRET, originalRefreshToken, successorRefreshToken,
                loginBody.path("accessToken").asText(), refreshBody.path("accessToken").asText());
        System.out.printf(
                "AUTH_DB scenario=refreshReuse familyRows=%d activeRows=%d successorRefreshStatus=%d result=PASS%n",
                familyRows, activeRows, successorAfterReuse.status());
    }

    @Test
    void returnsCamelCaseValidationError_whenIdTokenIsBlank() throws Exception {
        HttpResult invalidRequest = post("/api/v1/auth/oauth/google", new TokenRequest(""));
        assertError(invalidRequest, 422, "VALIDATION_FAILED");
        assertThat(objectMapper.readTree(invalidRequest.body()).at("/details/field").asText()).isEqualTo("idToken");
        recordHttp("camelCaseValidation", invalidRequest);
    }

    @Test
    void revokesRefreshFamily_whenUserLogsOut(CapturedOutput output) throws Exception {
        HttpResult login = post("/api/v1/auth/oauth/google", new TokenRequest(fakeToken(
                FakeGoogleIdTokenVerifier.ISSUER, "test-google-client", "logout")));
        JsonNode loginBody = objectMapper.readTree(login.body());
        String logoutRefreshToken = loginBody.path("refreshToken").asText();
        HttpResult logout = post("/api/v1/auth/logout", new RefreshRequest(logoutRefreshToken));
        assertThat(logout.status()).isEqualTo(204);
        recordHttp("logout", logout);
        HttpResult refreshAfterLogout = post("/api/v1/auth/refresh", new RefreshRequest(logoutRefreshToken));
        assertError(refreshAfterLogout, 401, "UNAUTHENTICATED");
        recordHttp("refreshAfterLogout", refreshAfterLogout);
        assertThat(output.getAll()).doesNotContain(
                TEST_SECRET, logoutRefreshToken, loginBody.path("accessToken").asText());
    }

    @Test
    void appliesAuthMigrationAndPgvector_whenDatabaseIsFresh() {
        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '001'")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT extname FROM pg_extension WHERE extname = 'vector'")
                .query(String.class).single()).isEqualTo("vector");
        System.out.println("AUTH_DB scenario=freshDatabase migrationV001=applied pgvectorExtension=installed result=PASS");
    }

    @Test
    void rejectsAccessToken_whenClaimsOrKeyIdAreInvalid() throws Exception {
        assertThatThrownBy(() -> jwtService.verify(signedAccessToken(
                "wrong-issuer", "mulgil-mobile", "demo-v1", Instant.now().plusSeconds(60))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> jwtService.verify(signedAccessToken(
                "mulgil-api", "wrong-audience", "demo-v1", Instant.now().plusSeconds(60))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> jwtService.verify(signedAccessToken(
                "mulgil-api", "mulgil-mobile", "wrong-key", Instant.now().plusSeconds(60))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> jwtService.verify(signedAccessToken(
                "mulgil-api", "mulgil-mobile", "demo-v1", Instant.now().minusSeconds(1))))
                .isInstanceOf(ApiException.class);
    }

    private HttpResult post(String path, Object body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private void assertAccessClaims(String accessToken) throws ParseException {
        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("demo-v1");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("mulgil-api");
        assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("mulgil-mobile");
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isAfter(Instant.now());
    }

    private void assertError(HttpResult result, int status, String code) throws IOException {
        assertThat(result.status()).isEqualTo(status);
        JsonNode body = objectMapper.readTree(result.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.has("message")).isTrue();
        assertThat(body.has("details")).isTrue();
    }

    private String redacted(String scenario, HttpResult result) throws IOException {
        if (result.body().isEmpty()) {
            return scenario + " status=" + result.status() + " body=<empty>";
        }
        JsonNode body = objectMapper.readTree(result.body());
        if (body.isObject()) {
            if (body.has("accessToken")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("accessToken", "<redacted>");
            }
            if (body.has("refreshToken")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("refreshToken", "<redacted>");
            }
            if (body.has("user")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("user", "<redacted>");
            }
        }
        return scenario + " status=" + result.status() + " body=" + objectMapper.writeValueAsString(body);
    }

    private void recordHttp(String scenario, HttpResult result) throws IOException {
        System.out.println("AUTH_HTTP " + redacted(scenario, result));
    }

    private String fakeToken(String issuer, String audience, String subject) {
        return fakeToken(issuer, audience, subject, Instant.now().plusSeconds(300));
    }

    private String fakeToken(String issuer, String audience, String subject, Instant expiresAt) {
        return String.join("|", "fake", issuer, audience, subject,
                "student@example.com", "Student", Long.toString(expiresAt.getEpochSecond()));
    }

    private String signedAccessToken(String issuer, String audience, String keyId, Instant expiresAt) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(keyId).build(), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(TEST_SECRET)));
        return jwt.serialize();
    }

    private record TokenRequest(String idToken) {}

    private record RefreshRequest(String refreshToken) {}

    private record HttpResult(int status, String body) {}
}
