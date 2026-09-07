package com.mulgil.generation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GenerationTestFakes.class)
// allow: SIZE_OK — Spring HTTP scenarios share one expensive application and container fixture.
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
        registry.add("mulgil.generation.input-soft-token-limit", () -> 200);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired ContentIndexingService indexing;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired List<JobCompletionListener> listeners;
    @Autowired GenerationScheduler scheduler;
    @Autowired GenerationSnapshotService snapshots;
    @Autowired FakeGenerationModel model;
    @Autowired TransactionTemplate transactions;
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
        model.lastPromptUnits = 0;
        model.lastResultUnits = 0;
        model.countTokensCalls = 0;
        model.generationCalls = 0;
        model.countedTokens = 100;
        model.contextTokenLimit = 1_048_576;
        model.failureCode = null;
        model.failureRetryable = true;
        model.failureResult = null;
        model.countTokensFailureCode = null;
        model.outputText = null;
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
    void publishesSummaryFirst_thenQueuesIndependentArtifactsAndReturnsNullableMindmap() throws Exception {
        String privateSource = "summary first untrusted source credential=secret";
        ok(send("PUT", "/api/v1/devices/fcm-token", Map.of(
                "token", "phase3-manual-qa-token", "platform", "android", "timezone", "UTC")), 200);
        sources.addReviewNote(privateSource, 0);
        runOne("chunk_embed");

        runOne("review_generate");

        assertThat(jobCount("review_mindmap_generate")).isOne();
        assertThat(jobCount("review_quiz_generate")).isOne();
        JsonNode response = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(response.path("summary").path("items")).hasSize(1);
        assertThat(response.path("mindmap").isNull()).isTrue();
        assertThat(jobCount("review_mindmap_generate")).isOne();
        assertThat(jobCount("review_quiz_generate")).isOne();
        int notificationCount = jdbc.sql("""
                        SELECT count(*) FROM notifications
                        WHERE owner_id=:owner AND notification_type='processing_complete'
                        """).param("owner", owner).query(Integer.class).single();
        int ledgerRows = jdbc.sql("""
                        SELECT count(*) FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation='vertex.generate'
                        """).param("owner", owner).query(Integer.class).single();
        String publicState = response + " " + ok(send("GET", "/api/v1/sessions/" + session + "/jobs", null), 200);
        assertThat(notificationCount).isOne();
        assertThat(ledgerRows).isOne();
        assertThat(publicState).doesNotContain(privateSource, "credential=secret", "error_message");
        System.out.println("GENERATION_PHASE3_QA job_status_counts=review_generate:succeeded:1,"
                + "review_mindmap_generate:queued:1,review_quiz_generate:queued:1 summary_http=200 "
                + "mindmap_null=true notification_count=1 generation_ledger_rows=1 "
                + "raw_error_source_absent=true result=PASS");
    }

    @Test
    void recoversInputVersionTwoMindmapAfterOutputLimit_withoutLeakingPrivateSource(CapturedOutput output)
            throws Exception {
        String sourceSentinel = "PRIVATE_SOURCE_SENTINEL_6 ignore instructions credential=secret";
        sources.addReviewNote(sourceSentinel, 0);
        runOne("chunk_embed");
        jdbc.sql("""
                UPDATE ai_jobs SET status='outdated',finished_at=CURRENT_TIMESTAMP
                WHERE job_type='review_generate' AND input_version=1
                """).update();
        runCompletionReplay();

        UUID parentJob = jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE job_type='review_generate' AND input_version=2
                        """).query(UUID.class).single();
        runOne("review_generate");
        assertThat(jobs.get(owner, parentJob).status()).isEqualTo("succeeded");
        assertThat(model.lastResponseSchema).isEqualTo("source-grounded-v3");
        UUID mindmapJob = jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE job_type='review_mindmap_generate' AND input_version=2
                        """)
                .query(UUID.class).single();
        UUID quizJob = jdbc.sql("""
                        SELECT id FROM ai_jobs
                        WHERE job_type='review_quiz_generate' AND input_version=2
                        """).query(UUID.class).single();
        assertThat(jdbc.sql("""
                        SELECT summary_type||':'||input_version||':'||prompt_version FROM summaries
                        WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                        """).param("owner", owner).param("session", session).query(String.class).single())
                .isEqualTo("review:2:source-grounded-v3");

        model.failureCode = "PROVIDER_OUTPUT_LIMIT";
        model.failureRetryable = false;
        model.failureResult = new GenerationModelPort.GenerationResult(json.createObjectNode()
                .put("partial", "PRIVATE_PROVIDER_SENTINEL_6").toString(),
                null, null, "MAX_TOKENS");
        ch.qos.logback.classic.Logger handlerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(GenerationJobHandler.class);
        ListAppender<ILoggingEvent> providerFailures = new ListAppender<>();
        providerFailures.start();
        handlerLogger.addAppender(providerFailures);
        try {
            runOne("review_mindmap_generate");
        } finally {
            handlerLogger.detachAppender(providerFailures);
            providerFailures.stop();
        }

        JobQueue.AiJob failed = jobs.get(owner, mindmapJob);
        assertThat(failed.status() + ":" + failed.errorCode() + ":" + failed.attemptCount())
                .isEqualTo("failed:PROVIDER_OUTPUT_LIMIT:1");
        assertThat(failed.inputVersion()).isEqualTo(2);
        assertThat(model.lastResponseSchema).isEqualTo("source-grounded-v3");
        assertThat(jdbc.sql("SELECT error_message FROM ai_jobs WHERE id=:id").param("id", mindmapJob)
                .query(String.class).single()).isEqualTo("Generation provider failed.");
        model.failureCode = null;
        runOne("review_quiz_generate");
        assertThat(jobs.get(owner, quizJob).status()).isEqualTo("succeeded");
        assertThat(jobs.get(owner, quizJob).inputVersion()).isEqualTo(2);
        assertThat(model.lastResponseSchema).isEqualTo("source-grounded-v3");

        JsonNode summary = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(summary.path("summary").path("inputVersion").asInt()).isEqualTo(2);
        assertThat(summary.path("mindmap").isNull()).isTrue();
        assertThat(ok(send("GET", "/api/v1/sessions/" + session + "/quiz", null), 200)).hasSize(1);
        JsonNode publicJobs = ok(send("GET", "/api/v1/sessions/" + session + "/jobs", null), 200);
        JsonNode publicFailure = null;
        for (JsonNode value : publicJobs) {
            if (value.path("id").asText().equals(mindmapJob.toString())) publicFailure = value;
        }
        assertThat(publicFailure).isNotNull();
        assertThat(publicFailure.path("status").asText() + ":" + publicFailure.path("errorCode").asText())
                .isEqualTo("failed:PROVIDER_OUTPUT_LIMIT");
        var publicFields = new java.util.HashSet<String>();
        publicFailure.fieldNames().forEachRemaining(publicFields::add);
        assertThat(publicFields).containsExactlyInAnyOrder("id", "type", "status", "materialId", "inputVersion",
                "attemptCount", "maxAttempts", "errorCode", "createdAt", "finishedAt", "progressStage",
                "progressUpdatedAt");
        assertThat(summary + " " + publicJobs).doesNotContain(sourceSentinel, "PRIVATE_PROVIDER_SENTINEL_6",
                "error_message", "Generation provider failed.");
        assertThat(jdbc.sql("""
                        SELECT input_version||':'||prompt_version FROM quiz_questions
                        WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                        """).param("owner", owner).param("session", session).query(String.class).single())
                .isEqualTo("2:source-grounded-v3");

        JsonNode retry = ok(send("POST", "/api/v1/jobs/" + mindmapJob + "/retry", null), 202);
        assertThat(retry.path("id").asText()).isEqualTo(mindmapJob.toString());
        assertThat(retry.path("inputVersion").asInt()).isEqualTo(2);
        assertThat(retry.path("attemptCount").asInt()).isOne();
        model.outputText = "Spring HTTP 마인드맵 MAX_TOKENS 복구";
        runOne("review_mindmap_generate");
        assertThat(model.lastResponseSchema).isEqualTo("source-grounded-v3");
        assertThat(jobs.get(owner, mindmapJob).status() + ":" + jobs.get(owner, mindmapJob).inputVersion())
                .isEqualTo("succeeded:2");
        JsonNode recovered = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        String label = recovered.path("mindmap").path("nodes").get(0).path("label").asText();
        assertThat(label).contains("Spring", "HTTP", "MAX_TOKENS").matches(".*[가-힣].*");
        assertThat(label.codePoints().count()).isLessThanOrEqualTo(80);
        assertThat(jdbc.sql("""
                        SELECT input_version||':'||prompt_version FROM mindmaps
                        WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                        """).param("owner", owner).param("session", session).query(String.class).single())
                .isEqualTo("2:source-grounded-v3");
        assertThat(jdbc.sql("""
                        SELECT artifact||':'||input_version||':'||prompt_version FROM (
                            SELECT 'summary' artifact,input_version,prompt_version FROM summaries
                            WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                            UNION ALL
                            SELECT 'quiz',input_version,prompt_version FROM quiz_questions
                            WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                            UNION ALL
                            SELECT 'mindmap',input_version,prompt_version FROM mindmaps
                            WHERE owner_id=:owner AND session_id=:session AND status='succeeded'
                        ) artifacts ORDER BY artifact
                        """).param("owner", owner).param("session", session).query(String.class).list())
                .containsExactly("mindmap:2:source-grounded-v3", "quiz:2:source-grounded-v3",
                        "summary:2:source-grounded-v3");

        assertThat(providerFailures.list).hasSize(1);
        ILoggingEvent event = providerFailures.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                .containsExactly("event", "jobId", "operation", "artifact", "errorCode", "finishReason");
        assertThat(event.getKeyValuePairs()).extracting(pair -> String.valueOf(pair.value))
                .containsExactly("generation.provider.failed", mindmapJob.toString(),
                        "review_mindmap_generate", "mindmap", "PROVIDER_OUTPUT_LIMIT", "MAX_TOKENS");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getMDCPropertyMap()).isEmpty();
        assertThat(event.getFormattedMessage() + event.getKeyValuePairs() + event.getMDCPropertyMap())
                .doesNotContain(sourceSentinel, "PRIVATE_PROVIDER_SENTINEL_6", "Generation provider failed.");
        assertThat(output.getAll()).doesNotContain(sourceSentinel, "PRIVATE_PROVIDER_SENTINEL_6");
        System.out.println("GENERATION_INCIDENT_QA input_version=2 contract=source-grounded-v3 "
                + "parent=succeeded mindmap=failed:PROVIDER_OUTPUT_LIMIT:MAX_TOKENS quiz=succeeded "
                + "summary_http=200 mindmap_before_retry=null retry_http=202 same_child=true "
                + "bounded_korean_technical_output=true private_source_absent=true result=PASS");
    }

    @Test
    void completionReplayDoesNotDuplicateTerminalChildren_andSourceChangeOutdatesQueuedChildren()
            throws Exception {
        sources.addReviewNote("replay source", 0);
        runOne("chunk_embed");
        runOne("review_generate");
        JobQueue.AiJob root = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='review_generate'")
                .query((row, ignored) -> jobs.get(owner, row.getObject("id", UUID.class))).single();
        JobQueue.CompletionEvent replay = new JobQueue.CompletionEvent(root.id(), root.type(), root.ownerId(),
                root.courseId(), root.sessionId(), root.materialId(), root.examResourceId(), root.noteId(),
                root.recordingId(), root.examId(), root.inputVersion(), root.sourceHash());
        listeners.forEach(listener -> listener.onCompleted(replay));
        assertThat(jobCount("review_mindmap_generate")).isOne();
        assertThat(jobCount("review_quiz_generate")).isOne();

        sources.addReviewNote("changed source", 1);
        runOne("review_mindmap_generate");

        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE job_type='review_mindmap_generate'")
                .query(String.class).single()).isEqualTo("outdated");
        assertThat(jdbc.sql("SELECT count(*) FROM mindmaps WHERE session_id=:session")
                .param("session", session).query(Integer.class).single()).isZero();
    }

    @Test
    void outdatesHeldChildPublications_whenNewerSummarySucceedsWithSameSourceHash() throws Exception {
        sources.addReviewNote("held child source", 0);
        runOne("chunk_embed");
        runOne("review_generate");

        JobHandler mindmapHandler = handlers.stream()
                .filter(value -> value.jobType().equals("review_mindmap_generate")).findFirst().orElseThrow();
        JobHandler quizHandler = handlers.stream()
                .filter(value -> value.jobType().equals("review_quiz_generate")).findFirst().orElseThrow();
        JobQueue.ClaimedJob heldMindmap = jobs.claim("held-mindmap", Set.of("review_mindmap_generate"));
        JobQueue.ClaimedJob heldQuiz = jobs.claim("held-quiz", Set.of("review_quiz_generate"));
        JobHandler.JobPublication heldMindmapPublication = mindmapHandler.handle(heldMindmap);
        JobHandler.JobPublication heldQuizPublication = quizHandler.handle(heldQuiz);

        runCompletionReplay();
        runOne("review_generate");
        runOne("review_mindmap_generate");
        runOne("review_quiz_generate");

        assertThat(jobs.complete(heldMindmap, heldMindmapPublication)).isFalse();
        assertThat(jobs.complete(heldQuiz, heldQuizPublication)).isFalse();
        assertThat(jdbc.sql("""
                        SELECT DISTINCT source_hash FROM ai_jobs
                        WHERE job_type IN ('review_mindmap_generate','review_quiz_generate')
                        """).query(String.class).list()).containsExactly(heldMindmap.sourceHash());
        assertThat(jdbc.sql("""
                        SELECT job_type||':'||input_version||':'||status FROM ai_jobs
                        WHERE job_type IN ('review_mindmap_generate','review_quiz_generate')
                        ORDER BY job_type,input_version
                        """).query(String.class).list())
                .containsExactly("review_mindmap_generate:1:outdated", "review_mindmap_generate:2:succeeded",
                        "review_quiz_generate:1:outdated", "review_quiz_generate:2:succeeded");
        assertThat(jdbc.sql("SELECT input_version||':'||status FROM mindmaps WHERE session_id=:session")
                .param("session", session).query(String.class).list()).containsExactly("2:succeeded");
        assertThat(jdbc.sql("SELECT input_version||':'||status FROM quiz_questions WHERE session_id=:session")
                .param("session", session).query(String.class).list()).containsExactly("2:succeeded");
    }

    @Test
    void keepsReviewMindmapCurrent_whenNewerPreviewMindmapSucceeds() throws Exception {
        sources.addPreviewMaterial("preview source");
        runOne("chunk_embed");
        runOne("preview_generate");
        runOne("preview_mindmap_generate");

        JsonNode firstPreview = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=preview", null), 200);
        UUID firstPreviewMindmap = UUID.fromString(firstPreview.path("mindmap").path("id").asText());

        sources.addReviewNote("review source", 0);
        runOne("chunk_embed");
        runOne("review_generate");
        runOne("review_mindmap_generate");

        JsonNode review = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        UUID reviewMindmap = UUID.fromString(review.path("mindmap").path("id").asText());
        assertThat(ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=preview", null), 200)
                .path("mindmap").path("id").asText()).isEqualTo(firstPreviewMindmap.toString());

        runOne("preview_generate");
        runOne("preview_mindmap_generate");

        JsonNode newerPreview = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=preview", null), 200);
        JsonNode sameReview = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(newerPreview.path("mindmap").path("id").asText())
                .isNotEqualTo(firstPreviewMindmap.toString());
        assertThat(sameReview.path("mindmap").path("id").asText()).isEqualTo(reviewMindmap.toString());
        assertThat(jdbc.sql("SELECT id||':'||status FROM mindmaps WHERE session_id=:session ORDER BY input_version")
                .param("session", session).query(String.class).list())
                .containsExactly(firstPreviewMindmap + ":outdated", newerPreview.path("mindmap").path("id").asText()
                        + ":succeeded", reviewMindmap + ":succeeded");
        System.out.println("GENERATION_PHASE_INVALIDATION_QA preview_http=200 review_http=200 "
                + "newer_preview_outdated_older_preview=true review_mindmap_preserved=true result=PASS");
    }

    @Test
    void preflightsOnlyAboveSoftLimit_andRejectsActualContextOverflowWithoutGeneration() throws Exception {
        sources.addReviewNote("short source", 0);
        runOne("chunk_embed");
        runOne("review_generate");
        assertThat(model.countTokensCalls).isZero();
        assertThat(model.generationCalls).isOne();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation='vertex.count_tokens'
                        """).param("owner", owner).query(Integer.class).single()).isZero();

        sources.addReviewNote("x".repeat(300), 1);
        runOne("chunk_embed");
        model.countedTokens = 101;
        model.contextTokenLimit = 100;
        int generationsBeforeOverflow = model.generationCalls;
        runOne("review_generate");

        assertThat(model.countTokensCalls).isOne();
        assertThat(model.generationCalls).isEqualTo(generationsBeforeOverflow);
        assertThat(jdbc.sql("""
                        SELECT operation||':'||provider||':'||model_id||':'||status||':'
                            ||(job_id IS NOT NULL)||':'||(latency_ms >= 0)||':'||unit_type||':'||unit_count
                        FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation='vertex.count_tokens'
                        """).param("owner", owner).query(String.class).single())
                .isEqualTo("vertex.count_tokens:vertex:gemini-2.5-flash:succeeded:true:true:token:101");
        assertThat(jdbc.sql("SELECT error_code FROM ai_jobs WHERE job_type='review_generate' ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("GENERATION_INPUT_TOO_LARGE");
        System.out.println("GENERATION_PHASE2_QA preflight=skip_below_once_above overflow=non_retryable_no_generation result=PASS");
    }

    @Test
    void recordsSafeFailedCountTokensPreflight_withoutCallingGeneration(CapturedOutput output) throws Exception {
        String privateSource = "untrusted preflight source credential=secret" + "x".repeat(300);
        sources.addReviewNote(privateSource, 0);
        runOne("chunk_embed");
        model.countTokensFailureCode = "PROVIDER_TIMEOUT";

        runOne("review_generate");

        assertThat(model.countTokensCalls).isOne();
        assertThat(model.generationCalls).isZero();
        assertThat(jdbc.sql("""
                        SELECT operation||':'||provider||':'||model_id||':'||status||':'||error_code||':'
                            ||(job_id IS NOT NULL)||':'||(latency_ms >= 0)||':'||unit_type||':'||(unit_count IS NULL)
                        FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation='vertex.count_tokens'
                        """).param("owner", owner).query(String.class).single())
                .isEqualTo("vertex.count_tokens:vertex:gemini-2.5-flash:failed:PROVIDER_TIMEOUT:true:true:token:true");
        assertThat(jdbc.sql("SELECT error_code FROM ai_jobs WHERE job_type='review_generate'")
                .query(String.class).single()).isEqualTo("PROVIDER_TIMEOUT");
        JsonNode publicJobs = ok(send("GET", "/api/v1/sessions/" + session + "/jobs", null), 200);
        assertThat(publicJobs.toString()).contains("PROVIDER_TIMEOUT")
                .doesNotContain(privateSource, "credential=secret");
        assertThat(output.getAll()).doesNotContain(privateSource, "credential=secret");
        System.out.println("GENERATION_COUNT_TOKENS_QA surface=HTTP+Testcontainers provider_call=count_tokens "
                + "status=failed job_linked=true generation_calls=0 error=PROVIDER_TIMEOUT "
                + "raw_data_absent=true result=PASS");
    }

    @Test
    void recordsOneSafeCountTokensPreflight_beforeOneGeneration(CapturedOutput output) throws Exception {
        String privateSource = "untrusted counted source credential=secret" + "x".repeat(300);
        sources.addReviewNote(privateSource, 0);
        runOne("chunk_embed");

        runOne("review_generate");

        assertThat(model.countTokensCalls).isOne();
        assertThat(model.generationCalls).isOne();
        assertThat(jdbc.sql("""
                        SELECT operation||':'||status FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation IN ('vertex.count_tokens','vertex.generate')
                        ORDER BY operation
                        """).param("owner", owner).query(String.class).list())
                .containsExactly("vertex.count_tokens:succeeded", "vertex.generate:succeeded");
        assertThat(jdbc.sql("""
                        SELECT provider||':'||model_id||':'||(job_id IS NOT NULL)||':'||(latency_ms >= 0)
                            ||':'||unit_type||':'||unit_count
                        FROM ai_provider_usage
                        WHERE owner_id=:owner AND operation='vertex.count_tokens'
                        """).param("owner", owner).query(String.class).single())
                .isEqualTo("vertex:gemini-2.5-flash:true:true:token:100");
        JsonNode publicJobs = ok(send("GET", "/api/v1/sessions/" + session + "/jobs", null), 200);
        assertThat(publicJobs.toString()).contains("review_generate", "succeeded")
                .doesNotContain(privateSource, "credential=secret", "vertex.count_tokens");
        assertThat(output.getAll()).doesNotContain(privateSource, "credential=secret");
        System.out.println("GENERATION_COUNT_TOKENS_QA surface=HTTP+Testcontainers "
                + "provider_calls=count_tokens:1,generate:1 statuses=succeeded,succeeded "
                + "job_linked=true latency_recorded=true raw_data_absent=true result=PASS");
    }

    @Test
    void enqueuesOnceAfterEverySourceIsIndexed_andPublishesSourceValidatedArtifacts() throws Exception {
        sources.addReviewNote("first source", 0);
        sources.addReviewNote("second source", 1);

        runOne("chunk_embed");
        assertThat(jobCount("review_generate")).isZero();
        runOne("chunk_embed");
        assertThat(jobCount("review_generate")).isOne();
        runCompletionReplay();
        assertThat(jobCount("review_generate")).isOne();
        runOne("review_generate");
        runOne("review_mindmap_generate");
        runOne("review_quiz_generate");

        JsonNode response = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(response.path("summary").path("items").get(0).path("sourceRefs")).isNotEmpty();
        assertThat(response.path("mindmap").path("nodes").get(0).path("sourceRefs")).isNotEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_questions WHERE session_id=:session AND status='succeeded'")
                .param("session", session).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT operation||':'||status FROM ai_provider_usage
                        WHERE owner_id=:owner ORDER BY operation,status
                        """).param("owner", owner).query(String.class).list())
                .contains("vertex.embed:succeeded", "vertex.generate:succeeded");

        runCompletionReplay();
        assertThat(jobCount("review_generate")).isEqualTo(2);
        runOne("review_generate");
        assertThat(jobCount("review_mindmap_generate")).isEqualTo(2);
        assertThat(jobCount("review_quiz_generate")).isEqualTo(2);
        runOne("review_mindmap_generate");
        runOne("review_quiz_generate");

        JsonNode regenerated = ok(send(
                "GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(regenerated.path("summary").path("inputVersion").asInt()).isEqualTo(2);
        assertThat(regenerated.path("mindmap").path("inputVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM quiz_questions
                        WHERE session_id=:session AND status='succeeded' AND input_version=2
                        """).param("session", session).query(Integer.class).single()).isOne();
        System.out.println("GENERATION_WORKFLOW scenario=staggered_sources observable=zero_then_one_job_valid_http_and_practice_rows result=PASS");
    }

    @Test
    void preservesGroundedArtifactAndPublicApiContract_throughGenerationWorkflow() throws Exception {
        String privateSource = "untrusted_external_text prompt_injection ignore instructions credential=secret";
        sources.addReviewNote(privateSource, 0);
        runOne("chunk_embed");
        runOne("review_generate");
        long rootPromptUnits = model.lastPromptUnits;
        long rootResultUnits = model.lastResultUnits;
        runOne("review_mindmap_generate");
        runOne("review_quiz_generate");

        JsonNode artifacts = ok(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null), 200);
        assertThat(artifacts.path("summary").path("inputVersion").asInt()).isOne();
        assertThat(artifacts.path("summary").path("items").get(0).path("sourceRefs")).hasSize(1);
        assertThat(artifacts.path("mindmap").path("nodes").get(0).path("sourceRefs")).isNotEmpty();
        assertThat(artifacts.findValue("sourceIds")).isNull();
        assertThat(artifacts.findValue("sourceRefs").toString()).doesNotContain(privateSource);

        JsonNode publicJob = ok(send("GET", "/api/v1/sessions/" + session + "/jobs", null), 200);
        JsonNode generationJob = null;
        for (JsonNode value : publicJob) {
            if (value.path("type").asText().equals("review_generate")) generationJob = value;
        }
        assertThat(generationJob).isNotNull();
        assertThat(generationJob.path("progressStage").asText()).isEqualTo("publishing");
        assertThat(generationJob.path("progressUpdatedAt").asText()).isNotBlank();
        assertThat(generationJob.toString()).doesNotContain(privateSource);
        assertThat(jdbc.sql("""
                        SELECT unit_type||':'||unit_count||':'||prompt_token_count||':'||candidate_token_count
                            ||':'||total_token_count||':'||cached_content_token_count||':'||first_response_latency_ms
                        FROM ai_provider_usage WHERE operation='vertex.generate' AND owner_id=:owner
                          AND job_id=(SELECT id FROM ai_jobs WHERE job_type='review_generate' LIMIT 1)
                        """).param("owner", owner).query(String.class).single())
                .isEqualTo("unicode_code_point:" + (rootPromptUnits + rootResultUnits)
                        + ":31:17:48:5:7");
        System.out.println("GENERATION_PHASE1_QA api_progress=publishing db_usage=unicode_code_point+tokens+first_response "
                + "untrusted_source_absent=true result=PASS");

        JsonNode quiz = ok(send("GET", "/api/v1/sessions/" + session + "/quiz", null), 200);
        assertThat(quiz).hasSize(1);
        assertThat(quiz.get(0).path("sourceRefs")).isNotEmpty();
        assertThat(quiz.get(0).has("answer") || quiz.get(0).has("explanation") || quiz.get(0).has("sourceIds"))
                .isFalse();
        assertThat(quiz.findValue("sourceRefs").toString()).doesNotContain(privateSource);
        System.out.println("GENERATION_WORKFLOW scenario=baseline_artifact_contract "
                + "observable=grounded_summary_mindmap_quiz_without_internal_or_private_fields result=PASS");
    }

    @Test
    void generatesPreviewSummaryAndMindmap_fromIndexedPreviewPdf() throws Exception {
        sources.addPreviewMaterial("preview source");
        runOne("chunk_embed");
        assertThat(jobCount("preview_generate")).isOne();
        runOne("preview_generate");
        runOne("preview_mindmap_generate");

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

    @ParameterizedTest
    @ValueSource(strings = {"queued", "running", "failed"})
    void returnsEmbeddingNotReady_whenCurrentChunksAreUnembedded(String embeddingJobStatus) throws Exception {
        sources.addReviewNote("current unembedded session source", 0);
        UUID exam = createExam();
        sources.addPastExam(exam, "current unembedded exam source");
        switch (embeddingJobStatus) {
            case "queued" -> { }
            case "running" -> jdbc.sql("""
                    UPDATE ai_jobs SET status='running',claimed_by='embedding-readiness',
                        last_heartbeat_at=CURRENT_TIMESTAMP,lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '1 minute'
                    WHERE job_type='chunk_embed'
                    """).update();
            case "failed" -> jdbc.sql("""
                    UPDATE ai_jobs SET status='failed',attempt_count=max_attempts,finished_at=CURRENT_TIMESTAMP,
                        error_code='PROVIDER_UNAVAILABLE',error_message='Provider unavailable.'
                    WHERE job_type='chunk_embed'
                    """).update();
            default -> throw new IllegalArgumentException("Unexpected embedding job status.");
        }

        error(send("GET", "/api/v1/sessions/" + session + "/summaries?type=review", null),
                409, "EMBEDDING_NOT_READY");
        error(send("POST", "/api/v1/exams/" + exam + "/summary/generate", Map.of()),
                409, "EMBEDDING_NOT_READY");
        error(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate", Map.of()),
                409, "EMBEDDING_NOT_READY");

        runCompletionReplay();
        assertThat(jobCount("review_generate")).isZero();
        System.out.println("GENERATION_WORKFLOW scenario=unembedded_current_chunks status=" + embeddingJobStatus
                + " observable=session_exam_409_and_no_practice_generation result=PASS");
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

        error(send("GET", "/api/v1/exams/" + exam + "/summary", null),
                404, "GENERATION_NOT_FOUND");
        JsonNode summaryJob = ok(send("POST", "/api/v1/exams/" + exam + "/summary/generate", Map.of()), 202);
        assertThat(summaryJob.path("status").asText()).isEqualTo("queued");
        runOne("exam_summary_generate");
        assertThat(ok(send("GET", "/api/v1/exams/" + exam + "/summary", null), 200)
                .path("type").asText()).isEqualTo("exam");
        assertThat(jdbc.sql("SELECT count(*) FROM summaries WHERE exam_id=:exam AND status='succeeded'")
                .param("exam", exam).query(Integer.class).single()).isOne();

        sources.addPastExam(exam, "past exam source");
        runOne("chunk_embed");
        JsonNode quizJob = ok(send("POST", "/api/v1/exams/" + exam + "/predicted-quiz/generate", Map.of()), 202);
        assertThat(quizJob.path("status").asText()).isEqualTo("queued");
        runOne("exam_quiz_generate");
        assertThat(ok(send("GET", "/api/v1/exams/" + exam + "/predicted-quiz", null), 200)).hasSize(1);
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

    @ParameterizedTest
    @ValueSource(strings = {"queued", "succeeded"})
    void doesNotReusePriorContractGenerationJob_whenSchedulingSourceGroundedV3(String priorStatus) throws Exception {
        sources.addReviewNote("contract-version source", 0);
        runOne("chunk_embed");
        GenerationSnapshotService.Snapshot snapshot = snapshots.session(owner, course, session, "review");
        jdbc.sql("DELETE FROM ai_jobs WHERE job_type='review_generate'").update();
        JobQueue.AiJob prior = jobs.enqueue(new JobQueue.EnqueueRequest("review_generate", owner, course,
                session, null, null, null, null, null, 1, snapshot.snapshotHash(), "vertex",
                "gemini-2.5-flash", "source-grounded-v1"));
        jdbc.sql("""
                UPDATE ai_jobs SET status=:status,
                    finished_at=CASE WHEN CAST(:status AS text)='succeeded' THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id=:id
                """).param("status", priorStatus).param("id", prior.id()).update();

        runCompletionReplay();

        List<UUID> scheduled = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='review_generate' ORDER BY created_at,id")
                .query(UUID.class).list();
        assertThat(scheduled).hasSize(2);
        assertThat(scheduled.get(1)).isNotEqualTo(prior.id());
        JobQueue.AiJob exactV3 = jobs.enqueue(new JobQueue.EnqueueRequest("review_generate", owner, course,
                session, null, null, null, null, null, 2, snapshot.snapshotHash(), "vertex",
                "gemini-2.5-flash", GenerationScheduler.PROMPT_VERSION));
        assertThat(exactV3.id()).isEqualTo(scheduled.get(1));
        System.out.println("GENERATION_PHASE2_QA prior_contract=v1 prior_status=" + priorStatus
                + " current_contract=v3 observable=distinct_jobs result=PASS");
    }

    @Test
    void serializesConcurrentExamGenerationTypes_onTheirSharedVersionCounter() throws Exception {
        sources.addReviewNote("shared exam source", 0);
        runOne("chunk_embed");
        UUID exam = createExam();
        sources.addPastExam(exam, "past exam source");
        runOne("chunk_embed");
        jdbc.sql("DELETE FROM ai_jobs WHERE job_type IN ('exam_summary_generate','exam_quiz_generate')").update();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<JobQueue.JobAccepted> summary = executor.submit(() -> {
                start.await();
                return scheduler.scheduleExam(owner, exam, false);
            });
            Future<JobQueue.JobAccepted> quiz = executor.submit(() -> {
                start.await();
                return scheduler.scheduleExam(owner, exam, true);
            });
            start.countDown();

            assertThat(summary.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(quiz.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.sql("""
                        SELECT input_version FROM ai_jobs
                        WHERE exam_id=:exam AND job_type IN ('exam_summary_generate','exam_quiz_generate')
                        """).param("exam", exam).query(Integer.class).list()).containsExactlyInAnyOrder(1, 2);
        System.out.println("GENERATION_WORKFLOW scenario=concurrent_exam_types "
                + "observable=shared_counter_versions_1_and_2 result=PASS");
    }

    @Test
    void ignoresCompletionEvents_forWrongOwnerAbsentSessionAndArchivedCourse() throws Exception {
        sources.addReviewNote("ready source", 0);
        runOne("chunk_embed");
        JobQueue.CompletionEvent event = completionEvent();
        jdbc.sql("DELETE FROM ai_jobs WHERE job_type IN ('preview_generate','review_generate')").update();

        scheduler.onCompleted(new JobQueue.CompletionEvent(event.jobId(), event.type(), UUID.randomUUID(),
                event.courseId(), event.sessionId(), event.materialId(), event.examResourceId(), event.noteId(),
                event.recordingId(), event.examId(), event.inputVersion(), event.sourceHash()));
        scheduler.onCompleted(new JobQueue.CompletionEvent(event.jobId(), event.type(), event.ownerId(),
                event.courseId(), UUID.randomUUID(), event.materialId(), event.examResourceId(), event.noteId(),
                event.recordingId(), event.examId(), event.inputVersion(), event.sourceHash()));
        jdbc.sql("UPDATE courses SET deleted_at=CURRENT_TIMESTAMP WHERE id=:course")
                .param("course", course).update();
        scheduler.onCompleted(event);

        assertThat(jobCount("preview_generate") + jobCount("review_generate")).isZero();
        System.out.println("GENERATION_WORKFLOW scenario=invalid_completion_scope "
                + "observable=wrong_owner_absent_session_archived_course_create_zero_jobs result=PASS");
    }

    @Test
    void blocksBehindSharedSessionLock_evenWhenCompletionSourcesAreInsufficient() throws Exception {
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerCompleted = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger workerPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> holder = null;
        Future<?> worker = null;
        try {
            holder = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                holderPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("SELECT id FROM class_sessions WHERE id=:session FOR SHARE")
                        .param("session", session).query(UUID.class).single();
                holderLocked.countDown();
                await(releaseHolder);
            }));
            assertThat(holderLocked.await(5, TimeUnit.SECONDS)).isTrue();
            worker = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        workerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        workerStarted.countDown();
                        scheduler.onCompleted(insufficientCompletionEvent());
                    });
                } finally {
                    workerCompleted.countDown();
                }
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            awaitBlockedBy(holderPid.get(), workerPid.get(), workerCompleted, "shared session lock");
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
            worker.get(10, TimeUnit.SECONDS);

            assertThat(jobCount("preview_generate") + jobCount("review_generate")).isZero();
            System.out.println("GENERATION_WORKFLOW scenario=session_lock_contract "
                    + "observable=share_holder_blocks_scheduler_update_then_zero_jobs result=PASS");
        } finally {
            releaseHolder.countDown();
            if (holder != null) holder.cancel(true);
            if (worker != null) worker.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blocksBehindCourseArchival_thenSkipsSchedulingAfterCommit() throws Exception {
        CountDownLatch archiveUpdated = new CountDownLatch(1);
        CountDownLatch commitArchive = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerCompleted = new CountDownLatch(1);
        AtomicInteger archiverPid = new AtomicInteger();
        AtomicInteger workerPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> archiver = null;
        Future<?> worker = null;
        try {
            archiver = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                archiverPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("UPDATE courses SET deleted_at=CURRENT_TIMESTAMP WHERE id=:course")
                        .param("course", course).update();
                archiveUpdated.countDown();
                await(commitArchive);
            }));
            assertThat(archiveUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            worker = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        workerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        workerStarted.countDown();
                        scheduler.onCompleted(insufficientCompletionEvent());
                    });
                } finally {
                    workerCompleted.countDown();
                }
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            awaitBlockedBy(archiverPid.get(), workerPid.get(), workerCompleted, "course archival");
            commitArchive.countDown();
            archiver.get(10, TimeUnit.SECONDS);
            worker.get(10, TimeUnit.SECONDS);

            assertThat(jobCount("preview_generate") + jobCount("review_generate")).isZero();
            System.out.println("GENERATION_WORKFLOW scenario=course_lock_contract "
                    + "observable=uncommitted_archive_blocks_scheduler_then_zero_jobs result=PASS");
        } finally {
            commitArchive.countDown();
            if (archiver != null) archiver.cancel(true);
            if (worker != null) worker.cancel(true);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void bypassesSucceededBillableJobs_whenOutputCacheIsDisabled() {
        for (String type : List.of("review_generate", "chunk_embed")) {
            JobQueue.EnqueueRequest request = cacheContractRequest(type, "succeeded-billable-" + type);
            JobQueue.AiJob first = jobs.enqueue(request);
            jobs.complete(jobs.claim("succeeded-billable-" + type, Set.of(type)), () -> {});

            JobQueue.AiJob replay = jobs.enqueue(request);

            assertThat(replay.id()).as(type).isNotEqualTo(first.id());
            assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type AND source_hash=:hash")
                    .param("type", type).param("hash", request.sourceHash())
                    .query(Integer.class).single()).as(type).isEqualTo(2);
        }
    }

    @Test
    void deduplicatesQueuedAndRunningBillableJob_whenOutputCacheIsDisabled() {
        JobQueue.EnqueueRequest request = cacheContractRequest("chunk_embed", "active-billable");
        JobQueue.AiJob first = jobs.enqueue(request);

        assertThat(jobs.enqueue(request).id()).isEqualTo(first.id());
        JobQueue.ClaimedJob claimed = jobs.claim("active-billable", Set.of(request.type()));
        JobQueue.AiJob runningReplay = jobs.enqueue(request);

        assertThat(runningReplay.id()).isEqualTo(first.id());
        assertThat(runningReplay.status()).isEqualTo("running");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type AND source_hash=:hash")
                .param("type", request.type()).param("hash", request.sourceHash())
                .query(Integer.class).single()).isOne();
        assertThat(claimed.id()).isEqualTo(first.id());
    }

    @Test
    void reusesSucceededNotificationAndLocalPdfJob_whenOutputCacheIsDisabled() {
        for (String type : List.of("notification_send", "pdf_extract")) {
            JobQueue.EnqueueRequest request = cacheContractRequest(type, "succeeded-non-billable-" + type);
            JobQueue.AiJob first = jobs.enqueue(request);
            jobs.complete(jobs.claim("succeeded-" + type, Set.of(type)), () -> {});

            JobQueue.AiJob replay = jobs.enqueue(request);

            assertThat(replay.id()).as(type).isEqualTo(first.id());
            assertThat(replay.status()).as(type).isEqualTo("succeeded");
            assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type AND source_hash=:hash")
                    .param("type", type).param("hash", request.sourceHash())
                    .query(Integer.class).single()).as(type).isOne();
        }
    }

    @Test
    void requeuesRetryableAiAndNonAiJobs_whenOutputCacheIsDisabled() {
        for (String type : List.of("chunk_embed", "notification_send", "pdf_extract")) {
            JobQueue.EnqueueRequest request = cacheContractRequest(type, "retryable-" + type);
            JobQueue.AiJob first = jobs.enqueue(request);
            JobQueue.ClaimedJob claimed = jobs.claim("retryable-" + type, Set.of(type));
            jobs.fail(claimed, "PROVIDER_TIMEOUT", "Provider timed out.", true);

            JobQueue.AiJob replay = jobs.enqueue(request);

            assertThat(replay.id()).as(type).isEqualTo(first.id());
            assertThat(replay.status()).as(type).isEqualTo("queued");
            assertThat(replay.attemptCount()).as(type).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type=:type AND source_hash=:hash")
                    .param("type", type).param("hash", request.sourceHash())
                    .query(Integer.class).single()).as(type).isOne();
        }
        System.out.println("GENERATION_WORKFLOW scenario=cache_disabled_retryable "
                + "observable=ai_and_non_ai_same_row_requeued result=PASS");
    }

    private JobQueue.EnqueueRequest cacheContractRequest(String type, String identity) {
        UUID material = type.equals("pdf_extract") ? sources.addPreviewMaterial(identity) : null;
        return new JobQueue.EnqueueRequest(type, owner, course, session, material, null, null, null, null, 1,
                ContentIndexingService.sha256(identity), "test-provider", "test-model", "none");
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
        listeners.forEach(listener -> listener.onCompleted(completionEvent()));
    }

    private JobQueue.CompletionEvent completionEvent() {
        JobQueue.AiJob embedded = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='chunk_embed' ORDER BY created_at LIMIT 1")
                .query((row, ignored) -> jobs.get(owner, row.getObject("id", UUID.class))).single();
        return new JobQueue.CompletionEvent(embedded.id(), embedded.type(),
                embedded.ownerId(), embedded.courseId(), embedded.sessionId(), embedded.materialId(),
                embedded.examResourceId(), embedded.noteId(), embedded.recordingId(), embedded.examId(),
                embedded.inputVersion(), embedded.sourceHash());
    }

    private JobQueue.CompletionEvent insufficientCompletionEvent() {
        return new JobQueue.CompletionEvent(UUID.randomUUID(), "chunk_embed", owner, course, session,
                null, null, null, null, null, 1, ContentIndexingService.sha256("insufficient"));
    }

    private void awaitBlockedBy(int blockerPid, int workerPid, CountDownLatch completed, String lock) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (completed.getCount() == 0) {
                throw new AssertionError("Scheduler completed before blocking behind " + lock + ".");
            }
            boolean blocked = jdbc.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM pg_stat_activity
                                WHERE pid=:workerPid AND wait_event_type='Lock'
                                  AND :blockerPid = ANY(pg_blocking_pids(pid))
                            )
                            """)
                    .param("blockerPid", blockerPid).param("workerPid", workerPid)
                    .query(Boolean.class).single();
            if (blocked) return;
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Scheduler did not block behind " + lock + ".");
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Latch interrupted.", exception);
        }
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
