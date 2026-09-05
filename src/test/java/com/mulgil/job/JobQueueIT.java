package com.mulgil.job;

import com.mulgil.indexing.ContentIndexingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
@Import(JobQueueIT.ListenerConfiguration.class)
class JobQueueIT {
    private static final String HASH = "a".repeat(64);
    private static final LinkedBlockingQueue<JobQueue.CompletionEvent> COMPLETIONS = new LinkedBlockingQueue<>();

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
        registry.add("mulgil.jobs.lease-seconds", () -> 1);
        registry.add("mulgil.demo.cache-enabled", () -> true);
        registry.add("mulgil.ai-rates.vision-image-microusd", () -> 7);
        registry.add("mulgil.ai-rates.generation-character-microusd", () -> Long.MAX_VALUE);
    }

    @Autowired
    JobQueue queue;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MulgilProperties properties;

    @Autowired
    AiProviderUsageLedger usage;

    @Autowired
    TransactionTemplate transactions;

    UUID ownerId;
    UUID courseId;
    UUID sessionId;
    UUID materialId;
    UUID recordingId;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM ai_provider_usage").update();
        jdbc.sql("DELETE FROM users").update();
        COMPLETIONS.clear();
        ownerId = UUID.randomUUID();
        courseId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        materialId = UUID.randomUUID();
        recordingId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO users VALUES (:id, 'google', :subject, :email, 'Owner', :now)")
                .param("id", ownerId).param("subject", ownerId.toString())
                .param("email", ownerId + "@example.com").param("now", now).update();
        jdbc.sql("INSERT INTO courses VALUES (:id, :owner, 'Course', NULL, NULL, :now, :now)")
                .param("id", courseId).param("owner", ownerId).param("now", now).update();
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id, owner_id, course_id, session_number, title, session_date, created_at, updated_at)
                VALUES (:id, :owner, :course, 1, 'Session', DATE '2026-09-01', :now, :now)
                """).param("id", sessionId).param("owner", ownerId).param("course", courseId)
                .param("now", now).update();
        jdbc.sql("""
                INSERT INTO materials
                    (id, owner_id, course_id, session_id, source_phase, object_key, original_filename,
                     mime_type, byte_size, checksum, version, status, created_at, updated_at)
                VALUES (:id, :owner, :course, :session, 'preview_pdf', :key, 'source.pdf',
                        'application/pdf', 10, :hash, 1, 'uploaded', :now, :now)
                """).param("id", materialId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "job/" + materialId).param("hash", HASH)
                .param("now", now).update();
        jdbc.sql("""
                INSERT INTO audio_recordings
                    (id,owner_id,course_id,session_id,object_key,original_filename,mime_type,byte_size,
                     checksum,started_at,duration_seconds,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,:key,'source.m4a','audio/m4a',10,:hash,
                        :now,10,1,'uploaded',:now,:now)
                """).param("id", recordingId).param("owner", ownerId).param("course", courseId)
                .param("session", sessionId).param("key", "job/" + recordingId).param("hash", HASH)
                .param("now", now).update();
    }

    @Test
    void returnsOneJob_whenConcurrentRequestsEnqueueTheSameInput() throws Exception {
        var request = request();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> queue.enqueue(request));
            var second = workers.submit(() -> queue.enqueue(request));

            assertThat(first.get().id()).isEqualTo(second.get().id());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isOne();
    }

    @Test
    void reusesSucceededJob_whenCacheEnabledAndIdentityMatches() {
        JobQueue.EnqueueRequest request = new JobQueue.EnqueueRequest("handwriting_ocr", ownerId, courseId,
                sessionId, null, null, null, null, null, 1, HASH, "google-vision", "document-text", "none");
        JobQueue.AiJob first = queue.enqueue(request);
        JobQueue.ClaimedJob claimed = queue.claim("cache-worker", Set.of("handwriting_ocr"));
        queue.complete(claimed, () -> {});

        JobQueue.AiJob cached = queue.enqueue(request);

        assertThat(cached.id()).isEqualTo(first.id());
        assertThat(cached.status()).isEqualTo("succeeded");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isOne();
    }

    @Test
    void reusesSucceededGeneration_whenOnlyAllocatedArtifactVersionChanges() {
        String snapshotHash = ContentIndexingService.sha256("");
        JobQueue.EnqueueRequest firstRequest = new JobQueue.EnqueueRequest("review_generate", ownerId, courseId,
                sessionId, null, null, null, null, null, 1, snapshotHash, "vertex", "generation-v1", "prompt-v1");
        JobQueue.AiJob first = queue.enqueue(firstRequest);
        queue.complete(queue.claim("generation-cache", Set.of("review_generate")), () -> {});

        JobQueue.AiJob replay = queue.enqueue(new JobQueue.EnqueueRequest("review_generate", ownerId, courseId,
                sessionId, null, null, null, null, null, 2, snapshotHash, "vertex", "generation-v1", "prompt-v1"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type='review_generate'")
                .query(Integer.class).single()).isOne();
    }

    @Test
    void preservesProviderUsageAcrossPublicationRollback_withoutSensitivePayloadColumns() {
        JobQueue.AiJob job = queue.enqueue(billable("chunk_embed", 0));
        JobQueue.ClaimedJob claimed = queue.claim("usage-worker", Set.of("chunk_embed"));

        transactions.executeWithoutResult(status -> {
            usage.observe(claimed, "vision.ocr", "google-vision", "document-text", "image", 2L,
                    () -> "provider-response");
            status.setRollbackOnly();
        });

        assertThat(jdbc.sql("""
                        SELECT status||':'||unit_type||':'||unit_count||':'||estimated_cost_microusd
                        FROM ai_provider_usage WHERE job_id=:job
                        """).param("job", job.id()).query(String.class).single())
                .isEqualTo("succeeded:image:2:14");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_name='ai_provider_usage'
                          AND column_name IN ('source','raw_source','ocr_text','transcript','prompt','answer',
                                              'signed_url','token','credential','secret')
                        """).query(Integer.class).single()).isZero();
    }

    @Test
    void leavesStartedUsageForInterruptedCall_andMakesOverflowCostUnknown() {
        JobQueue.AiJob job = queue.enqueue(billable("chunk_embed", 0));
        JobQueue.ClaimedJob claimed = queue.claim("interrupted-worker", Set.of("chunk_embed"));

        AiProviderUsageLedger.UsageHandle interrupted = usage.begin(claimed.id(), claimed.ownerId(),
                "vertex.embed", "vertex", "embedding-v1", "unicode_code_point", null);
        AiProviderUsageLedger.UsageHandle overflow = usage.begin(claimed.id(), claimed.ownerId(),
                "vertex.generate", "vertex", "generation-v1", "unicode_code_point", 2L);
        usage.succeed(overflow, 2L);

        assertThat(jdbc.sql("SELECT status FROM ai_provider_usage WHERE id=:id")
                .param("id", interrupted.id()).query(String.class).single()).isEqualTo("started");
        assertThat(jdbc.sql("SELECT estimated_cost_microusd FROM ai_provider_usage WHERE id=:id")
                .param("id", overflow.id()).query(Long.class).optional()).isEmpty();
    }

    @Test
    void leavesStartedUsageUnfinished_whenFinalLeaseExpiresWithoutHandlerReturn() {
        JobQueue.AiJob job = queue.enqueue(billable("chunk_embed", 0));
        JobQueue.ClaimedJob claimed = queue.claim("crashed-provider-worker", Set.of("chunk_embed"));
        AiProviderUsageLedger.UsageHandle started = usage.begin(claimed.id(), claimed.ownerId(),
                "vertex.embed", "vertex", "embedding-v1", "unicode_code_point", 4L);
        jdbc.sql("""
                UPDATE ai_jobs SET attempt_count=max_attempts,
                    last_heartbeat_at=now()-interval '2 seconds', lease_expires_at=now()-interval '1 second'
                WHERE id=:id
                """).param("id", job.id()).update();

        assertThat(queue.claim("recovery-worker", Set.of("chunk_embed"))).isNull();

        assertThat(jdbc.sql("SELECT status||':'||error_code FROM ai_jobs WHERE id=:id")
                .param("id", job.id()).query(String.class).single()).isEqualTo("failed:LEASE_EXPIRED");
        assertThat(jdbc.sql("SELECT status||':'||(finished_at IS NULL) FROM ai_provider_usage WHERE id=:id")
                .param("id", started.id()).query(String.class).single()).isEqualTo("started:true");
    }

    @Test
    void missesCache_whenSourceModelOrPromptChanges() {
        JobQueue.EnqueueRequest base = billable("chunk_embed", 0);
        queue.enqueue(base);

        queue.enqueue(new JobQueue.EnqueueRequest(base.type(), base.ownerId(), base.courseId(), base.sessionId(),
                base.materialId(), base.examResourceId(), base.noteId(), base.recordingId(), base.examId(),
                base.inputVersion(), "b".repeat(64), base.provider(), base.model(), base.promptVersion()));
        queue.enqueue(new JobQueue.EnqueueRequest(base.type(), base.ownerId(), base.courseId(), base.sessionId(),
                base.materialId(), base.examResourceId(), base.noteId(), base.recordingId(), base.examId(),
                base.inputVersion(), base.sourceHash(), base.provider(), "fake-v2", base.promptVersion()));
        queue.enqueue(new JobQueue.EnqueueRequest(base.type(), base.ownerId(), base.courseId(), base.sessionId(),
                base.materialId(), base.examResourceId(), base.noteId(), base.recordingId(), base.examId(),
                base.inputVersion(), base.sourceHash(), base.provider(), base.model(), "prompt-v2"));

        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(DISTINCT cache_fingerprint) FROM ai_jobs")
                .query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void rejectsEveryBillableJobTypeAfterThirtyCanonicalJobs_withoutChargingDuplicate() {
        JobQueue.AiJob first = queue.enqueue(billable("pdf_ocr", 0));
        JobQueue.ClaimedJob claimed = queue.claim("quota-retry", Set.of("pdf_ocr"));
        queue.fail(claimed, "PROVIDER_TIMEOUT", "Provider timed out.", true);
        for (int index = 1; index < 30; index++) queue.enqueue(billable("pdf_ocr", index));

        assertThat(queue.retry(ownerId, first.id()).status()).isEqualTo("queued");
        assertThat(queue.enqueue(billable("pdf_ocr", 0)).id()).isEqualTo(first.id());
        for (String type : Set.of("pdf_ocr", "handwriting_ocr", "stt", "review_generate")) {
            assertThatThrownBy(() -> queue.enqueue(billable(type, 100)))
                    .isInstanceOf(ApiException.class)
                    .extracting(value -> ((ApiException) value).code())
                    .isEqualTo("AI_DAILY_LIMIT_REACHED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isEqualTo(30);
    }

    @Test
    void acceptsChunkEmbedAfterDailyLimit_whenItIsAnInternalChildJob() {
        for (int index = 0; index < 30; index++) queue.enqueue(billable("pdf_ocr", index));

        assertThat(queue.enqueue(billable("chunk_embed", 100)).status()).isEqualTo("queued");
    }

    @Test
    void admitsOnlyOneBillableJob_whenTwoEnqueuesRaceAtDailyBoundary() throws Exception {
        for (int index = 0; index < 29; index++) queue.enqueue(billable("pdf_ocr", index));

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> enqueueResult(billable("stt", 100)));
            var second = workers.submit(() -> enqueueResult(billable("pdf_ocr", 101)));

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder("accepted", "AI_DAILY_LIMIT_REACHED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isEqualTo(30);
    }

    @Test
    void claimsOneJob_whenWorkersPollConcurrently() throws Exception {
        queue.enqueue(request());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> queue.claim("worker-1", Set.of("pdf_extract")));
            var second = workers.submit(() -> queue.claim("worker-2", Set.of("pdf_extract")));

            assertThat(java.util.stream.Stream.of(first.get(), second.get()).filter(java.util.Objects::nonNull))
                    .hasSize(1);
        }
        assertThat(jdbc.sql("SELECT attempt_count FROM ai_jobs").query(Integer.class).single()).isOne();
    }

    @Test
    void rejectsFourthRetry_whenProviderFailsThreeClaims() {
        JobQueue.AiJob job = queue.enqueue(request());
        for (int attempt = 1; attempt <= 3; attempt++) {
            JobQueue.ClaimedJob claimed = queue.claim("worker-" + attempt, Set.of("pdf_extract"));
            assertThat(claimed.id()).isEqualTo(job.id());
            assertThat(claimed.attemptCount()).isEqualTo(attempt);
            queue.fail(claimed, "PROVIDER_TIMEOUT", "Provider timed out.", true);
            if (attempt < 3) assertThat(queue.retry(ownerId, job.id()).status()).isEqualTo("queued");
        }

        assertThat(queue.claim("worker-4", Set.of("pdf_extract"))).isNull();
        assertThatThrownBy(() -> queue.retry(ownerId, job.id()))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_RETRYABLE");
        assertThat(jdbc.sql("SELECT attempt_count FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void requeuesSameRow_whenRetryableFailedInputIsEnqueuedAgain() {
        JobQueue.AiJob first = queue.enqueue(request());
        JobQueue.ClaimedJob claimed = queue.claim("worker", Set.of("pdf_extract"));
        queue.fail(claimed, "PROVIDER_TIMEOUT", "Provider timed out.", true);

        JobQueue.AiJob second = queue.enqueue(request());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo("queued");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs").query(Integer.class).single()).isOne();
    }

    @Test
    void reclaimsExpiredLease_whenAttemptsRemain() {
        JobQueue.AiJob job = queue.enqueue(request());
        JobQueue.ClaimedJob first = queue.claim("crashed-worker", Set.of("pdf_extract"));
        jdbc.sql("""
                UPDATE ai_jobs SET last_heartbeat_at=now()-interval '2 seconds',
                    lease_expires_at=now()-interval '1 second' WHERE id=:id
                """)
                .param("id", job.id()).update();

        JobQueue.ClaimedJob reclaimed = queue.claim("recovery-worker", Set.of("pdf_extract"));

        assertThat(reclaimed.id()).isEqualTo(first.id());
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        assertThat(reclaimed.claimedBy()).isEqualTo("recovery-worker");
    }

    @Test
    void blocksPublication_whenInputVersionIsStale() {
        JobQueue.AiJob job = queue.enqueue(request());
        JobQueue.ClaimedJob claimed = queue.claim("worker", Set.of("pdf_extract"));
        jdbc.sql("UPDATE materials SET version=2, checksum=:hash WHERE id=:id")
                .param("hash", "b".repeat(64)).param("id", materialId).update();
        AtomicBoolean published = new AtomicBoolean();

        assertThat(queue.complete(claimed, () -> published.set(true))).isFalse();
        assertThat(published).isFalse();
        assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("outdated");
    }

    @Test
    void archivesQueuedJobs_whenTheirCourseIsSoftDeleted() {
        JobQueue.AiJob job = queue.enqueue(request());
        jdbc.sql("UPDATE courses SET deleted_at=now() WHERE id=:id").param("id", courseId).update();

        assertThat(queue.claim("worker", Set.of("pdf_extract"))).isNull();
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(String.class).single()).isEqualTo("outdated");
        assertThatThrownBy(() -> queue.get(ownerId, job.id()))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_FOUND");
    }

    @Test
    void skipsPreclaimedJob_whenItsCourseIsSoftDeleted() throws Exception {
        JobQueue.AiJob job = queue.enqueue(request());
        JobQueue.ClaimedJob claimed = queue.claim("worker", Set.of("pdf_extract"));
        jdbc.sql("UPDATE courses SET deleted_at=now() WHERE id=:id").param("id", courseId).update();
        AtomicBoolean handled = new AtomicBoolean();

        assertThat(queue.run(claimed, new JobHandler() {
            @Override
            public String jobType() {
                return "pdf_extract";
            }

            @Override
            public JobPublication handle(JobQueue.ClaimedJob ignored) {
                handled.set(true);
                return () -> {};
            }
        })).isFalse();

        assertThat(handled).isFalse();
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(String.class).single()).isEqualTo("outdated");
    }

    @Test
    void waitsForRunningJobBeforeArchivingItsCourse() throws Exception {
        queue.enqueue(request());
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch archiveStarted = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        JobHandler handler = new JobHandler() {
            @Override
            public String jobType() {
                return "pdf_extract";
            }

            @Override
            public JobPublication handle(JobQueue.ClaimedJob ignored) throws JobExecutionException {
                handlerStarted.countDown();
                try {
                    if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                        throw new JobExecutionException("TEST_TIMEOUT", "Handler timed out.", false);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new JobExecutionException("TEST_INTERRUPTED", "Handler interrupted.", false);
                }
                return publications::incrementAndGet;
            }
        };
        JobWorker worker = new JobWorker(queue, java.util.List.of(handler), properties);
        var poller = Executors.newSingleThreadExecutor();
        var archiver = Executors.newSingleThreadExecutor();

        try {
            var workerRun = poller.submit(worker::poll);
            assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var archive = archiver.submit(() -> {
                archiveStarted.countDown();
                jdbc.sql("UPDATE courses SET deleted_at=now() WHERE id=:id").param("id", courseId).update();
            });
            assertThat(archiveStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> archive.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            releaseHandler.countDown();
            workerRun.get(3, TimeUnit.SECONDS);
            archive.get(3, TimeUnit.SECONDS);
            assertThat(publications).hasValue(1);
            assertThat(jdbc.sql("SELECT deleted_at IS NOT NULL FROM courses WHERE id=:id").param("id", courseId)
                    .query(Boolean.class).single()).isTrue();
        } finally {
            releaseHandler.countDown();
            poller.shutdownNow();
            archiver.shutdownNow();
            worker.close();
        }
    }

    @Test
    void extendsLease_whenClaimedWorkerHeartbeats() {
        JobQueue.AiJob job = queue.enqueue(request());
        queue.claim("worker", Set.of("pdf_extract"));
        Instant before = jdbc.sql("SELECT lease_expires_at FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(Instant.class).single();

        assertThat(queue.heartbeat(job.id(), "worker")).isTrue();

        Instant after = jdbc.sql("SELECT lease_expires_at FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(Instant.class).single();
        assertThat(after).isAfterOrEqualTo(before);
    }

    @Test
    void rejectsHeartbeatAndPublication_whenWorkerLeaseExpired() {
        JobQueue.AiJob job = queue.enqueue(request());
        JobQueue.ClaimedJob claimed = queue.claim("expired-worker", Set.of("pdf_extract"));
        jdbc.sql("""
                UPDATE ai_jobs SET last_heartbeat_at=now()-interval '2 seconds',
                    lease_expires_at=now()-interval '1 second' WHERE id=:id
                """).param("id", job.id()).update();
        AtomicBoolean published = new AtomicBoolean();

        assertThat(queue.heartbeat(job.id(), "expired-worker")).isFalse();
        assertThat(queue.complete(claimed, () -> published.set(true))).isFalse();
        assertThat(published).isFalse();
        assertThat(queue.claim("recovery-worker", Set.of("pdf_extract")).attemptCount()).isEqualTo(2);
    }

    @Test
    void keepsSingleClaimAndPublication_whenHandlerExceedsOneSecondLease() throws Exception {
        JobQueue.AiJob job = queue.enqueue(request());
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger publications = new AtomicInteger();
        JobHandler handler = new JobHandler() {
            @Override
            public String jobType() {
                return "pdf_extract";
            }

            @Override
            public JobPublication handle(JobQueue.ClaimedJob ignored) throws JobExecutionException {
                handlerStarted.countDown();
                try {
                    if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                        throw new JobExecutionException("TEST_TIMEOUT", "Handler timed out.", false);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new JobExecutionException("TEST_INTERRUPTED", "Handler interrupted.", false);
                }
                return publications::incrementAndGet;
            }
        };
        JobWorker worker = new JobWorker(queue, java.util.List.of(handler), properties);
        var poller = Executors.newSingleThreadExecutor();
        ScheduledExecutorService contender = Executors.newSingleThreadScheduledExecutor();

        try {
            var workerRun = poller.submit(worker::poll);
            assertThat(handlerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Instant initialLease = jdbc.sql("SELECT lease_expires_at FROM ai_jobs WHERE id=:id")
                    .param("id", job.id()).query(Instant.class).single();
            long afterInitialLease = Math.max(1,
                    Duration.between(Instant.now(), initialLease).toMillis() + 100);

            JobQueue.ClaimedJob duplicate = contender.schedule(
                    () -> queue.claim("contender", Set.of("pdf_extract")),
                    afterInitialLease, TimeUnit.MILLISECONDS).get(3, TimeUnit.SECONDS);

            assertThat(duplicate).isNull();
            releaseHandler.countDown();
            workerRun.get(3, TimeUnit.SECONDS);
            assertThat(publications).hasValue(1);
            assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("succeeded");
            assertThat(queue.get(ownerId, job.id()).attemptCount()).isOne();
        } finally {
            releaseHandler.countDown();
            poller.shutdownNow();
            contender.shutdownNow();
            worker.close();
        }
    }

    @Test
    void notifiesListenerAfterCommit_whenCurrentJobSucceeds() {
        JobQueue.AiJob job = queue.enqueue(request());
        JobQueue.ClaimedJob claimed = queue.claim("worker", Set.of("pdf_extract"));

        assertThat(queue.complete(claimed, () -> {})).isTrue();

        assertThat(COMPLETIONS).singleElement().extracting(JobQueue.CompletionEvent::jobId).isEqualTo(job.id());
        assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("succeeded");
    }

    @Test
    void hidesJobs_whenOwnerDoesNotMatch() {
        JobQueue.AiJob job = queue.enqueue(request());

        assertThatThrownBy(() -> queue.get(UUID.randomUUID(), job.id()))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_FOUND");
        assertThatThrownBy(() -> queue.list(UUID.randomUUID(), sessionId))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_FOUND");
    }

    private JobQueue.EnqueueRequest request() {
        return JobQueue.EnqueueRequest.material("pdf_extract", ownerId, courseId, sessionId,
                materialId, 1, HASH, "pdfbox", "pdfbox-3", "none");
    }

    private JobQueue.EnqueueRequest billable(String type, int index) {
        if (type.equals("stt")) {
            return new JobQueue.EnqueueRequest(type, ownerId, courseId, sessionId, null, null, null,
                    recordingId, null, index + 1, "%064x".formatted(index + 1), "fake", "fake-v1", "none");
        }
        return JobQueue.EnqueueRequest.material(type, ownerId, courseId, sessionId, materialId,
                index + 1, "%064x".formatted(index + 1), "fake", "fake-v1", "none");
    }

    private String enqueueResult(JobQueue.EnqueueRequest request) {
        try {
            queue.enqueue(request);
            return "accepted";
        } catch (ApiException exception) {
            return exception.code();
        }
    }

    @TestConfiguration
    static class ListenerConfiguration {
        @Bean
        JobCompletionListener recordingCompletionListener() {
            return COMPLETIONS::add;
        }
    }
}
