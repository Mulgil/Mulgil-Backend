package com.mulgil.recording;

import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.stt.SpeechToTextPort;
import com.mulgil.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "JOB_POLL_INTERVAL_MILLIS=600000")
@Import(RecordingWorkflowIT.Fakes.class)
class RecordingWorkflowIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mulgil.ai-rates.speech-second-microusd", () -> 3);
    }

    @Autowired JdbcClient jdbc;
    @Autowired RecordingMappingService mappings;
    @Autowired RecordingRetrievalService retrieval;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired FakeSegmenter segmenter;
    @Autowired FakeTemporaryObjects temporaryObjects;
    @Autowired FakeSpeech speech;
    @Autowired SpeechInputCleanupScheduler inputCleanup;

    UUID owner;
    UUID course;
    UUID session;

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM speech_input_cleanups").update();
        jdbc.sql("DELETE FROM users").update();
        segmenter.reset();
        temporaryObjects.reset();
        speech.reset();
        owner = UUID.randomUUID();
        course = UUID.randomUUID();
        session = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO users VALUES (:id,'google',:subject,:email,'Owner',:now)")
                .param("id", owner).param("subject", owner.toString()).param("email", owner + "@example.com")
                .param("now", now).update();
        jdbc.sql("INSERT INTO courses VALUES (:id,:owner,'Course',NULL,NULL,:now,:now)")
                .param("id", course).param("owner", owner).param("now", now).update();
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id,owner_id,course_id,session_number,title,session_date,created_at,updated_at)
                VALUES (:id,:owner,:course,1,'Session',DATE '2026-09-01',:now,:now)
                """).param("id", session).param("owner", owner).param("course", course).param("now", now).update();
    }

    @Test
    void serializesMappingAndStitchesTranscript_whenConcurrentRequestsCrossSessionLimit() throws Exception {
        UUID first = recording(6_000, "uploaded");
        UUID second = recording(6_000, "uploaded");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<String> firstResult = pool.submit(() -> confirmAfter(start, first));
            Future<String> secondResult = pool.submit(() -> confirmAfter(start, second));
            start.countDown();
            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder("accepted", "UPLOAD_LIMIT_EXCEEDED");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM audio_recordings WHERE session_id=:session")
                .param("session", session).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type='stt'")
                .query(Integer.class).single()).isOne();

        runAll("stt");
        runAll("chunk_embed");

        assertThat(jdbc.sql("SELECT start_ms,end_ms FROM transcript_segments ORDER BY start_ms")
                .query((row, ignored) -> List.of(row.getLong(1), row.getLong(2))).list())
                .containsExactly(List.of(100L, 500L), List.of(1_140_100L, 1_140_500L));
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE embedding IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(segmenter.maxSegmentSeconds()).isEqualTo(1_140);
        assertThat(temporaryObjects.puts()).isEqualTo(2);
        assertThat(temporaryObjects.deletes()).isEqualTo(2);
        System.out.println("RECORDING_WORKFLOW scenario=concurrent_mapping_stt observable=one_mapping_two_offset_segments_clean result=PASS");
    }

    @Test
    void gatesUnconfirmedAndRejectedRecordings_andPrefiltersRetrievalScope() throws Exception {
        assertThatThrownBy(() -> invalidRecording("audio/wav", 10_800))
                .hasMessageContaining("audio_recordings_mime_type_check");
        assertThatThrownBy(() -> invalidRecording("audio/m4a", 10_801))
                .hasMessageContaining("audio_recordings_duration_seconds_check");
        UUID unconfirmed = recording(1_200, "uploaded");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE recording_id=:id")
                .param("id", unconfirmed).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM chunks").query(Integer.class).single()).isZero();

        UUID confirmed = recording(1_200, "uploaded");
        mappings.confirm(owner, confirmed, session);
        runAll("stt");
        runAll("chunk_embed");

        assertThat(retrieval.search(owner, course, session, "lecture", 5)).hasSize(2);
        UUID foreignOwner = UUID.randomUUID();
        assertThat(retrieval.search(foreignOwner, course, session, "lecture", 5)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_usage WHERE owner_id=:owner AND job_id IS NULL "
                        + "AND operation='vertex.embed'").param("owner", owner)
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_usage WHERE owner_id=:owner AND job_id IS NULL "
                        + "AND operation='vertex.embed'").param("owner", foreignOwner)
                .query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_jobs WHERE job_type IN ('review_generate','preview_generate')")
                .query(Integer.class).single()).isOne();
        System.out.println("RECORDING_WORKFLOW scenario=audio_boundaries_isolation observable=unconfirmed_zero_jobs_foreign_zero_chunks_review_queued result=PASS");
    }

    @Test
    void startsOneResumableOperationPerSegment_whenRecordingExceeds19Minutes() throws Exception {
        UUID recording = recording(1_200, "uploaded");
        mappings.confirm(owner, recording, session);

        runAll("stt");

        assertThat(speech.starts()).isEqualTo(2);
        assertThat(speech.polls()).isEqualTo(3);
        assertThat(speech.inputsPresentDuringTimeout()).isTrue();
        assertThat(temporaryObjects.deletes()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT provider_request_id FROM ai_jobs WHERE recording_id=:recording")
                .param("recording", recording).query(String.class).single()).isEqualTo("done|1");
        assertThat(jdbc.sql("SELECT status FROM ai_jobs WHERE recording_id=:recording")
                .param("recording", recording).query(String.class).single()).isEqualTo("succeeded");
        assertThat(jdbc.sql("""
                        SELECT operation||':'||status||':'||count(*) FROM ai_provider_usage
                        WHERE job_id=(SELECT id FROM ai_jobs WHERE recording_id=:recording)
                        GROUP BY operation,status ORDER BY operation,status
                        """).param("recording", recording).query(String.class).list())
                .containsExactly("speech.recognize:succeeded:2");
        System.out.println("RECORDING_WORKFLOW scenario=multi_segment_timeout observable=one_operation_per_segment_no_duplicate_terminal_cleanup result=PASS");
    }

    @Test
    void resumesPersistedProviderOperation_withoutStartingAnotherPaidOperation() throws Exception {
        UUID recording = recording(1_200, "uploaded");
        JobQueue.JobAccepted accepted = mappings.confirm(owner, recording, session);
        jdbc.sql("UPDATE ai_jobs SET provider_request_id='operation|1|operations/existing' WHERE id=:id")
                .param("id", accepted.jobId()).update();

        runAll("stt");

        assertThat(speech.starts()).isZero();
        assertThat(speech.awaitedOperations()).contains("operations/existing");
        assertThat(temporaryObjects.deletes()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM ai_provider_usage WHERE job_id=:job")
                .param("job", accepted.jobId()).query(String.class).single()).isEqualTo("succeeded");
        System.out.println("RECORDING_WORKFLOW scenario=provider_operation_resume observable=zero_duplicate_starts_terminal_cleanup result=PASS");
    }

    @Test
    void keepsOneStartedUsageAcrossTransientAwaitRetry_thenTerminalizesSucceeded() throws Exception {
        UUID recording = recording(1_200, "uploaded");
        JobQueue.JobAccepted accepted = mappings.confirm(owner, recording, session);
        speech.failNextAwait();
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals("stt"))
                .findFirst().orElseThrow();
        JobQueue.ClaimedJob firstClaim = jobs.claim("transient-await-1", Set.of("stt"));

        try {
            handler.handle(firstClaim);
            throw new AssertionError("Expected transient speech await failure.");
        } catch (JobHandler.JobExecutionException exception) {
            assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(exception.retryable()).isTrue();
            jobs.fail(firstClaim, exception.code(), exception.getMessage(), true);
        }

        String providerState = jdbc.sql("SELECT provider_request_id FROM ai_jobs WHERE id=:job")
                .param("job", accepted.jobId()).query(String.class).single();
        UUID usageId = UUID.fromString(providerState.split("\\|", 4)[3]);
        assertThat(jdbc.sql("SELECT status FROM ai_provider_usage WHERE id=:id")
                .param("id", usageId).query(String.class).single()).isEqualTo("started");

        jobs.retry(owner, accepted.jobId());
        JobQueue.ClaimedJob resumed = jobs.claim("transient-await-2", Set.of("stt"));
        assertThat(jobs.complete(resumed, handler.handle(resumed))).isTrue();

        assertThat(speech.starts()).isEqualTo(2);
        assertThat(speech.awaitedOperations().stream().filter("operations/fake-1"::equals).count()).isEqualTo(3);
        assertThat(jdbc.sql("""
                        SELECT status||':'||unit_type||':'||unit_count||':'||estimated_cost_microusd
                        FROM ai_provider_usage WHERE id=:id
                        """).param("id", usageId).query(String.class).single())
                .isEqualTo("succeeded:audio_second:1140:3420");
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_usage WHERE job_id=:job")
                .param("job", accepted.jobId()).query(Integer.class).single()).isEqualTo(2);
        System.out.println("RECORDING_WORKFLOW scenario=transient_await_resume "
                + "observable=one_usage_same_operation_terminal_success result=PASS");
    }

    @Test
    void terminalizesUsageFailed_whenSpeechOperationFailsTerminally() throws Exception {
        UUID recording = recording(1_200, "uploaded");
        JobQueue.JobAccepted accepted = mappings.confirm(owner, recording, session);
        speech.failNextAwaitTerminally();
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals("stt"))
                .findFirst().orElseThrow();
        JobQueue.ClaimedJob claimed = jobs.claim("terminal-await", Set.of("stt"));

        try {
            handler.handle(claimed);
            throw new AssertionError("Expected terminal speech failure.");
        } catch (JobHandler.JobExecutionException exception) {
            assertThat(exception.code()).isEqualTo("PROVIDER_FAILED");
            assertThat(exception.retryable()).isFalse();
            jobs.fail(claimed, exception.code(), exception.getMessage(), false);
        }

        assertThat(jdbc.sql("SELECT status||':'||error_code FROM ai_provider_usage WHERE job_id=:job")
                .param("job", accepted.jobId()).query(String.class).single())
                .isEqualTo("failed:PROVIDER_FAILED");
    }

    @Test
    void terminalizesSameUsage_whenTransientAwaitFailsOnFinalAttempt() throws Exception {
        UUID recording = recording(600, "uploaded");
        JobQueue.JobAccepted accepted = mappings.confirm(owner, recording, session);
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals("stt"))
                .findFirst().orElseThrow();
        UUID usageId = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            speech.failNextAwait();
            JobQueue.ClaimedJob claimed = jobs.claim("exhaustion-" + attempt, Set.of("stt"));
            assertThat(claimed.maxAttempts()).isEqualTo(3);
            try {
                handler.handle(claimed);
                throw new AssertionError("Expected transient speech await failure.");
            } catch (JobHandler.JobExecutionException exception) {
                assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                assertThat(exception.retryable()).isTrue();
                jobs.fail(claimed, exception.code(), exception.getMessage(), true);
            }
            String providerState = jdbc.sql("SELECT provider_request_id FROM ai_jobs WHERE id=:job")
                    .param("job", accepted.jobId()).query(String.class).single();
            UUID currentUsageId = UUID.fromString(providerState.split("\\|", 4)[3]);
            if (usageId == null) usageId = currentUsageId;
            assertThat(currentUsageId).isEqualTo(usageId);
            assertThat(jdbc.sql("SELECT status FROM ai_provider_usage WHERE id=:id")
                    .param("id", usageId).query(String.class).single())
                    .isEqualTo(attempt < 3 ? "started" : "failed");
            if (attempt < 3) jobs.retry(owner, accepted.jobId());
        }

        assertThat(speech.starts()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM ai_provider_usage WHERE job_id=:job")
                .param("job", accepted.jobId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT error_code||':'||(finished_at IS NOT NULL)||':'||(latency_ms IS NOT NULL)
                        FROM ai_provider_usage WHERE id=:id
                        """).param("id", usageId).query(String.class).single())
                .isEqualTo("PROVIDER_UNAVAILABLE:true:true");
        assertThatThrownBy(() -> jobs.retry(owner, accepted.jobId()))
                .isInstanceOf(ApiException.class)
                .extracting(value -> ((ApiException) value).code())
                .isEqualTo("JOB_NOT_RETRYABLE");
        System.out.println("RECORDING_WORKFLOW scenario=transient_await_exhaustion "
                + "observable=one_usage_one_start_terminal_safe_failure result=PASS");
    }

    @Test
    void retainsInputsAfterAmbiguousStart_thenDeletesThemAfterProviderBound() throws Exception {
        UUID recording = recording(1_200, "uploaded");
        mappings.confirm(owner, recording, session);
        speech.failNextStart();
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals("stt"))
                .findFirst().orElseThrow();
        JobQueue.ClaimedJob job = jobs.claim("recording-it", Set.of("stt"));

        try {
            handler.handle(job);
            throw new AssertionError("Expected ambiguous provider start failure.");
        } catch (JobHandler.JobExecutionException exception) {
            assertThat(exception.code()).isEqualTo("PROVIDER_START_UNKNOWN");
            jobs.fail(job, exception.code(), exception.getMessage(), exception.retryable());
        }

        assertThat(temporaryObjects.deletes()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM speech_input_cleanups WHERE job_id=:job")
                .param("job", job.id()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("""
                        SELECT not_before>now()+interval '24 hours' AND cardinality(object_uris)=2
                        FROM speech_input_cleanups WHERE job_id=:job
                        """).param("job", job.id()).query(Boolean.class).single()).isTrue();
        inputCleanup.cleanupDue();
        assertThat(temporaryObjects.deletes()).isZero();
        jdbc.sql("DELETE FROM ai_jobs WHERE id=:job").param("job", job.id()).update();
        jdbc.sql("""
                        UPDATE speech_input_cleanups
                        SET created_at=now()-interval '26 hours',not_before=now()-interval '1 second'
                        WHERE job_id=:job
                        """)
                .param("job", job.id()).update();

        inputCleanup.cleanupDue();

        assertThat(temporaryObjects.deletes()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM speech_input_cleanups WHERE job_id=:job")
                .param("job", job.id()).query(Integer.class).single()).isZero();
        System.out.println("RECORDING_WORKFLOW scenario=ambiguous_start_cleanup observable=retained_before_24h_deleted_after_bound_without_job result=PASS");
    }

    private String confirmAfter(CountDownLatch start, UUID recording) throws InterruptedException {
        start.await();
        try {
            mappings.confirm(owner, recording, session);
            return "accepted";
        } catch (ApiException expected) {
            return expected.code();
        }
    }

    private void invalidRecording(String mimeType, long duration) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO audio_recordings
                    (id,owner_id,object_key,original_filename,mime_type,byte_size,checksum,started_at,
                     duration_seconds,version,status,created_at,updated_at)
                VALUES (:id,:owner,:key,'invalid.m4a',:mime,100,:hash,:now,:duration,1,'uploaded',:now,:now)
                """).param("id", id).param("owner", owner).param("key", "invalid/" + id)
                .param("mime", mimeType).param("hash", "f".repeat(64)).param("duration", duration)
                .param("now", now).update();
    }

    private UUID recording(long duration, String status) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("""
                INSERT INTO audio_recordings
                    (id,owner_id,object_key,original_filename,mime_type,byte_size,checksum,started_at,
                     duration_seconds,version,status,created_at,updated_at)
                VALUES (:id,:owner,:key,'lecture.m4a','audio/m4a',100,:hash,:now,:duration,1,:status,:now,:now)
                """).param("id", id).param("owner", owner).param("key", "owner/" + owner + "/recording/" + id + "/source.m4a")
                .param("hash", "a".repeat(64)).param("duration", duration).param("status", status)
                .param("now", now).update();
        return id;
    }

    private void runAll(String type) throws Exception {
        JobHandler handler = handlers.stream().filter(value -> value.jobType().equals(type)).findFirst().orElseThrow();
        JobQueue.ClaimedJob job;
        while ((job = jobs.claim("recording-it", Set.of(type))) != null) {
            jobs.complete(job, handler.handle(job));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary FakeSegmenter segmenter() { return new FakeSegmenter(); }
        @Bean @Primary FakeTemporaryObjects temporaryObjects() { return new FakeTemporaryObjects(); }
        @Bean @Primary FakeSpeech speech(FakeTemporaryObjects objects) { return new FakeSpeech(objects); }
        @Bean @Primary ChunkEmbeddingPort embeddings() {
            return text -> new ChunkEmbeddingPort.Embedding(Collections.nCopies(768, 0.25f), "fake-768");
        }
    }

    static final class FakeSegmenter implements RecordingSegmenter {
        private final AtomicInteger maxSegmentSeconds = new AtomicInteger();
        @Override public List<AudioSegment> split(String objectKey, Duration duration, Duration maxSegmentDuration) {
            maxSegmentSeconds.set((int) maxSegmentDuration.toSeconds());
            return List.of(new AudioSegment(Path.of("segment-0.m4a"), Duration.ZERO),
                    new AudioSegment(Path.of("segment-1.m4a"), maxSegmentDuration));
        }
        @Override public void cleanup(List<AudioSegment> segments) {}
        int maxSegmentSeconds() { return maxSegmentSeconds.get(); }
        void reset() { maxSegmentSeconds.set(0); }
    }

    static final class FakeTemporaryObjects implements SpeechTemporaryObjectPort {
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        @Override public URI put(UUID jobId, int segmentIndex, Path segment) {
            puts.incrementAndGet();
            return URI.create("gs://fake-bucket/stt/" + jobId + "/" + segmentIndex + ".m4a");
        }
        @Override public void delete(URI uri) { deletes.incrementAndGet(); }
        int puts() { return puts.get(); }
        int deletes() { return deletes.get(); }
        int active() { return puts.get() - deletes.get(); }
        void reset() { puts.set(0); deletes.set(0); }
    }

    static final class FakeSpeech implements SpeechToTextPort {
        private final FakeTemporaryObjects objects;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger polls = new AtomicInteger();
        private boolean inputsPresentDuringTimeout;
        private boolean failNextStart;
        private boolean failNextAwait;
        private boolean failNextAwaitTerminally;
        private final List<String> awaitedOperations = new java.util.ArrayList<>();

        FakeSpeech(FakeTemporaryObjects objects) { this.objects = objects; }

        @Override public String start(Input input) {
            int start = starts.incrementAndGet();
            if (failNextStart) {
                failNextStart = false;
                throw new IllegalStateException("Ambiguous provider start.");
            }
            return "operations/fake-" + start;
        }

        @Override public Optional<Transcript> await(String operationId, Input input, Duration pollTimeout) {
            awaitedOperations.add(operationId);
            if (failNextAwait) {
                failNextAwait = false;
                throw new IllegalStateException("Transient provider await failure.");
            }
            if (failNextAwaitTerminally) {
                failNextAwaitTerminally = false;
                throw new TerminalOperationException("Terminal provider operation failure.");
            }
            if (polls.incrementAndGet() == 1) {
                inputsPresentDuringTimeout = objects.active() == 2;
                return Optional.empty();
            }
            long offset = input.offset().toMillis();
            return Optional.of(new Transcript(List.of(
                    new Segment(offset + 100, offset + 500, "segment " + input.offset().toSeconds(), null)),
                    "fake-chirp", "chirp_3"));
        }

        int starts() { return starts.get(); }
        int polls() { return polls.get(); }
        boolean inputsPresentDuringTimeout() { return inputsPresentDuringTimeout; }
        List<String> awaitedOperations() { return List.copyOf(awaitedOperations); }
        void failNextStart() { failNextStart = true; }
        void failNextAwait() { failNextAwait = true; }
        void failNextAwaitTerminally() { failNextAwaitTerminally = true; }
        void reset() {
            starts.set(0);
            polls.set(0);
            inputsPresentDuringTimeout = false;
            failNextStart = false;
            failNextAwait = false;
            failNextAwaitTerminally = false;
            awaitedOperations.clear();
        }
    }
}
