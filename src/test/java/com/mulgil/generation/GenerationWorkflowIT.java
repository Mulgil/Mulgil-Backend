package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GenerationTestFakes.class)
class GenerationWorkflowIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mulgil.demo.cache-enabled", () -> false);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ContentIndexingService indexing;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired List<JobCompletionListener> listeners;
    @Autowired FakeGenerationModel model;
    private final HttpClient http = HttpClient.newHttpClient();

    String token;
    UUID owner;
    UUID course;
    UUID session;
    GenerationSourceFixtures sources;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("DELETE FROM users").update();
        model.valid = true;
        token = login("generation-owner-" + UUID.randomUUID());
        owner = jdbc.sql("SELECT id FROM users").query(UUID.class).single();
        course = UUID.fromString(ok(send("POST", "/api/v1/courses", Map.of("name", "Generation")), 201)
                .path("id").asText());
        session = UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/sessions", Map.of(
                "sessionNumber", 1, "title", "Sources", "sessionDate", "2026-09-01")), 201)
                .path("id").asText());
        sources = new GenerationSourceFixtures(jdbc, indexing, owner, course, session);
    }

    @Test
    void enqueuesOnceAfterEverySourceIsIndexed_andPublishesSourceValidatedArtifacts() throws Exception {
        sources.addReviewNote("first source", 0);
        sources.addReviewNote("second source", 1);

        runOne("chunk_embed");
        assertThat(jobCount("review_generate")).isZero();
        runOne("chunk_embed");
        assertThat(jobCount("review_generate")).isOne();
        runOne("review_generate");

        JsonNode response = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(response.path("summary").path("items").get(0).path("sourceRefs")).isNotEmpty();
        assertThat(response.path("mindmap").path("nodes").get(0).path("sourceRefs")).isNotEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_questions WHERE session_id=:session AND status='succeeded'")
                .param("session", session).query(Integer.class).single()).isOne();

        runCompletionReplay();
        assertThat(jobCount("review_generate")).isOne();
        System.out.println("GENERATION_WORKFLOW scenario=staggered_sources observable=zero_then_one_job_valid_http_and_practice_rows result=PASS");
    }

    @Test
    void generatesPreviewSummaryAndMindmap_fromIndexedPreviewPdf() throws Exception {
        sources.addPreviewMaterial("preview source");
        runOne("chunk_embed");
        assertThat(jobCount("preview_generate")).isOne();
        runOne("preview_generate");

        JsonNode response = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=preview", null), 200);
        assertThat(response.path("summary").path("type").asText()).isEqualTo("preview");
        assertThat(response.path("mindmap").path("nodes")).hasSize(1);
        System.out.println("GENERATION_WORKFLOW scenario=preview_generation observable=indexed_pdf_publishes_summary_and_mindmap result=PASS");
    }

    @Test
    void preservesPriorGoodResult_whenProviderReturnsMissingReferences() throws Exception {
        sources.addReviewNote("valid source", 0);
        runOne("chunk_embed");
        runOne("review_generate");
        String prior = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200)
                .path("summary").path("id").asText();

        model.valid = false;
        sources.addReviewNote("new source", 1);
        runOne("chunk_embed");
        runOne("review_generate");

        JsonNode after = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(after.path("summary").path("id").asText()).isEqualTo(prior);
        assertThat(jdbc.sql("SELECT error_code FROM ai_jobs WHERE job_type='review_generate' ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("INVALID_SOURCE_REFERENCES");
        System.out.println("GENERATION_WORKFLOW scenario=malformed_references observable=failed_job_prior_success_preserved result=PASS");
    }

    @Test
    void rejectsInsufficientSessionAndPredictedExamSources_throughDocumentedApis() throws Exception {
        error(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null),
                409, "INSUFFICIENT_SOURCE_DATA");
        sources.addReviewNote("indexed normal exam source", 0);
        runOne("chunk_embed");
        UUID exam = UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/exams", Map.of(
                "title", "Midterm", "examAt", "2026-10-01T00:00:00Z", "sessionIds", List.of(session))), 201)
                .path("id").asText());
        UUID otherExam = UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/exams", Map.of(
                "title", "Other", "examAt", "2026-11-01T00:00:00Z", "sessionIds", List.of(session))), 201)
                .path("id").asText());
        sources.addPastExam(otherExam, "other exam source");
        runOne("chunk_embed");
        error(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate", Map.of()),
                409, "INSUFFICIENT_SOURCE_DATA");
        System.out.println("GENERATION_WORKFLOW scenario=insufficient_sources observable=session_get_409_predicted_post_409 result=PASS");
    }

    @Test
    void waitsForRelevantPdfJob_whenItsChunkIsAlreadyIndexed() throws Exception {
        UUID material = sources.addPreviewMaterial("partially processed preview");
        jobs.enqueue(JobQueue.EnqueueRequest.material("pdf_extract", owner, course, session, material, 1,
                ContentIndexingService.sha256("partially processed preview"), "pdfbox", "pdfbox-3", "none"));

        runOne("chunk_embed");
        assertThat(jobCount("preview_generate")).isZero();
        jdbc.sql("UPDATE ai_jobs SET status='succeeded' WHERE material_id=:material AND job_type='pdf_extract'")
                .param("material", material).update();
        runCompletionReplay();

        assertThat(jobCount("preview_generate")).isOne();
        System.out.println("GENERATION_WORKFLOW scenario=partial_pdf observable=queued_pdf_blocks_generation_until_terminal_success result=PASS");
    }

    @Test
    void generatesExamSummaryAndPredictedQuiz_fromSelectedSessionsAndAttachedPastExam() throws Exception {
        sources.addReviewNote("selected session source", 0);
        runOne("chunk_embed");
        UUID exam = createExam();

        JsonNode summaryJob = ok(send("POST", "/api/v1/exams/" + exam + "/summary/generate", Map.of()), 202);
        assertThat(summaryJob.path("status").asText()).isEqualTo("queued");
        runOne("exam_summary_generate");
        assertThat(jdbc.sql("SELECT count(*) FROM summaries WHERE exam_id=:exam AND status='succeeded'")
                .param("exam", exam).query(Integer.class).single()).isOne();

        sources.addPastExam(exam, "past exam source");
        runOne("chunk_embed");
        JsonNode quizJob = ok(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate", Map.of()), 202);
        assertThat(quizJob.path("status").asText()).isEqualTo("queued");
        runOne("exam_quiz_generate");
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_questions WHERE exam_id=:exam "
                        + "AND quiz_scope='past_exam_based' AND status='succeeded'")
                .param("exam", exam).query(Integer.class).single()).isOne();
        System.out.println("GENERATION_WORKFLOW scenario=exam_generation observable=documented_posts_202_summary_and_predicted_rows result=PASS");
    }

    @Test
    void keepsExamGenerationIdempotentWhenOutputCacheIsDisabled_withoutCollidingAcrossExams() throws Exception {
        sources.addReviewNote("shared selected source", 0);
        runOne("chunk_embed");
        UUID firstExam = createExam();
        UUID secondExam = createExam();

        JsonNode first = ok(send("POST", "/api/v1/exams/" + firstExam + "/summary/generate", Map.of()), 202);
        JsonNode second = ok(send("POST", "/api/v1/exams/" + secondExam + "/summary/generate", Map.of()), 202);
        JsonNode retry = ok(send("POST", "/api/v1/exams/" + firstExam + "/summary/generate", Map.of()), 202);

        assertThat(second.path("jobId").asText()).isNotEqualTo(first.path("jobId").asText());
        assertThat(retry.path("jobId").asText()).isEqualTo(first.path("jobId").asText());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type='exam_summary_generate'")
                .query(Integer.class).single()).isEqualTo(2);
        System.out.println("GENERATION_WORKFLOW scenario=exam_idempotency observable=distinct_exam_jobs_same_exam_retry result=PASS");
    }

    private UUID createExam() throws Exception {
        return UUID.fromString(ok(send("POST", "/api/v1/courses/" + course + "/exams", Map.of(
                "title", "Midterm", "examAt", "2026-10-01T00:00:00Z", "sessionIds", List.of(session))), 201)
                .path("id").asText());
    }

    private void runOne(String type) throws Exception {
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
        JobQueue.ClaimedJob job = jobs.claim("generation-it-" + UUID.randomUUID(), Set.of(type));
        assertThat(job).as("queued " + type).isNotNull();
        try {
            assertThat(jobs.complete(job, handler.handle(job))).isTrue();
        } catch (JobHandler.JobExecutionException exception) {
            jobs.fail(job, exception.code(), exception.getMessage(), exception.retryable());
        }
    }

    private void runCompletionReplay() {
        JobQueue.AiJob embedded = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='chunk_embed' ORDER BY created_at LIMIT 1")
                .query((row, ignored) -> jobs.get(owner, row.getObject("id", UUID.class))).single();
        JobQueue.CompletionEvent replay = new JobQueue.CompletionEvent(embedded.id(), embedded.type(),
                embedded.ownerId(), embedded.courseId(), embedded.sessionId(), embedded.materialId(),
                embedded.examResourceId(), embedded.noteId(), embedded.recordingId(), embedded.examId(),
                embedded.inputVersion(), embedded.sourceHash());
        listeners.forEach(listener -> listener.onCompleted(replay));
    }

    private int jobCount(String type) {
        return jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type")
                .param("type", type).query(Integer.class).single();
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        return ok(send("POST", "/api/v1/auth/oauth/google", Map.of("idToken", fake)), 200)
                .path("accessToken").asText();
    }

    private HttpResult send(String method, String path, Object body) throws Exception {
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
