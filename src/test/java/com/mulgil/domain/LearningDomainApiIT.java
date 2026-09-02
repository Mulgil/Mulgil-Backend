package com.mulgil.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LearningDomainApiIT {
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

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void supportsOwnerScopedLearningLifecycle_whenRequestsAreAuthenticated() throws Exception {
        String ownerA = login("domain-owner-a");
        String ownerB = login("domain-owner-b");

        JsonNode course = successful(post("/api/v1/courses", ownerA, Map.of(
                "name", "Algorithms", "instructor", "Professor Kim", "term", "2026-Fall")), 201);
        String courseId = course.path("id").asText();
        assertThat(successful(get("/api/v1/courses", ownerA), 200)).hasSize(1);

        JsonNode slot = successful(post("/api/v1/timetable/slots", ownerA, Map.of(
                "courseId", courseId, "weekday", 1, "startTime", "09:00",
                "endTime", "10:15", "timezone", "Asia/Seoul")), 201);
        String slotId = slot.path("id").asText();
        JsonNode updatedSlot = successful(patch("/api/v1/timetable/slots/" + slotId, ownerA, Map.of(
                "courseId", courseId, "weekday", 3, "startTime", "13:00",
                "endTime", "14:15", "timezone", "Asia/Seoul")), 200);
        assertThat(updatedSlot.path("weekday").asInt()).isEqualTo(3);
        assertThat(successful(get("/api/v1/timetable/slots?courseId=" + courseId, ownerA), 200)).hasSize(1);

        JsonNode session = successful(post("/api/v1/courses/" + courseId + "/sessions", ownerA, Map.of(
                "sessionNumber", 1, "title", "Graph traversal", "sessionDate", "2026-09-01",
                "startsAt", "2026-09-01T00:00:00Z", "endsAt", "2026-09-01T01:15:00Z")), 201);
        String sessionId = session.path("id").asText();
        assertThat(successful(get("/api/v1/sessions/" + sessionId, ownerA), 200)
                .path("sessionNumber").asInt()).isEqualTo(1);
        assertThat(successful(get("/api/v1/courses/" + courseId + "/sessions", ownerA), 200)).hasSize(1);

        JsonNode exam = successful(post("/api/v1/courses/" + courseId + "/exams", ownerA, Map.of(
                "title", "Midterm", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of(sessionId))), 201);
        assertThat(exam.path("sessionIds")).containsExactly(objectMapper.getNodeFactory().textNode(sessionId));
        JsonNode listedExams = successful(get("/api/v1/courses/" + courseId + "/exams", ownerA), 200);
        assertThat(listedExams).hasSize(1);
        assertThat(listedExams.get(0).path("sessionIds"))
                .containsExactly(objectMapper.getNodeFactory().textNode(sessionId));

        assertError(get("/api/v1/sessions/" + sessionId, ownerB), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/courses/" + courseId + "/sessions", ownerB), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/courses/" + courseId + "/exams", ownerB), 404, "RESOURCE_NOT_FOUND");
        assertError(patch("/api/v1/timetable/slots/" + slotId, ownerB, Map.of(
                "courseId", courseId, "weekday", 2, "startTime", "09:00",
                "endTime", "10:00", "timezone", "Asia/Seoul")), 404, "RESOURCE_NOT_FOUND");
        assertError(post("/api/v1/timetable/slots", ownerB, Map.of(
                "courseId", courseId, "weekday", 2, "startTime", "09:00",
                "endTime", "10:00", "timezone", "Asia/Seoul")), 404, "RESOURCE_NOT_FOUND");

        HttpResult deleted = delete("/api/v1/timetable/slots/" + slotId, ownerA);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(successful(get("/api/v1/timetable/slots", ownerA), 200)).isEmpty();
        recordHttp("ownerLifecycle", 201, "course+slot+session+exam");
        recordHttp("foreignOwner", 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void updatesOwnedCourse_whenEditableFieldsAreSubmitted() throws Exception {
        String owner = login("course-update-owner");
        String courseId = successful(post("/api/v1/courses", owner, Map.of(
                "name", "Algorithms", "instructor", "Professor Kim", "term", "2026-Fall")), 201)
                .path("id").asText();

        JsonNode updated = successful(patch("/api/v1/courses/" + courseId, owner, Map.of(
                "name", "Advanced Algorithms", "instructor", "Professor Lee", "term", "2027-Spring")), 200);

        assertThat(updated.path("name").asText()).isEqualTo("Advanced Algorithms");
        assertThat(updated.path("instructor").asText()).isEqualTo("Professor Lee");
        assertThat(updated.path("term").asText()).isEqualTo("2027-Spring");
    }

    @Test
    void softDeletesOwnedCourse_whenRequested() throws Exception {
        String owner = login("course-delete-owner");
        String courseId = createCourse(owner, "Databases");
        successful(post("/api/v1/timetable/slots", owner, Map.of(
                "courseId", courseId, "weekday", 1, "startTime", "09:00",
                "endTime", "10:00", "timezone", "Asia/Seoul")), 201);
        String sessionId = createSession(owner, courseId, 1);

        HttpResult deleted = delete("/api/v1/courses/" + courseId, owner);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(successful(get("/api/v1/courses", owner), 200)).isEmpty();
        assertThat(successful(get("/api/v1/timetable/slots", owner), 200)).isEmpty();
        assertError(get("/api/v1/courses/" + courseId + "/sessions", owner), 404, "RESOURCE_NOT_FOUND");
        assertError(get("/api/v1/sessions/" + sessionId, owner), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM courses WHERE id = :id")
                .param("id", UUID.fromString(courseId)).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM class_sessions WHERE course_id = :id")
                .param("id", UUID.fromString(courseId)).query(Integer.class).single()).isEqualTo(1);
        recordHttp("softDeleteCourse", 204, "courseHidden=1 sessionRetained=1");
    }

    @Test
    void hidesArchivedCourseDescendants_butRetainsNotes() throws Exception {
        String owner = login("course-delete-descendants-owner");
        String courseId = createCourse(owner, "Distributed Systems");
        String sessionId = createSession(owner, courseId, 1);
        String noteId = successful(post("/api/v1/sessions/" + sessionId + "/notes", owner,
                Map.of("bodyMarkdown", "Archived note")), 201).path("id").asText();
        String examId = successful(post("/api/v1/courses/" + courseId + "/exams", owner, Map.of(
                "title", "Final", "examAt", "2026-12-01T01:00:00Z", "sessionIds", List.of(sessionId))), 201)
                .path("id").asText();

        assertThat(delete("/api/v1/courses/" + courseId, owner).status()).isEqualTo(204);

        assertError(get("/api/v1/sessions/" + sessionId + "/notes", owner), 404, "NOTE_NOT_FOUND");
        assertError(get("/api/v1/notes/" + noteId, owner), 404, "NOTE_NOT_FOUND");
        assertError(post("/api/v1/sessions/" + sessionId + "/notes", owner,
                Map.of("bodyMarkdown", "Must not be created")), 404, "NOTE_NOT_FOUND");
        assertError(patch("/api/v1/notes/" + noteId, owner,
                Map.of("bodyMarkdown", "Must not be updated", "expectedVersion", 1)), 404, "NOTE_NOT_FOUND");
        assertError(get("/api/v1/sessions/" + sessionId + "/jobs", owner), 404, "JOB_NOT_FOUND");
        assertError(get("/api/v1/sessions/" + sessionId + "/quiz", owner), 404, "QUIZ_NOT_FOUND");
        assertError(get("/api/v1/sessions/" + sessionId + "/summaries?type=review", owner), 404,
                "SESSION_NOT_FOUND");
        assertError(get("/api/v1/exams/" + examId + "/summary", owner), 404, "EXAM_NOT_FOUND");
        assertError(get("/api/v1/exams/" + examId + "/predicted-quiz", owner), 404, "EXAM_NOT_FOUND");
        assertError(post("/api/v1/exams/" + examId + "/summary/generate", owner, Map.of()), 404,
                "EXAM_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM notes WHERE id=:id")
                .param("id", UUID.fromString(noteId)).query(Integer.class).single()).isOne();
        recordHttp("archivedCourseDescendants", 404, "notes-jobs-quiz-generation-hidden noteRetained=1");
    }

    @Test
    void rejectsDuplicateAndCrossCourseExamScope_whenPayloadIsInvalid() throws Exception {
        String ownerA = login("domain-validation-a");
        String ownerB = login("domain-validation-b");
        String courseA = createCourse(ownerA, "Databases");
        String otherCourseA = createCourse(ownerA, "Networks");
        String courseB = createCourse(ownerB, "Operating Systems");
        String sessionA = createSession(ownerA, courseA, 1);
        String otherSessionA = createSession(ownerA, otherCourseA, 1);
        String sessionB = createSession(ownerB, courseB, 1);

        assertError(post("/api/v1/courses/" + courseA + "/sessions", ownerA, Map.of(
                "sessionNumber", 1, "title", "Duplicate", "sessionDate", "2026-09-08")),
                422, "VALIDATION_FAILED");
        assertError(post("/api/v1/courses/" + courseA + "/exams", ownerA, Map.of(
                "title", "Duplicate range", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of(sessionA, sessionA))), 422, "VALIDATION_FAILED");
        assertError(post("/api/v1/courses/" + courseA + "/exams", ownerA, Map.of(
                "title", "Empty range", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of())), 422, "VALIDATION_FAILED");
        assertError(post("/api/v1/courses/" + courseA + "/exams", ownerA, Map.of(
                "title", "Cross course", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of(sessionA, otherSessionA))), 422, "VALIDATION_FAILED");
        assertError(post("/api/v1/courses/" + courseA + "/exams", ownerA, Map.of(
                "title", "Cross owner", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of(sessionA, sessionB))), 422, "VALIDATION_FAILED");
        assertError(post("/api/v1/courses/" + UUID.randomUUID() + "/sessions", ownerA, Map.of(
                "sessionNumber", 2, "title", "Missing course", "sessionDate", "2026-09-08")),
                404, "RESOURCE_NOT_FOUND");
        recordHttp("duplicateSessionAndMembers", 422, "VALIDATION_FAILED");
        recordHttp("crossCourseAndOwnerMembers", 422, "VALIDATION_FAILED");
    }

    @Test
    void appliesV002AndEnforcesMembershipScope_whenDatabaseIsFresh() throws Exception {
        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '002'")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name IN
                            ('courses', 'timetable_slots', 'class_sessions', 'exams', 'exam_session_members')
                        """).query(Integer.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT installed_rank FROM flyway_schema_history WHERE version = '002'")
                .query(Integer.class).single()).isGreaterThan(
                jdbc.sql("SELECT installed_rank FROM flyway_schema_history WHERE version = '001'")
                        .query(Integer.class).single());

        String owner = login("domain-db-owner");
        String courseOne = createCourse(owner, "Course One");
        String courseTwo = createCourse(owner, "Course Two");
        String sessionOne = createSession(owner, courseOne, 1);
        String sessionTwo = createSession(owner, courseTwo, 1);
        String examId = successful(post("/api/v1/courses/" + courseOne + "/exams", owner, Map.of(
                "title", "Scoped exam", "examAt", "2026-10-20T01:00:00Z",
                "sessionIds", List.of(sessionOne))), 201).path("id").asText();
        UUID ownerId = jdbc.sql("SELECT owner_id FROM courses WHERE id = :id")
                .param("id", UUID.fromString(courseOne)).query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO exam_session_members (exam_id, session_id, owner_id, course_id, created_at)
                        VALUES (:examId, :sessionId, :ownerId, :courseId, now())
                        """)
                .param("examId", UUID.fromString(examId)).param("sessionId", UUID.fromString(sessionTwo))
                .param("ownerId", ownerId).param("courseId", UUID.fromString(courseOne)).update())
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("DOMAIN_DB migrationOrder=V001,V002 tables=5 examSessionMembers=present "
                + "sameCourseForeignKeys=enforced result=PASS");
    }

    private String createCourse(String token, String name) throws Exception {
        return successful(post("/api/v1/courses", token, Map.of("name", name)), 201).path("id").asText();
    }

    private String createSession(String token, String courseId, int number) throws Exception {
        return successful(post("/api/v1/courses/" + courseId + "/sessions", token, Map.of(
                "sessionNumber", number, "title", "Session " + number, "sessionDate", "2026-09-01")), 201)
                .path("id").asText();
    }

    private String login(String subject) throws Exception {
        String fakeToken = String.join("|", "fake", "https://accounts.google.com",
                "test-google-client", subject, subject + "@example.com", "Student",
                Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        HttpResult result = send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fakeToken));
        assertThat(result.status()).isEqualTo(200);
        return objectMapper.readTree(result.body()).path("accessToken").asText();
    }

    private HttpResult get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private HttpResult post(String path, String token, Object body) throws Exception {
        return send("POST", path, token, body);
    }

    private HttpResult patch(String path, String token, Object body) throws Exception {
        return send("PATCH", path, token, body);
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

    private static void recordHttp(String scenario, int status, String observable) {
        System.out.printf("DOMAIN_HTTP scenario=%s status=%d observable=%s result=PASS%n",
                scenario, status, observable);
    }

    private record HttpResult(int status, String body) {}
}
