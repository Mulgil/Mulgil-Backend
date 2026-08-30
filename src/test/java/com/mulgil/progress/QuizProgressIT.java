package com.mulgil.progress;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QuizProgressIT {
    private static final Path EVIDENCE = Path.of(".omo/evidence/mvp-backend-implementation/task-11");

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
    UUID trueFalse;
    UUID multipleChoice;

    @BeforeEach
    void seed() throws Exception {
        String ownerSubject = "quiz-owner-" + UUID.randomUUID();
        ownerToken = login(ownerSubject);
        owner = jdbc.sql("SELECT id FROM users WHERE provider_subject=:subject")
                .param("subject", ownerSubject).query(UUID.class).single();
        foreignToken = login("quiz-foreign-" + UUID.randomUUID());
        course = UUID.fromString(ok(send("POST", "/api/v1/courses", ownerToken,
                Map.of("name", "Quiz")), 201).path("id").asText());
        session = UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/sessions", ownerToken,
                Map.of("sessionNumber", 1, "title", "Attempts", "sessionDate", "2026-09-01")), 201)
                .path("id").asText());
        trueFalse = insertQuestion("true_false", List.of(), "true", "O/X explanation");
        multipleChoice = insertQuestion("multiple_choice", List.of("A", "B", "C", "D"), "C",
                "Multiple-choice explanation");
    }

    @Test
    void redactsSolutionsAndAppendsBothAnswerKinds_withTransactionalProgress() throws Exception {
        HttpResult fetched = send("GET", "/api/v1/sessions/" + session + "/quiz", ownerToken, null);
        JsonNode quiz = ok(fetched, 200);
        assertThat(quiz).hasSize(2);
        assertThat(fetched.body()).doesNotContain("answer", "explanation");
        assertThat(quiz.get(0).path("sourceRefs")).isNotEmpty();
        assertThat(quiz.get(1).path("options")).hasSize(4);

        JsonNode correct = ok(send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                ownerToken, Map.of("answer", true)), 201);
        JsonNode incorrect = ok(send("POST", "/api/v1/quiz/questions/" + multipleChoice + "/attempts",
                ownerToken, Map.of("answer", 1)), 201);
        assertThat(correct.path("isCorrect").asBoolean()).isTrue();
        assertThat(incorrect.path("isCorrect").asBoolean()).isFalse();
        assertThat(correct.path("answer").path("value").asBoolean()).isTrue();
        assertThat(incorrect.path("answer").path("value").asInt()).isEqualTo(2);
        assertThat(correct.path("answer").path("sourceRefs")).isNotEmpty();
        assertThat(incorrect.path("explanation").path("sourceRefs")).isNotEmpty();

        CompletableFuture<JsonNode> repeatOne = attemptAsync(trueFalse, true);
        CompletableFuture<JsonNode> repeatTwo = attemptAsync(trueFalse, true);
        CompletableFuture.allOf(repeatOne, repeatTwo).join();
        assertThat(repeatOne.join().path("attemptId").asText())
                .isNotEqualTo(repeatTwo.join().path("attemptId").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner")
                .param("owner", owner).query(Integer.class).single()).isEqualTo(4);
        JsonNode progress = repeatOne.join().path("progress").path("correctCount").asInt()
                > repeatTwo.join().path("progress").path("correctCount").asInt()
                ? repeatOne.join().path("progress") : repeatTwo.join().path("progress");
        assertThat(progress.path("correctCount").asInt()).isEqualTo(3);
        assertThat(progress.path("incorrectCount").asInt()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM progress_status WHERE owner_id=:owner AND session_id=:session")
                .param("owner", owner).param("session", session).query(Integer.class).single()).isOne();

        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("quiz-attempt-http.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("quiz", quiz, "trueFalseAttempt", correct,
                        "multipleChoiceAttempt", incorrect, "concurrentProgress", progress)));
        System.out.println("QUIZ_PROGRESS scenario=redaction-attempts observable=no-solution-leak-four-appends-3:1-progress result=PASS");
    }

    @Test
    void rejectsInvalidAnswerShapesAndForeignOrMissingResources() throws Exception {
        int trueFalseNumber = error(send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                ownerToken, Map.of("answer", 0)), 422);
        int choiceBoolean = error(send("POST", "/api/v1/quiz/questions/" + multipleChoice + "/attempts",
                ownerToken, Map.of("answer", true)), 422);
        int choiceLow = error(send("POST", "/api/v1/quiz/questions/" + multipleChoice + "/attempts",
                ownerToken, Map.of("answer", -1)), 422);
        int choiceHigh = error(send("POST", "/api/v1/quiz/questions/" + multipleChoice + "/attempts",
                ownerToken, Map.of("answer", 4)), 422);
        int foreignQuestion = error(send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                foreignToken, Map.of("answer", true)), 404);
        int foreignSession = error(send("GET", "/api/v1/sessions/" + session + "/quiz",
                foreignToken, null), 404);
        int missingQuestion = error(send("POST", "/api/v1/quiz/questions/" + UUID.randomUUID() + "/attempts",
                ownerToken, Map.of("answer", true)), 404);
        String originalChoice = jdbc.sql("SELECT question_json::text FROM quiz_questions WHERE id=:id")
                .param("id", multipleChoice).query(String.class).single();
        jdbc.sql("UPDATE quiz_questions SET question_json=jsonb_set(question_json,'{options}',CAST(:options AS jsonb)) WHERE id=:id")
                .param("options", "[\"A\",\"B\",\"C\"]").param("id", multipleChoice).update();
        int malformedChoice = error(send("POST", "/api/v1/quiz/questions/" + multipleChoice + "/attempts",
                ownerToken, Map.of("answer", 1)), 422);
        jdbc.sql("UPDATE quiz_questions SET question_json=CAST(:question AS jsonb) WHERE id=:id")
                .param("question", originalChoice).param("id", multipleChoice).update();
        jdbc.sql("UPDATE quiz_questions SET status='outdated' WHERE id=:id")
                .param("id", trueFalse).update();
        int staleQuestion = error(send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                ownerToken, Map.of("answer", true)), 404);
        int currentQuizSize = ok(send("GET", "/api/v1/sessions/" + session + "/quiz",
                ownerToken, null), 200).size();
        assertThat(currentQuizSize).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner")
                .param("owner", owner).query(Integer.class).single()).isZero();

        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve("quiz-attempt-failure.txt"), String.join("\n",
                "true-false-number=" + trueFalseNumber, "choice-boolean=" + choiceBoolean,
                "choice-low=" + choiceLow, "choice-high=" + choiceHigh,
                "foreign-question=" + foreignQuestion, "foreign-session=" + foreignSession,
                "missing-question=" + missingQuestion, "malformed-choice=" + malformedChoice,
                "stale-question=" + staleQuestion, "current-quiz-size=" + currentQuizSize,
                "attempt-count=0") + "\n");
        System.out.println("QUIZ_PROGRESS scenario=rejections observable=422-invalid-404-foreign-missing-stale-zero-attempts result=PASS");
    }

    @Test
    void rollsBackAttempt_whenProgressProjectionFails() throws Exception {
        jdbc.sql("""
                CREATE FUNCTION test_reject_progress() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced progress failure'; END;
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_reject_progress BEFORE INSERT OR UPDATE ON progress_status
                FOR EACH ROW EXECUTE FUNCTION test_reject_progress()
                """).update();
        try {
            HttpResult failed = send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                    ownerToken, Map.of("answer", true));
            assertThat(failed.status()).isEqualTo(500);
            assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner")
                    .param("owner", owner).query(Integer.class).single()).isZero();
        } finally {
            jdbc.sql("DROP TRIGGER test_reject_progress ON progress_status").update();
            jdbc.sql("DROP FUNCTION test_reject_progress()").update();
        }
        System.out.println("QUIZ_PROGRESS scenario=atomicity observable=progress-failure-rolls-back-attempt result=PASS");
    }

    @Test
    void rejectsAmbiguousTextAnswers_butKeepsIntegerCorrectIndex() throws Exception {
        UUID duplicateText = insertQuestion("multiple_choice", List.of("A", "A", "C", "D"), "A",
                "Duplicate label");
        UUID numericText = insertQuestion("multiple_choice", List.of("A", "1", "C", "D"), "1",
                "Numeric label");
        UUID integerIndex = insertQuestion("multiple_choice", List.of("A", "1", "C", "D"), 1,
                "Integer index");

        error(send("POST", "/api/v1/quiz/questions/" + duplicateText + "/attempts",
                ownerToken, Map.of("answer", 0)), 422);
        error(send("POST", "/api/v1/quiz/questions/" + numericText + "/attempts",
                ownerToken, Map.of("answer", 1)), 422);
        JsonNode accepted = ok(send("POST", "/api/v1/quiz/questions/" + integerIndex + "/attempts",
                ownerToken, Map.of("answer", 1)), 201);
        JsonNode available = ok(send("GET", "/api/v1/sessions/" + session + "/quiz", ownerToken, null), 200);

        assertThat(accepted.path("isCorrect").asBoolean()).isTrue();
        assertThat(available.findValuesAsText("id")).doesNotContain(duplicateText.toString(), numericText.toString())
                .contains(integerIndex.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner")
                .param("owner", owner).query(Integer.class).single()).isOne();
        System.out.println("QUIZ_PROGRESS scenario=ambiguous-generated-answer observable=duplicate-and-numeric-text-unavailable-integer-index-valid result=PASS");
    }

    @Test
    void keepsProgressTimestampsMonotonic_whenOlderAttemptFinishesAfterNewerProjection() throws Exception {
        Instant newer = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO progress_status
                            (id,owner_id,course_id,session_id,quiz_question_id,state,correct_count,
                             incorrect_count,last_attempt_at,updated_at)
                        VALUES (:id,:owner,:course,:session,NULL,'in_progress',0,0,:newer,:newer)
                        """).param("id", UUID.randomUUID()).param("owner", owner).param("course", course)
                .param("session", session).param("newer", Timestamp.from(newer)).update();

        JsonNode result = ok(send("POST", "/api/v1/quiz/questions/" + trueFalse + "/attempts",
                ownerToken, Map.of("answer", true)), 201);

        assertThat(Instant.parse(result.path("progress").path("lastAttemptAt").asText())).isEqualTo(newer);
        assertThat(Instant.parse(result.path("progress").path("updatedAt").asText())).isEqualTo(newer);
        assertThat(result.path("progress").path("correctCount").asInt()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_attempts WHERE owner_id=:owner")
                .param("owner", owner).query(Integer.class).single()).isOne();
        System.out.println("QUIZ_PROGRESS scenario=timestamp-race observable=older-finisher-counted-without-time-regression result=PASS");
    }

    private CompletableFuture<JsonNode> attemptAsync(UUID question, Object answer) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ok(send("POST", "/api/v1/quiz/questions/" + question + "/attempts",
                        ownerToken, Map.of("answer", answer)), 201);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private UUID insertQuestion(String type, List<String> options, Object answer, String explanation) throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, Object> ref = Map.of("sourceType", "note", "noteId", UUID.randomUUID().toString(),
                "contentBlockId", UUID.randomUUID().toString(), "paragraphOffset", 0, "inputVersion", 1);
        var question = json.createObjectNode().put("text", type + " prompt");
        if (!options.isEmpty()) question.set("options", json.valueToTree(options));
        question.set("sourceRefs", json.valueToTree(List.of(ref)));
        var storedAnswer = json.createObjectNode();
        storedAnswer.set("value", json.valueToTree(answer));
        storedAnswer.set("sourceRefs", json.valueToTree(List.of(ref)));
        var storedExplanation = json.createObjectNode().put("text", explanation);
        storedExplanation.set("sourceRefs", json.valueToTree(List.of(ref)));
        jdbc.sql("""
                        INSERT INTO quiz_questions
                            (id,owner_id,course_id,session_id,quiz_scope,question_type,input_version,
                             question_json,answer_json,explanation_json,status,model_id,prompt_version,created_at)
                        VALUES (:id,:owner,:course,:session,'practice',:type,1,CAST(:question AS jsonb),
                                CAST(:answer AS jsonb),CAST(:explanation AS jsonb),'succeeded','fake','v1',:now)
                        """).param("id", id).param("owner", owner).param("course", course)
                .param("session", session).param("type", type).param("question", json.writeValueAsString(question))
                .param("answer", json.writeValueAsString(storedAnswer))
                .param("explanation", json.writeValueAsString(storedExplanation))
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

    private int error(HttpResult result, int status) {
        assertThat(result.status()).as(result.body()).isEqualTo(status);
        return result.status();
    }

    record HttpResult(int status, String body) {}
}
