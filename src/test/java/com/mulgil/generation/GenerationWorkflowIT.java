package com.mulgil.generation;

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
    @Autowired GenerationScheduler scheduler;
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
        runCompletionReplay();
        assertThat(jobCount("review_generate")).isOne();
        runOne("review_generate");

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
