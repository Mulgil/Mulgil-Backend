package com.mulgil.progress;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class QuizProgressService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    QuizProgressService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    List<QuizQuestion> quiz(UUID ownerId, UUID sessionId) {
        boolean sessionExists = jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM class_sessions WHERE owner_id=:owner AND id=:session)
                        """).param("owner", ownerId).param("session", sessionId).query(Boolean.class).single();
        if (!sessionExists) throw notFound();
        List<QuizQuestion> questions = jdbc.sql("""
                        SELECT id,question_type,question_json::text FROM quiz_questions
                        WHERE owner_id=:owner AND session_id=:session AND quiz_scope='practice'
                          AND status='succeeded' ORDER BY created_at,id
                        """).param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> publicQuestion(row.getObject("id", UUID.class),
                        row.getString("question_type"), parse(row.getString("question_json")))).list();
        if (questions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "QUIZ_NOT_READY", "Quiz is not available yet.");
        }
        return questions;
    }

    @Transactional
    AttemptResult attempt(UUID ownerId, UUID questionId, JsonNode submitted) {
        StoredQuestion question = jdbc.sql("""
                        SELECT course_id,session_id,question_type,question_json::text,answer_json::text,
                               explanation_json::text
                        FROM quiz_questions WHERE owner_id=:owner AND id=:question AND status='succeeded'
                          AND session_id IS NOT NULL
                        """).param("owner", ownerId).param("question", questionId)
                .query((row, ignored) -> new StoredQuestion(row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getString("question_type"),
                        parse(row.getString("question_json")), parse(row.getString("answer_json")),
                        parse(row.getString("explanation_json")))).optional().orElseThrow(QuizProgressService::notFound);
        Grading grading = grade(question, submitted);
        Instant now = clock.instant();
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO quiz_attempts
                            (id,owner_id,quiz_question_id,submitted_answer,is_correct,submitted_at)
                        VALUES (:id,:owner,:question,CAST(:answer AS jsonb),:correct,:now)
                        """).param("id", attemptId).param("owner", ownerId).param("question", questionId)
                .param("answer", submittedAnswer(submitted)).param("correct", grading.correct())
                .param("now", Timestamp.from(now)).update();
        Progress progress = updateProgress(ownerId, question, grading.correct(), now);
        return new AttemptResult(attemptId, grading.correct(), grading.answer(), question.explanation(), progress);
    }

    private Progress updateProgress(UUID ownerId, StoredQuestion question, boolean correct, Instant now) {
        return jdbc.sql("""
                        INSERT INTO progress_status
                            (id,owner_id,course_id,session_id,quiz_question_id,state,correct_count,
                             incorrect_count,last_attempt_at,updated_at)
                        VALUES (:id,:owner,:course,:session,NULL,:state,:correct,:incorrect,:now,:now)
                        ON CONFLICT (owner_id,session_id) WHERE quiz_question_id IS NULL DO UPDATE SET
                            state=CASE WHEN progress_status.incorrect_count + EXCLUDED.incorrect_count > 0
                                THEN 'needs_restudy' ELSE 'in_progress' END,
                            correct_count=progress_status.correct_count + EXCLUDED.correct_count,
                            incorrect_count=progress_status.incorrect_count + EXCLUDED.incorrect_count,
                            last_attempt_at=EXCLUDED.last_attempt_at,updated_at=EXCLUDED.updated_at
                        RETURNING correct_count,incorrect_count,last_attempt_at,updated_at
                        """).param("id", UUID.randomUUID()).param("owner", ownerId)
                .param("course", question.courseId()).param("session", question.sessionId())
                .param("state", correct ? "in_progress" : "needs_restudy")
                .param("correct", correct ? 1 : 0).param("incorrect", correct ? 0 : 1)
                .param("now", Timestamp.from(now)).query((row, ignored) -> new Progress(question.sessionId(),
                        row.getInt("correct_count"), row.getInt("incorrect_count"),
                        row.getTimestamp("last_attempt_at").toInstant(),
                        row.getTimestamp("updated_at").toInstant())).single();
    }

    private Grading grade(StoredQuestion question, JsonNode submitted) {
        JsonNode expected = question.answer().path("value");
        if (question.type().equals("true_false")) {
            if (!submitted.isBoolean()) throw validation();
            boolean expectedValue = expected.isBoolean() ? expected.booleanValue()
                    : expected.isTextual() && expected.asText().equalsIgnoreCase("true") ? true
                    : expected.isTextual() && expected.asText().equalsIgnoreCase("false") ? false
                    : invalidStoredAnswer();
            return new Grading(submitted.booleanValue() == expectedValue, answer(question, expectedValue));
        }
        JsonNode options = question.question().path("options");
        if (!question.type().equals("multiple_choice") || !fourTextOptions(options)
                || !submitted.isIntegralNumber() || !submitted.canConvertToInt()
                || submitted.intValue() < 0 || submitted.intValue() > 3) {
            throw validation();
        }
        int expectedIndex = expectedIndex(expected, options);
        return new Grading(submitted.intValue() == expectedIndex, answer(question, expectedIndex));
    }

    private int expectedIndex(JsonNode expected, JsonNode options) {
        if (expected.isIntegralNumber() && expected.canConvertToInt()
                && expected.intValue() >= 0 && expected.intValue() <= 3) return expected.intValue();
        if (expected.isTextual()) {
            for (int index = 0; index < options.size(); index++) {
                if (expected.asText().equals(options.get(index).asText())) return index;
            }
            if (expected.asText().matches("[0-3]")) return Integer.parseInt(expected.asText());
        }
        return invalidStoredAnswer();
    }

    private JsonNode answer(StoredQuestion question, Object value) {
        var answer = question.answer().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) answer).set("value", json.valueToTree(value));
        return answer;
    }

    private static <T> T invalidStoredAnswer() {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QUIZ_INVALID", "Stored quiz answer is invalid.");
    }

    private QuizQuestion publicQuestion(UUID id, String type, JsonNode question) {
        JsonNode options = question.path("options");
        if (!question.path("text").isTextual() || question.path("text").asText().isBlank()
                || !question.path("sourceRefs").isArray() || question.path("sourceRefs").isEmpty()
                || (type.equals("multiple_choice") && !fourTextOptions(options))) {
            throw validation();
        }
        return new QuizQuestion(id, type, question.path("text").asText(),
                type.equals("multiple_choice") ? options : null, question.path("sourceRefs"));
    }

    private String submittedAnswer(JsonNode answer) {
        try {
            return json.writeValueAsString(json.createObjectNode().set("value", answer));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean fourTextOptions(JsonNode options) {
        if (!options.isArray() || options.size() != 4) return false;
        for (JsonNode option : options) {
            if (!option.isTextual() || option.asText().isBlank()) return false;
        }
        return true;
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored quiz JSON is invalid.", exception);
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND", "Quiz question not found.");
    }

    private static ApiException validation() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Invalid quiz answer.");
    }

    record QuizQuestion(UUID id, String type, String prompt,
                        @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode options, JsonNode sourceRefs) {}
    record AttemptResult(UUID attemptId, boolean isCorrect, JsonNode answer,
                         JsonNode explanation, Progress progress) {}
    record Progress(UUID sessionId, int correctCount, int incorrectCount,
                    Instant lastAttemptAt, Instant updatedAt) {}
    private record StoredQuestion(UUID courseId, UUID sessionId, String type, JsonNode question,
                                  JsonNode answer, JsonNode explanation) {}
    private record Grading(boolean correct, JsonNode answer) {}
}
