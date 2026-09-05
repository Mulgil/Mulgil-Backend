package com.mulgil.document;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ChunkEmbedJobHandler;
import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.JobWorkerTestDriver;
import com.mulgil.ocr.OcrProviderException;
import com.mulgil.ocr.GoogleVisionOcrTestDriver;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.storage.CloudStoragePort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "JOB_POLL_INTERVAL_MILLIS=600000")
@Import(PdfIndexingIT.Fakes.class)
class PdfIndexingIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcClient jdbc;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired FakeStorage storage;
    @Autowired FakeVision vision;
    @Autowired ContentIndexingService indexing;
    @Autowired MulgilProperties properties;
    @Autowired TransactionTemplate transactions;
    @Autowired @Qualifier("generationScheduler") JobCompletionListener generationScheduler;

    UUID owner;
    UUID course;
    UUID session;

    @BeforeEach
    void seedScope() {
        jdbc.sql("DELETE FROM users").update();
        storage.clear();
        vision.reset();
        owner = UUID.randomUUID();
        course = UUID.randomUUID();
        session = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO users VALUES (:id, 'google', :subject, :email, 'Owner', :now)")
                .param("id", owner).param("subject", owner.toString()).param("email", owner + "@example.com")
                .param("now", now).update();
        jdbc.sql("INSERT INTO courses VALUES (:id, :owner, 'Course', NULL, NULL, :now, :now)")
                .param("id", course).param("owner", owner).param("now", now).update();
        insertSession(session, 1);
    }

    @Test
    void indexesTextWithoutOcr_whenNonWhitespaceLengthIs80() throws Exception {
        byte[] pdf = pdf("x".repeat(80), false);
        UUID material = insertMaterial(pdf);
        String checksum = checksum(pdf);

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("chunk_embed");

        assertThat(vision.calls()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE owner_id=:owner AND course_id=:course "
                        + "AND session_id=:session AND embedding IS NOT NULL")
                .param("owner", owner).param("course", course).param("session", session)
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT checksum FROM materials WHERE id=:id").param("id", material)
                .query(String.class).single()).isEqualTo(checksum);
    }

    @Test
    void invokesOcrOnce_whenNonWhitespaceLengthIs79() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(79), false));

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");

        assertThat(vision.calls()).isOne();
        assertThat(jdbc.sql("SELECT extraction_method FROM document_pages WHERE material_id=:id")
                .param("id", material).query(String.class).single()).isEqualTo("ocr");
        assertThat(jdbc.sql("SELECT confidence FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Double.class).single()).isEqualTo(0.91);
        assertThat(jdbc.sql("SELECT provider||':'||model_id FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(String.class).single()).isEqualTo("fake-vision:fake-ocr");
        assertThat(jdbc.sql("SELECT source_hash FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(String.class).single()).hasSize(64);
        assertThat(jdbc.sql("""
                        SELECT status||':'||unit_type||':'||unit_count FROM ai_provider_usage
                        WHERE job_id=(SELECT id FROM ai_jobs WHERE material_id=:id AND job_type='pdf_ocr')
                        """).param("id", material).query(String.class).single())
                .isEqualTo("succeeded:image:1");
    }

    @Test
    void invokesOcrOnce_whenImageCoverageIsExactlyHalf() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(80), true));

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");

        assertThat(vision.calls()).isOne();
    }

    @Test
    void preservesProviderFailureCode_whenPdfOcrFails() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(79), false));
        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        vision.fail(new OcrProviderException("PROVIDER_UNAVAILABLE", "Vision is unavailable.", true));
        UUID jobId = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='pdf_ocr'")
                .query(UUID.class).single();

        JobWorkerTestDriver.poll(jobs, handler("pdf_ocr"), properties);

        assertThat(jobs.get(owner, jobId).status()).isEqualTo("failed");
        assertThat(jobs.get(owner, jobId).errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(jobs.retry(owner, jobId).status()).isEqualTo("queued");
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isZero();
    }

    @Test
    void rejectsRetry_whenVisionTerminalFailurePersistsThroughWorker() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(79), false));
        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        vision.delegate(GoogleVisionOcrTestDriver.permissionDenied());
        UUID jobId = jdbc.sql("SELECT id FROM ai_jobs WHERE job_type='pdf_ocr'")
                .query(UUID.class).single();

        JobWorkerTestDriver.poll(jobs, handler("pdf_ocr"), properties);

        assertThatThrownBy(() -> jobs.retry(owner, jobId))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_RETRYABLE");
        assertThat(jobs.get(owner, jobId).status()).isEqualTo("failed");
        assertThat(jobs.get(owner, jobId).errorCode()).isEqualTo("PROVIDER_AUTHENTICATION_FAILED");
    }

    @Test
    void materializesPastExamForSelectedSessions_andForeignOwnerReadsNothing() throws Exception {
        UUID secondSession = UUID.randomUUID();
        insertSession(secondSession, 2);
        byte[] pdf = pdf("exam".repeat(20), false);
        UUID exam = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO exams VALUES (:id,:owner,:course,'Exam',:now,:now,:now)")
                .param("id", exam).param("owner", owner).param("course", course).param("now", now).update();
        for (UUID selected : List.of(session, secondSession)) {
            jdbc.sql("INSERT INTO exam_session_members VALUES (:exam,:session,:owner,:course,:now)")
                    .param("exam", exam).param("session", selected).param("owner", owner)
                    .param("course", course).param("now", now).update();
        }
        String key = "exam/" + resource;
        storage.put(key, pdf);
        jdbc.sql("""
                INSERT INTO exam_resources
                    (id,owner_id,course_id,exam_id,resource_type,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:exam,'past_exam',:key,'exam.pdf','application/pdf',
                        :size,1,:hash,'uploaded',:now,:now)
                """).param("id", resource).param("owner", owner).param("course", course).param("exam", exam)
                .param("key", key).param("size", pdf.length).param("hash", checksum(pdf)).param("now", now).update();

        jobs.enqueuePdfExamResource(owner, resource);
        runAll("pdf_extract");

        List<String> refs = jdbc.sql("SELECT source_ref::text FROM chunks WHERE owner_id=:owner ORDER BY session_id")
                .param("owner", owner).query(String.class).list();
        assertThat(refs).hasSize(2).allMatch(reference -> reference.contains("\"sourceType\": \"past_exam\"")
                && reference.contains(resource.toString()));
        assertThat(jdbc.sql("SELECT count(DISTINCT session_id) FROM chunks "
                        + "WHERE source_ref->>'examResourceId'=:resource")
                .param("resource", resource.toString()).query(Integer.class).single()).isEqualTo(2);
        assertThat(indexing.chunks(UUID.randomUUID(), course, session)).isEmpty();
    }

    @Test
    void rejectsStaleEmbeddingPublication_whenChunkSnapshotChangesAfterClaim() throws Exception {
        UUID material = insertMaterial(pdf("x".repeat(80), false));
        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        JobQueue.ClaimedJob job = jobs.claim("pdf-it", Set.of("chunk_embed"));
        JobHandler.JobPublication stalePublication = handler("chunk_embed").handle(job);
        UUID chunk = jdbc.sql("SELECT id FROM chunks WHERE owner_id=:owner")
                .param("owner", owner).query(UUID.class).single();
        jdbc.sql("""
                UPDATE chunks SET text_content='new current text', source_hash=:hash,
                    source_ref=jsonb_set(source_ref, '{inputVersion}', '2'::jsonb)
                WHERE id=:id
                """).param("hash", "b".repeat(64)).param("id", chunk).update();

        assertThat(jobs.complete(job, stalePublication)).isTrue();
        assertThat(jdbc.sql("SELECT embedding IS NULL FROM chunks WHERE id=:id")
                .param("id", chunk).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT text_content FROM chunks WHERE id=:id")
                .param("id", chunk).query(String.class).single()).isEqualTo("new current text");
    }

    @Test
    void removesObsoleteBlocksAndChunks_whenPageIsRematerializedWithFewerBlocks() throws Exception {
        byte[] pdf = pdf("x".repeat(79), false);
        UUID material = insertMaterial(pdf);
        vision.blocks("first", "obsolete");
        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");
        assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE content_block_id IN "
                        + "(SELECT id FROM content_blocks WHERE material_id=:id)")
                .param("id", material).query(Integer.class).single()).isEqualTo(2);
        jdbc.sql("UPDATE materials SET version=2 WHERE id=:id").param("id", material).update();
        vision.blocks("first");

        jobs.enqueuePdfMaterial(owner, material);
        runAll("pdf_extract");
        runAll("pdf_ocr");

        assertThat(jdbc.sql("SELECT text_content FROM content_blocks WHERE material_id=:id")
                .param("id", material).query(String.class).list()).containsExactly("first");
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE content_block_id IN "
                        + "(SELECT id FROM content_blocks WHERE material_id=:id)")
                .param("id", material).query(Integer.class).single()).isOne();
    }

    @Test
    void completesPdfOcrPublication_whenChunkFinalizationRunsConcurrently() throws Exception {
        byte[] source = pdf("x".repeat(79), false);
        UUID material = insertMaterial(source);
        jobs.enqueuePdfMaterial(owner, material);
        JobQueue.ClaimedJob extract = jobs.claim("deadlock-extract", Set.of("pdf_extract"));
        assertThat(jobs.run(extract, handler("pdf_extract"))).isTrue();
        JobQueue.ClaimedJob ocr = jobs.claim("deadlock-ocr", Set.of("pdf_ocr"));
        jobs.enqueue(JobQueue.EnqueueRequest.material("chunk_embed", owner, course, session,
                material, 1, checksum(source), "vertex", "fake-768", "none"));
        JobQueue.ClaimedJob chunk = jobs.claim("deadlock-chunk", Set.of("chunk_embed"));
        jdbc.sql("UPDATE ai_jobs SET lease_expires_at=now() + interval '1 minute' WHERE id IN (:ids)")
                .param("ids", List.of(ocr.id(), chunk.id())).update();

        CountDownLatch ocrEntered = new CountDownLatch(1);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        CountDownLatch controlledSessionLockHeld = new CountDownLatch(1);
        CountDownLatch finalizerCompleted = new CountDownLatch(1);
        AtomicInteger ocrPid = new AtomicInteger();
        AtomicInteger finalizerPid = new AtomicInteger();
        vision.delegate(ignored -> {
            ocrPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
            ocrEntered.countDown();
            await(releaseOcr);
            return new VisionOcrPort.OcrResult(List.of(
                    new VisionOcrPort.OcrBlock("recognized text", 0.91,
                            new VisionOcrPort.NormalizedBox(0.1, 0.1, 0.7, 0.15))),
                    "fake-vision", "fake-ocr");
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Boolean> ocrRun = null;
        Future<?> finalizer = null;
        try {
            ocrRun = workers.submit(() -> jobs.run(ocr, handler("pdf_ocr")));
            assertThat(ocrEntered.await(5, TimeUnit.SECONDS)).isTrue();
            finalizer = workers.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        finalizerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        holdControlledSessionLock();
                        controlledSessionLockHeld.countDown();
                        jobs.finishChunkEmbeddings(List.of(
                                new ChunkEmbedJobHandler.BatchOutcome(chunk, () -> {}, null)));
                    });
                } finally {
                    finalizerCompleted.countDown();
                }
            });
            assertThat(controlledSessionLockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            awaitFinalizerBlockedByOcrOrCompleted(ocrPid.get(), finalizerPid.get(), finalizerCompleted);
            releaseOcr.countDown();

            List<Throwable> failures = java.util.stream.Stream.of(failure(ocrRun), failure(finalizer))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(failures).allMatch(PdfIndexingIT::isPostgresDeadlock);
            assertThat(failures)
                    .withFailMessage("Expected compatible publication locks, but got %s",
                            failures.stream().map(PdfIndexingIT::failureChain).toList())
                    .isEmpty();
        } finally {
            ocrEntered.countDown();
            releaseOcr.countDown();
            controlledSessionLockHeld.countDown();
            finalizerCompleted.countDown();
            if (ocrRun != null) ocrRun.cancel(true);
            if (finalizer != null) finalizer.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void controlledSchedulerCompletion_doesNotDeadlockWithPdfOcrPublication() throws Exception {
        byte[] source = pdf("x".repeat(79), false);
        UUID material = insertMaterial(source);
        String sourceHash = checksum(source);
        jobs.enqueuePdfMaterial(owner, material);
        JobQueue.ClaimedJob extract = jobs.claim("scheduler-extract", Set.of("pdf_extract"));
        assertThat(jobs.run(extract, handler("pdf_extract"))).isTrue();

        JobQueue.AiJob terminalChunk = jobs.enqueue(JobQueue.EnqueueRequest.material(
                "chunk_embed", owner, course, session, material, 1, sourceHash, "vertex", "fake-768", "none"));
        JobQueue.ClaimedJob terminalChunkClaim = jobs.claim("scheduler-terminal-chunk", Set.of("chunk_embed"));
        assertThat(jobs.run(terminalChunkClaim, handler("chunk_embed"))).isTrue();
        terminalChunk = jobs.get(owner, terminalChunk.id());
        JobQueue.CompletionEvent event = completionEvent(terminalChunk);

        JobQueue.ClaimedJob ocr = jobs.claim("scheduler-ocr", Set.of("pdf_ocr"));
        jdbc.sql("UPDATE ai_jobs SET lease_expires_at=now() + interval '1 minute' WHERE id=:id")
                .param("id", ocr.id()).update();

        CountDownLatch ocrEntered = new CountDownLatch(1);
        CountDownLatch releaseOcr = new CountDownLatch(1);
        CountDownLatch controlledSessionLockHeld = new CountDownLatch(1);
        CountDownLatch schedulerCompleted = new CountDownLatch(1);
        AtomicInteger ocrPid = new AtomicInteger();
        AtomicInteger schedulerPid = new AtomicInteger();
        vision.delegate(ignored -> {
            ocrPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
            ocrEntered.countDown();
            await(releaseOcr);
            return new VisionOcrPort.OcrResult(List.of(
                    new VisionOcrPort.OcrBlock("recognized text", 0.91,
                            new VisionOcrPort.NormalizedBox(0.1, 0.1, 0.7, 0.15))),
                    "fake-vision", "fake-ocr");
        });

        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Boolean> ocrRun = null;
        Future<?> schedulerRun = null;
        try {
            ocrRun = workers.submit(() -> jobs.run(ocr, handler("pdf_ocr")));
            assertThat(ocrEntered.await(5, TimeUnit.SECONDS)).isTrue();
            schedulerRun = workers.submit(() -> {
                try {
                    transactions.executeWithoutResult(ignored -> {
                        schedulerPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        // Controlled reproduction: establishes session-first state, not listener query lock order.
                        holdControlledSessionLock();
                        controlledSessionLockHeld.countDown();
                        generationScheduler.onCompleted(event);
                    });
                } finally {
                    schedulerCompleted.countDown();
                }
            });
            assertThat(controlledSessionLockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            awaitWorkerBlockedByOcrOrCompleted(
                    ocrPid.get(), schedulerPid.get(), schedulerCompleted, "Generation scheduler");
            releaseOcr.countDown();

            List<Throwable> failures = java.util.stream.Stream.of(failure(ocrRun), failure(schedulerRun))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(failures).allMatch(PdfIndexingIT::isPostgresDeadlock);
            assertThat(failures)
                    .withFailMessage("Expected compatible scheduler/OCR locks, but got %s",
                            failures.stream().map(PdfIndexingIT::failureChain).toList())
                    .isEmpty();

            assertThat(jobs.get(owner, ocr.id()).status()).isEqualTo("succeeded");
            assertThat(jobs.get(owner, terminalChunk.id()).status()).isEqualTo("succeeded");
            assertThat(vision.calls()).isOne();
            assertThat(jdbc.sql("SELECT extraction_method FROM document_pages WHERE material_id=:id")
                    .param("id", material).query(String.class).single()).isEqualTo("ocr");
            assertThat(jdbc.sql("SELECT count(*) FROM content_blocks WHERE material_id=:id")
                    .param("id", material).query(Integer.class).single()).isOne();
            assertThat(jdbc.sql("""
                            SELECT operation||':'||status FROM ai_provider_usage
                            WHERE job_id=:job
                            """).param("job", ocr.id()).query(String.class).single())
                    .isEqualTo("vision.ocr:succeeded");
            assertThat(jdbc.sql("""
                            SELECT id::text||':'||status FROM ai_jobs
                            WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                              AND material_id=:material AND job_type='chunk_embed'
                            """).param("owner", owner).param("course", course).param("session", session)
                    .param("material", material).query(String.class).list())
                    .containsExactly(terminalChunk.id() + ":succeeded");
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM chunks
                            WHERE content_block_id IN (
                                SELECT id FROM content_blocks WHERE material_id=:material
                            )
                            """).param("material", material).query(Integer.class).single()).isOne();
            assertThat(jdbc.sql("""
                            SELECT job.status FROM ai_jobs job
                            JOIN chunks chunk ON chunk.owner_id=job.owner_id
                             AND chunk.course_id=job.course_id AND chunk.session_id=job.session_id
                             AND chunk.source_hash=job.source_hash
                            JOIN content_blocks block ON block.id=chunk.content_block_id
                            WHERE block.material_id=:material AND job.job_type='chunk_embed'
                              AND job.material_id IS NULL AND job.exam_resource_id IS NULL
                              AND job.note_id IS NULL AND job.recording_id IS NULL
                            """).param("material", material).query(String.class).list())
                    .containsExactly("queued");
            assertThat(jdbc.sql("""
                            SELECT count(*) FROM ai_jobs
                            WHERE owner_id=:owner AND session_id=:session
                              AND job_type IN ('preview_generate','review_generate')
                            """).param("owner", owner).param("session", session)
                    .query(Integer.class).single()).isZero();
        } finally {
            ocrEntered.countDown();
            releaseOcr.countDown();
            controlledSessionLockHeld.countDown();
            schedulerCompleted.countDown();
            if (ocrRun != null) ocrRun.cancel(true);
            if (schedulerRun != null) schedulerRun.cancel(true);
            workers.shutdownNow();
            workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void holdControlledSessionLock() {
        jdbc.sql("SELECT id FROM class_sessions WHERE id=:id FOR UPDATE")
                .param("id", session).query(UUID.class).single();
    }

    private void awaitFinalizerBlockedByOcrOrCompleted(
            int ocrPid, int finalizerPid, CountDownLatch finalizerCompleted) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (finalizerCompleted.getCount() != 0) {
            boolean blockedByOcr = jdbc.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM pg_stat_activity
                                WHERE pid=:finalizerPid AND wait_event_type='Lock'
                                  AND :ocrPid = ANY(pg_blocking_pids(pid))
                            )
                            """)
                    .param("ocrPid", ocrPid).param("finalizerPid", finalizerPid)
                    .query(Boolean.class).single();
            if (blockedByOcr) return;
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Chunk finalizer neither completed nor blocked behind PDF OCR.");
            }
        }
    }

    private void awaitWorkerBlockedByOcrOrCompleted(
            int ocrPid, int workerPid, CountDownLatch completed, String worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completed.getCount() != 0) {
            boolean blockedByOcr = jdbc.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM pg_stat_activity
                                WHERE pid=:workerPid AND wait_event_type='Lock'
                                  AND :ocrPid = ANY(pg_blocking_pids(pid))
                            )
                            """)
                    .param("ocrPid", ocrPid).param("workerPid", workerPid)
                    .query(Boolean.class).single();
            if (blockedByOcr) return;
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(worker + " neither completed nor blocked behind PDF OCR.");
            }
        }
    }

    private static JobQueue.CompletionEvent completionEvent(JobQueue.AiJob job) {
        return new JobQueue.CompletionEvent(job.id(), job.type(), job.ownerId(), job.courseId(), job.sessionId(),
                job.materialId(), job.examResourceId(), job.noteId(), job.recordingId(), job.examId(),
                job.inputVersion(), job.sourceHash());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Latch interrupted.", exception);
        }
    }

    private static Throwable failure(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return exception;
        } catch (TimeoutException exception) {
            return exception;
        }
    }

    private static boolean isPostgresDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!chain.isEmpty()) chain.append(" -> ");
            chain.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            if (cause instanceof SQLException sql) chain.append(" [SQLState=").append(sql.getSQLState()).append(']');
        }
        return chain.toString();
    }

    private UUID insertMaterial(byte[] pdf) throws Exception {
        UUID id = UUID.randomUUID();
        String key = "material/" + id;
        storage.put(key, pdf);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO materials
                    (id,owner_id,course_id,session_id,source_phase,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,'preview_pdf',:key,'source.pdf','application/pdf',
                        :size,1,:hash,1,'uploaded',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course).param("session", session)
                .param("key", key).param("size", pdf.length).param("hash", checksum(pdf)).param("now", now).update();
        return id;
    }

    private void insertSession(UUID id, int number) {
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id,owner_id,course_id,session_number,title,session_date,created_at,updated_at)
                VALUES (:id,:owner,:course,:number,'Session',DATE '2026-09-01',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course).param("number", number)
                .param("now", Timestamp.from(Instant.now())).update();
    }

    private void runAll(String type) throws Exception {
        JobHandler handler = handler(type);
        JobQueue.ClaimedJob job;
        while ((job = jobs.claim("pdf-it", Set.of(type))) != null) {
            jobs.complete(job, handler.handle(job));
        }
    }

    private JobHandler handler(String type) {
        return handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
    }

    private static byte[] pdf(String text, boolean halfPageImage) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(20, 760);
                content.showText(text);
                content.endText();
                if (halfPageImage) {
                    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D graphics = image.createGraphics();
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, 2, 2);
                    graphics.dispose();
                    content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 612, 396);
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeStorage storage() { return new FakeStorage(); }
        @Bean FakeVision vision() { return new FakeVision(); }
        @Bean ChunkEmbeddingPort embeddings() {
            return text -> new ChunkEmbeddingPort.Embedding(java.util.Collections.nCopies(768, 0.25f), "fake-768");
        }
    }

    static final class FakeStorage implements CloudStoragePort {
        private final Map<String, byte[]> objects = new HashMap<>();
        void put(String key, byte[] bytes) { objects.put(key, bytes.clone()); }
        void clear() { objects.clear(); }
        @Override public URI createUploadUrl(String key, String type, long length, Instant expiresAt) {
            return URI.create("https://storage.invalid/upload");
        }
        @Override public URI createDownloadUrl(String key, Instant expiresAt) {
            return URI.create("https://storage.invalid/download");
        }
        @Override public StoredObjectMetadata metadata(String key) { return null; }
        @Override public void delete(String key) { objects.remove(key); }
        @Override public byte[] read(String key) { return objects.get(key).clone(); }
    }

    static final class FakeVision implements VisionOcrPort {
        private final AtomicInteger calls = new AtomicInteger();
        private List<OcrBlock> blocks = List.of(block("recognized text", 0));
        private OcrProviderException failure;
        private VisionOcrPort delegate;
        @Override public OcrResult extract(byte[] image) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            if (delegate != null) return delegate.extract(image);
            return new OcrResult(blocks, "fake-vision", "fake-ocr");
        }
        int calls() { return calls.get(); }
        void blocks(String... texts) {
            blocks = java.util.stream.IntStream.range(0, texts.length)
                    .mapToObj(index -> block(texts[index], index)).toList();
        }
        void reset() {
            calls.set(0);
            blocks = List.of(block("recognized text", 0));
            failure = null;
            delegate = null;
        }
        void fail(OcrProviderException value) { failure = value; }
        void delegate(VisionOcrPort value) { delegate = value; }
        private static OcrBlock block(String text, int index) {
            return new OcrBlock(text, 0.91, new NormalizedBox(0.1, 0.1 + index * 0.2, 0.7, 0.15));
        }
    }
}
