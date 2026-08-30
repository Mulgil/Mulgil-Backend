package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExamArtifactsIT {
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
    String ownerToken;
    String foreignToken;
    UUID owner;
    UUID course;
    UUID session;
    UUID exam;
    UUID question;

    @BeforeEach
    void seed() throws Exception {
        String subject = "exam-owner-" + UUID.randomUUID();
        ownerToken = login(subject);
        owner = jdbc.sql("SELECT id FROM users WHERE provider_subject=:subject")
                .param("subject", subject).query(UUID.class).single();
        foreignToken = login("exam-foreign-" + UUID.randomUUID());
        course = UUID.fromString(ok(send("POST", "/api/v1/courses", ownerToken,
                Map.of("name", "Exam artifacts")), 201).path("id").asText());
        session = UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/sessions", ownerToken,
                Map.of("sessionNumber", 1, "title", "Scope", "sessionDate", "2026-09-01")), 201)
                .path("id").asText());
        exam = createExam("Midterm");
        insertSummary();
        question = insertQuestion(exam, "succeeded");
    }

    @Test
    void readsOwnerExamArtifacts_withoutLeakingSolutions() throws Exception {
        HttpResult summaryResult = send("GET", "/api/v1/exams/" + exam + "/summary", ownerToken, null);
        JsonNode summary = ok(summaryResult, 200);
        HttpResult quizResult = send("GET", "/api/v1/exams/" + exam + "/predicted-quiz", ownerToken, null);
        JsonNode quiz = ok(quizResult, 200);

        assertThat(summary.path("type").asText()).isEqualTo("exam");
        assertThat(summary.path("inputVersion").asInt()).isOne();
        assertThat(summary.path("items")).hasSize(1);
        assertThat(summary.path("tables").isArray()).isTrue();
        assertThat(quiz).hasSize(1);
        assertThat(quizResult.body()).doesNotContain("answer", "explanation");
        assertThat(error(send("GET", "/api/v1/exams/" + exam + "/summary", foreignToken, null),
                404)).isEqualTo("EXAM_NOT_FOUND");
        assertThat(error(send("GET", "/api/v1/exams/" + exam + "/predicted-quiz", foreignToken, null),
                404)).isEqualTo("EXAM_NOT_FOUND");
    }

    @Test
    void attemptsPredictedQuiz_andMaintainsExamProgress() throws Exception {
        JsonNode first = ok(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of("answer", true)), 201);
        JsonNode retry = ok(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of("answer", false)), 201);

        assertThat(first.path("isCorrect").asBoolean()).isTrue();
        assertThat(first.path("answer").path("value").asBoolean()).isTrue();
        assertThat(first.path("progress").path("scopeType").asText()).isEqualTo("exam");
        assertThat(first.path("progress").path("scopeId").asText()).isEqualTo(exam.toString());
        assertThat(first.path("progress").has("sessionId")).isFalse();
        assertThat(first.path("progress").has("examId")).isFalse();
        assertThat(retry.path("progress").path("correctCount").asInt()).isOne();
        assertThat(retry.path("progress").path("incorrectCount").asInt()).isOne();
        assertThat(first.path("attemptId").asText()).isNotEqualTo(retry.path("attemptId").asText());
        assertThat(error(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", foreignToken,
                Map.of("answer", true)), 404)).isEqualTo("QUIZ_NOT_FOUND");
        assertThat(error(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of("answer", 1)), 422)).isEqualTo("VALIDATION_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner AND course_id=:course "
                        + "AND exam_id=:exam AND session_id IS NULL AND quiz_question_id=:question")
                .param("owner", owner).param("course", course).param("exam", exam)
                .param("question", question).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM progress_status WHERE owner_id=:owner AND course_id=:course "
                        + "AND exam_id=:exam AND session_id IS NULL")
                .param("owner", owner).param("course", course).param("exam", exam)
                .query(Integer.class).single()).isOne();
    }

    @Test
    void returnsReadinessAndStaleErrors_andDatabaseRejectsMixedScope() throws Exception {
        UUID emptyExam = createExam("Empty");
        assertThat(error(send("GET", "/api/v1/exams/" + emptyExam + "/summary", ownerToken, null),
                409)).isEqualTo("INSUFFICIENT_SOURCE_DATA");
        assertThat(error(send("GET", "/api/v1/exams/" + emptyExam + "/predicted-quiz", ownerToken, null),
                409)).isEqualTo("QUIZ_NOT_READY");
        assertThat(send("GET", "/api/v1/exams/not-a-uuid/summary", ownerToken, null).status()).isEqualTo(400);
        assertThat(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of()).status()).isEqualTo(422);
        jdbc.sql("UPDATE quiz_questions SET status='outdated' WHERE owner_id=:owner AND course_id=:course "
                        + "AND exam_id=:exam AND id=:question")
                .param("owner", owner).param("course", course).param("exam", exam)
                .param("question", question).update();
        assertThat(error(send("POST", "/api/v1/quiz/questions/" + question + "/attempts", ownerToken,
                Map.of("answer", true)), 404)).isEqualTo("QUIZ_NOT_FOUND");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO quiz_attempts
                            (id,owner_id,course_id,session_id,exam_id,quiz_question_id,
                             submitted_answer,is_correct,submitted_at)
                        VALUES (:id,:owner,:course,:session,:exam,:question,'{"value":true}',true,:now)
                        """).param("id", UUID.randomUUID()).param("owner", owner).param("course", course)
                .param("session", session).param("exam", exam).param("question", question)
                .param("now", Timestamp.from(Instant.now())).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID otherExam = createExam("Other");
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO quiz_attempts
                            (id,owner_id,course_id,exam_id,quiz_question_id,
                             submitted_answer,is_correct,submitted_at)
                        VALUES (:id,:owner,:course,:exam,:question,'{"value":true}',true,:now)
                        """).param("id", UUID.randomUUID()).param("owner", owner).param("course", course)
                .param("exam", otherExam).param("question", question)
                .param("now", Timestamp.from(Instant.now())).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createExam(String title) throws Exception {
        return UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/exams", ownerToken,
                Map.of("title", title, "examAt", "2026-10-01T00:00:00Z", "sessionIds", List.of(session))), 201)
                .path("id").asText());
    }

    private void insertSummary() throws Exception {
        var ref = List.of(Map.of("sourceType", "note", "noteId", UUID.randomUUID().toString()));
        var content = Map.of("items", List.of(Map.of("text", "sanitized", "sourceRefs", ref)),
                "tables", List.of());
        jdbc.sql("""
                        INSERT INTO summaries
                            (id,owner_id,course_id,exam_id,summary_type,input_version,content_json,
                             source_refs,status,model_id,prompt_version,created_at,updated_at)
                        VALUES (:id,:owner,:course,:exam,'exam',1,CAST(:content AS jsonb),CAST(:refs AS jsonb),
                                'succeeded','fake','v1',:now,:now)
                        """).param("id", UUID.randomUUID()).param("owner", owner).param("course", course)
                .param("exam", exam).param("content", json.writeValueAsString(content))
                .param("refs", json.writeValueAsString(ref)).param("now", Timestamp.from(Instant.now())).update();
    }

    private UUID insertQuestion(UUID targetExam, String status) throws Exception {
        UUID id = UUID.randomUUID();
        var ref = List.of(Map.of("sourceType", "past_exam", "examResourceId", UUID.randomUUID().toString()));
        var questionJson = Map.of("text", "Sanitized prompt", "sourceRefs", ref);
        var answer = Map.of("value", true, "sourceRefs", ref);
        var explanation = Map.of("text", "Sanitized explanation", "sourceRefs", ref);
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,exam_id,quiz_scope,question_type,input_version,question_json,
                             answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:exam,'past_exam_based','true_false',1,
                                CAST(:question AS jsonb),CAST(:answer AS jsonb),CAST(:explanation AS jsonb),
                                :status,'fake','v1',:now)
                        """).param("id", id).param("owner", owner).param("course", course).param("exam", targetExam)
                .param("question", json.writeValueAsString(questionJson))
                .param("answer", json.writeValueAsString(answer))
                .param("explanation", json.writeValueAsString(explanation)).param("status", status)
                .param("now", Timestamp.from(Instant.now())).update();
        return id;
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        return ok(send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fake)), 200)
                .path("accessToken").asText();
    }

    private HttpResult send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private JsonNode ok(HttpResult result, int status) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        return json.readTree(result.body());
    }

    private String error(HttpResult result, int status) throws Exception {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        return json.readTree(result.body()).path("code").asText();
    }

    record HttpResult(int status, String body) {}
}
