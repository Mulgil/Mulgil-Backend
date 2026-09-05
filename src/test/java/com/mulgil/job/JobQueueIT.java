package com.mulgil.job;

import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.indexing.ChunkEmbedJobHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import jakarta.validation.Validator;
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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.ArrayList;
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
    private static final LinkedBlockingQueue<Integer> ACTIVE_CHUNKS_AT_COMPLETION = new LinkedBlockingQueue<>();

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
    Validator validator;

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
        ACTIVE_CHUNKS_AT_COMPLETION.clear();
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
    void claimsOldestChunkEmbeddingsFromOneSeedScope_inFifoOrder() {
        UUID otherSession = insertSession(2);
        List<JobQueue.AiJob> expected = enqueueChunks(sessionId, 3, 0);
        enqueueChunks(otherSession, 2, 10);

        List<JobQueue.ClaimedJob> claimed = queue.claimChunkEmbeddings("batch-worker", 2);

        assertThat(claimed).extracting(JobQueue.ClaimedJob::id)
                .containsExactly(expected.get(0).id(), expected.get(1).id());
        assertThat(claimed).allSatisfy(job -> {
            assertThat(job.ownerId()).isEqualTo(ownerId);
            assertThat(job.courseId()).isEqualTo(courseId);
            assertThat(job.sessionId()).isEqualTo(sessionId);
            assertThat(job.attemptCount()).isOne();
            assertThat(job.claimedBy()).isEqualTo("batch-worker");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE status='running'")
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void claimsDisjointSameScopeChunkEmbeddingBatches_whenWorkersRace() throws Exception {
        List<JobQueue.AiJob> expected = enqueueChunks(sessionId, 6, 0);
        UUID otherSession = insertSession(2);
        enqueueChunks(otherSession, 2, 10);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> claimChunkBatchWhenReleased("batch-worker-1", ready, start));
            var second = workers.submit(() -> claimChunkBatchWhenReleased("batch-worker-2", ready, start));
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<JobQueue.ClaimedJob> claimed = new ArrayList<>(first.get(5, TimeUnit.SECONDS));
            claimed.addAll(second.get(5, TimeUnit.SECONDS));
            assertThat(claimed).extracting(JobQueue.ClaimedJob::id)
                    .containsExactlyInAnyOrderElementsOf(expected.stream().map(JobQueue.AiJob::id).toList());
            assertThat(claimed).extracting(JobQueue.ClaimedJob::id).doesNotHaveDuplicates();
            assertThat(claimed).allSatisfy(job -> {
                assertThat(job.ownerId()).isEqualTo(ownerId);
                assertThat(job.courseId()).isEqualTo(courseId);
                assertThat(job.sessionId()).isEqualTo(sessionId);
                assertThat(job.attemptCount()).isOne();
            });
        } finally {
            start.countDown();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE status='queued' AND session_id=:session")
                .param("session", otherSession).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void reclaimsExpiredChunkEmbeddingLease_whenAttemptsRemain() {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        queue.claimChunkEmbeddings("crashed-batch-worker", 1);
        jdbc.sql("""
                UPDATE ai_jobs SET last_heartbeat_at=now()-interval '2 seconds',
                    lease_expires_at=now()-interval '1 second' WHERE id=:id
                """).param("id", job.id()).update();

        List<JobQueue.ClaimedJob> reclaimed = queue.claimChunkEmbeddings("recovery-batch-worker", 1);

        assertThat(reclaimed).singleElement().satisfies(claimed -> {
            assertThat(claimed.id()).isEqualTo(job.id());
            assertThat(claimed.attemptCount()).isEqualTo(2);
            assertThat(claimed.claimedBy()).isEqualTo("recovery-batch-worker");
        });
    }

    @Test
    void archivesQueuedChunkEmbeddings_whenTheirCourseIsSoftDeleted() {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        jdbc.sql("UPDATE courses SET deleted_at=now() WHERE id=:id").param("id", courseId).update();

        assertThat(queue.claimChunkEmbeddings("batch-worker", 1)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE id=:id").param("id", job.id())
                .query(String.class).single()).isEqualTo("outdated");
    }

    @Test
    void rejectsNonPositiveChunkEmbeddingBatchLimit() {
        assertThatThrownBy(() -> queue.claimChunkEmbeddings("batch-worker", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxJobs must be positive.");
    }

    @Test
    void bindsBoundedChunkEmbedConcurrency_withDefaultFour() {
        assertThat(properties.jobs().chunkEmbedConcurrency()).isEqualTo(4);
        assertThat(validator.validate(new MulgilProperties.Jobs(2, 60, 60, 0))).isNotEmpty();
        assertThat(validator.validate(new MulgilProperties.Jobs(2, 60, 60, 33))).isNotEmpty();
        assertThat(validator.validate(new MulgilProperties.Jobs(2, 60, 60, 32))).isEmpty();
    }

    @Test
    void manualDeadlockRetryPreservesRowAndAttemptsUntilClaim_thenRejectsExhaustion() {
        JobQueue.AiJob job = queue.enqueue(request());
        JobHandler handler = new JobHandler() {
            @Override
            public String jobType() {
                return "pdf_extract";
            }

            @Override
            public JobPublication handle(JobQueue.ClaimedJob ignored) {
                throw new RuntimeException("database operation failed",
                        new SQLException("DELETE FROM sensitive_table", "40P01"));
            }
        };
        JobWorker worker = new JobWorker(queue, List.of(handler), properties);
        try {
            worker.poll();
        } finally {
            worker.close();
        }
        assertThat(jdbc.sql("""
                        SELECT status||':'||error_code||':'||error_message||':'||attempt_count
                        FROM ai_jobs WHERE id=:id
                        """).param("id", job.id()).query(String.class).single())
                .isEqualTo("failed:DATABASE_DEADLOCK:Job handler failed.:1");

        JobQueue.AiJob retried = queue.retry(ownerId, job.id());
        assertThat(retried.id()).isEqualTo(job.id());
        assertThat(jdbc.sql("""
                        SELECT status||':'||attempt_count||':'||(error_code IS NULL)||':'||
                               (error_message IS NULL)||':'||(finished_at IS NULL)
                        FROM ai_jobs WHERE id=:id
                        """).param("id", job.id()).query(String.class).single())
                .isEqualTo("queued:1:true:true:true");

        for (int attempt = 2; attempt <= 3; attempt++) {
            JobQueue.ClaimedJob claimed = queue.claim("worker-" + attempt, Set.of("pdf_extract"));
            assertThat(claimed.id()).isEqualTo(job.id());
            assertThat(claimed.attemptCount()).isEqualTo(attempt);
            queue.fail(claimed, "DATABASE_DEADLOCK", "Job handler failed.", true);
            if (attempt < 3) {
                assertThat(queue.retry(ownerId, job.id()).id()).isEqualTo(job.id());
            }
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
    void retryOnEnqueueRequeuesSameDeadlockedRowWithoutIncrementingAttempt() {
        JobQueue.EnqueueRequest request = request();
        JobQueue.AiJob first = queue.enqueue(request);
        JobQueue.ClaimedJob claimed = queue.claim("worker", Set.of("pdf_extract"));
        queue.fail(claimed, "DATABASE_DEADLOCK", "Job handler failed.", true);

        JobQueue.AiJob second = queue.enqueue(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.status()).isEqualTo("queued");
        assertThat(jdbc.sql("""
                        SELECT status||':'||attempt_count||':'||(error_code IS NULL)||':'||
                               (error_message IS NULL)||':'||(finished_at IS NULL)
                        FROM ai_jobs WHERE id=:id
                        """).param("id", first.id()).query(String.class).single())
                .isEqualTo("queued:1:true:true:true");
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
    void blocksLeaseRecoveryUntilLockedPublicationCommits_andRejectsOldWorkerAfterReclaim() throws Exception {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        JobQueue.ClaimedJob oldClaim = queue.claimChunkEmbeddings("old-worker", 1).getFirst();
        Instant lease = jdbc.sql("SELECT lease_expires_at FROM ai_jobs WHERE id=:id")
                .param("id", job.id()).query(Instant.class).single();
        CountDownLatch publicationEntered = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        AtomicBoolean rejectedPublication = new AtomicBoolean();

        try (var workers = Executors.newScheduledThreadPool(2)) {
            var publication = workers.submit(() -> queue.publishWhileRunning(oldClaim, () -> {
                publicationEntered.countDown();
                try {
                    if (!releasePublication.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Publication release timed out.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Publication interrupted.", exception);
                }
                jdbc.sql("UPDATE courses SET name='published' WHERE id=:id").param("id", courseId).update();
            }));
            assertThat(publicationEntered.await(2, TimeUnit.SECONDS)).isTrue();
            long afterLease = Math.max(1, Duration.between(Instant.now(), lease).toMillis() + 100);
            var recovery = workers.schedule(() -> {
                recoveryStarted.countDown();
                return queue.claimChunkEmbeddings("recovery-worker", 1).getFirst();
            }, afterLease, TimeUnit.MILLISECONDS);

            assertThat(recoveryStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> recovery.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releasePublication.countDown();

            assertThat(publication.get(3, TimeUnit.SECONDS)).isTrue();
            JobQueue.ClaimedJob reclaimed = recovery.get(3, TimeUnit.SECONDS);
            assertThat(reclaimed.claimedBy()).isEqualTo("recovery-worker");
            assertThat(reclaimed.attemptCount()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT name FROM courses WHERE id=:id").param("id", courseId)
                    .query(String.class).single()).isEqualTo("published");
            assertThat(queue.publishWhileRunning(oldClaim, () -> rejectedPublication.set(true))).isFalse();
            assertThat(rejectedPublication).isFalse();
        } finally {
            releasePublication.countDown();
        }
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
    void finalizesEveryChunkEmbeddingButEmitsOneCompletionAfterCommit() {
        List<JobQueue.AiJob> jobs = enqueueChunks(sessionId, 3, 0);
        List<JobQueue.ClaimedJob> claimed = queue.claimChunkEmbeddings("batch-worker", 5);
        AtomicInteger publications = new AtomicInteger();
        List<ChunkEmbedJobHandler.BatchOutcome> outcomes = claimed.stream()
                .map(job -> new ChunkEmbedJobHandler.BatchOutcome(job, publications::incrementAndGet, null))
                .toList();

        queue.finishChunkEmbeddings(outcomes);

        assertThat(publications).hasValue(3);
        assertThat(jobs).allSatisfy(job -> assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("succeeded"));
        assertThat(COMPLETIONS).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("chunk_embed");
            assertThat(event.sessionId()).isEqualTo(sessionId);
        });
    }

    @Test
    void blocksChunkFinalizationBehindSharedSessionLock_beforePublication() throws Exception {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        JobQueue.ClaimedJob claimed = queue.claimChunkEmbeddings("session-gate-worker", 1).getFirst();
        extendLease(job.id());
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerCompleted = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger workerPid = new AtomicInteger();
        AtomicBoolean publicationEntered = new AtomicBoolean();
        var workers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> holder = null;
        java.util.concurrent.Future<?> finalizer = null;

        try {
            holder = workers.submit(() -> transactions.executeWithoutResult(ignored -> {
                holderPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("SELECT id FROM class_sessions WHERE id=:id FOR SHARE")
                        .param("id", sessionId).query(UUID.class).single();
                holderReady.countDown();
                await(releaseHolder, "Session lock release timed out.");
            }));
            assertThat(holderReady.await(5, TimeUnit.SECONDS)).isTrue();
            finalizer = workers.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        workerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        workerStarted.countDown();
                        queue.finishChunkEmbeddings(List.of(new ChunkEmbedJobHandler.BatchOutcome(
                                claimed, () -> publicationEntered.set(true), null)));
                    });
                } finally {
                    workerCompleted.countDown();
                }
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            awaitBlockedBy(holderPid.get(), workerPid.get(), workerCompleted, publicationEntered);
            assertThat(publicationEntered).isFalse();
            releaseHolder.countDown();

            holder.get(5, TimeUnit.SECONDS);
            finalizer.get(5, TimeUnit.SECONDS);
            assertThat(publicationEntered).isTrue();
            assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("succeeded");
        } finally {
            releaseHolder.countDown();
            if (holder != null) holder.cancel(true);
            if (finalizer != null) finalizer.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void blocksChunkFinalizationBehindCourseArchive_thenFencesPublication() throws Exception {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        JobQueue.ClaimedJob claimed = queue.claimChunkEmbeddings("archive-gate-worker", 1).getFirst();
        extendLease(job.id());
        CountDownLatch archiveReady = new CountDownLatch(1);
        CountDownLatch commitArchive = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerCompleted = new CountDownLatch(1);
        AtomicInteger archiverPid = new AtomicInteger();
        AtomicInteger workerPid = new AtomicInteger();
        AtomicBoolean publicationEntered = new AtomicBoolean();
        var workers = Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<?> archiver = null;
        java.util.concurrent.Future<?> finalizer = null;

        try {
            archiver = workers.submit(() -> transactions.executeWithoutResult(ignored -> {
                archiverPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                jdbc.sql("UPDATE courses SET deleted_at=now() WHERE id=:id")
                        .param("id", courseId).update();
                archiveReady.countDown();
                await(commitArchive, "Course archive commit timed out.");
            }));
            assertThat(archiveReady.await(5, TimeUnit.SECONDS)).isTrue();
            finalizer = workers.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        workerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        workerStarted.countDown();
                        queue.finishChunkEmbeddings(List.of(new ChunkEmbedJobHandler.BatchOutcome(
                                claimed, () -> publicationEntered.set(true), null)));
                    });
                } finally {
                    workerCompleted.countDown();
                }
            });
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            awaitBlockedBy(archiverPid.get(), workerPid.get(), workerCompleted, publicationEntered);
            assertThat(publicationEntered).isFalse();
            commitArchive.countDown();

            archiver.get(5, TimeUnit.SECONDS);
            finalizer.get(5, TimeUnit.SECONDS);
            assertThat(publicationEntered).isFalse();
            assertThat(jdbc.sql("SELECT status||':'||error_code FROM ai_jobs WHERE id=:id")
                    .param("id", job.id()).query(String.class).single()).isEqualTo("outdated:STALE_INPUT");
            assertThat(COMPLETIONS).isEmpty();
        } finally {
            commitArchive.countDown();
            if (archiver != null) archiver.cancel(true);
            if (finalizer != null) finalizer.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void emitsOneCompletionAfter143ConcurrentChunkJobsBecomeTerminal() throws Exception {
        List<JobQueue.AiJob> jobs = enqueueChunks(sessionId, 143, 0);
        List<List<JobQueue.ClaimedJob>> batches = new ArrayList<>();
        List<JobQueue.ClaimedJob> claimed;
        while (!(claimed = queue.claimChunkEmbeddings("batch-worker", 5)).isEmpty()) {
            batches.add(claimed);
        }

        assertThat(batches).hasSize(29);
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(5));
        try (var finalizers = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> completions = new ArrayList<>();
            for (List<JobQueue.ClaimedJob> batch : batches) {
                completions.add(finalizers.submit(() -> queue.finishChunkEmbeddings(batch.stream()
                        .map(job -> new ChunkEmbedJobHandler.BatchOutcome(job, () -> {}, null)).toList())));
            }
            for (java.util.concurrent.Future<?> completion : completions) {
                completion.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(jobs).allSatisfy(job -> assertThat(queue.get(ownerId, job.id()).status()).isEqualTo("succeeded"));
        assertThat(COMPLETIONS).singleElement().satisfies(event -> assertThat(event.sessionId()).isEqualTo(sessionId));
        assertThat(ACTIVE_CHUNKS_AT_COMPLETION).containsExactly(0);
    }

    @Test
    void finalizesSuccessfulAndFailedChunkEmbeddingOutcomesIndividually() {
        List<JobQueue.AiJob> jobs = enqueueChunks(sessionId, 2, 0);
        List<JobQueue.ClaimedJob> claimed = queue.claimChunkEmbeddings("batch-worker", 5);
        JobHandler.JobExecutionException providerFailure = new JobHandler.JobExecutionException(
                "PROVIDER_UNAVAILABLE", "Embedding provider failed.", true);

        queue.finishChunkEmbeddings(List.of(
                new ChunkEmbedJobHandler.BatchOutcome(claimed.get(0), () -> {}, null),
                new ChunkEmbedJobHandler.BatchOutcome(claimed.get(1), null, providerFailure)));

        assertThat(queue.get(ownerId, jobs.get(0).id()).status()).isEqualTo("succeeded");
        assertThat(queue.get(ownerId, jobs.get(1).id())).satisfies(job -> {
            assertThat(job.status()).isEqualTo("failed");
            assertThat(job.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        });
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE status='running'")
                .query(Integer.class).single()).isZero();
        assertThat(COMPLETIONS).hasSize(1);
    }

    @Test
    void failsTerminalStaleChunkOutcome_withoutCallingItsPublication() {
        JobQueue.AiJob job = enqueueChunks(sessionId, 1, 0).getFirst();
        JobQueue.ClaimedJob claimed = queue.claimChunkEmbeddings("batch-worker", 5).getFirst();
        AtomicBoolean published = new AtomicBoolean();
        JobHandler.JobExecutionException stale = new JobHandler.JobExecutionException(
                "STALE_INPUT", "Chunk changed before embedding publication.", false);

        queue.finishChunkEmbeddings(List.of(
                new ChunkEmbedJobHandler.BatchOutcome(claimed, () -> published.set(true), stale)));

        assertThat(published).isFalse();
        assertThat(queue.get(ownerId, job.id())).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo("failed");
            assertThat(failed.errorCode()).isEqualTo("STALE_INPUT");
        });
        assertThat(COMPLETIONS).isEmpty();
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

    private UUID insertSession(int sessionNumber) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id, owner_id, course_id, session_number, title, session_date, created_at, updated_at)
                VALUES (:id, :owner, :course, :number, 'Other session', DATE '2026-09-02', now(), now())
                """).param("id", id).param("owner", ownerId).param("course", courseId)
                .param("number", sessionNumber).update();
        return id;
    }

    private List<JobQueue.AiJob> enqueueChunks(UUID session, int count, int minuteOffset) {
        List<JobQueue.AiJob> jobs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            JobQueue.AiJob job = queue.enqueue(new JobQueue.EnqueueRequest("chunk_embed", ownerId, courseId,
                    session, null, null, null, null, null, index + 1, "%064x".formatted(index + 1L + minuteOffset),
                    "vertex", "embedding-v1", "none"));
            jdbc.sql("UPDATE ai_jobs SET created_at=:created WHERE id=:id")
                    .param("created", Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")
                            .plusSeconds((long) (minuteOffset + index) * 60)))
                    .param("id", job.id()).update();
            jobs.add(job);
        }
        return jobs;
    }

    private void extendLease(UUID jobId) {
        jdbc.sql("UPDATE ai_jobs SET lease_expires_at=now() + interval '1 minute' WHERE id=:id")
                .param("id", jobId).update();
    }

    private void awaitBlockedBy(int blockerPid, int workerPid, CountDownLatch workerCompleted,
                                AtomicBoolean publicationEntered) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            if (workerCompleted.getCount() == 0 || publicationEntered.get()) {
                throw new AssertionError("Chunk finalizer reached publication before the expected lock block.");
            }
            boolean blocked = jdbc.sql("SELECT :blocker = ANY(pg_blocking_pids(:worker))")
                    .param("blocker", blockerPid).param("worker", workerPid)
                    .query(Boolean.class).single();
            if (blocked) return;
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Expected PostgreSQL blocking edge was not observed.");
            }
        }
    }

    private static void await(CountDownLatch latch, String timeoutMessage) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(timeoutMessage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Lock holder interrupted.", exception);
        }
    }

    private List<JobQueue.ClaimedJob> claimChunkBatchWhenReleased(
            String workerId, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Batch claim start timed out.");
        return queue.claimChunkEmbeddings(workerId, 3);
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
        JobCompletionListener recordingCompletionListener(JdbcClient jdbc) {
            return event -> {
                COMPLETIONS.add(event);
                ACTIVE_CHUNKS_AT_COMPLETION.add(jdbc.sql("""
                                SELECT count(*) FROM ai_jobs
                                WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                                  AND job_type='chunk_embed' AND status IN ('queued','running')
                                """).param("owner", event.ownerId()).param("course", event.courseId())
                        .param("session", event.sessionId()).query(Integer.class).single());
            };
        }
    }
}
