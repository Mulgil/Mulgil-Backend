package com.mulgil.recording;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.stt.SpeechToTextPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
final class RecordingSttJobHandler implements JobHandler {
    private static final Duration MAX_CHIRP_SEGMENT = Duration.ofMinutes(19);

    private final JdbcClient jdbc;
    private final ContentIndexingService indexing;
    private final ObjectProvider<RecordingSegmenter> segmenters;
    private final ObjectProvider<SpeechTemporaryObjectPort> temporaryObjects;
    private final ObjectProvider<SpeechToTextPort> speech;
    private final MulgilProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final SpeechInputCleanupScheduler inputCleanup;
    private final AiProviderUsageLedger usage;

    RecordingSttJobHandler(JdbcClient jdbc, ContentIndexingService indexing,
                           ObjectProvider<RecordingSegmenter> segmenters,
                           ObjectProvider<SpeechTemporaryObjectPort> temporaryObjects,
                           ObjectProvider<SpeechToTextPort> speech, MulgilProperties properties, Clock clock,
                           TransactionTemplate transactions, SpeechInputCleanupScheduler inputCleanup,
                           AiProviderUsageLedger usage) {
        this.jdbc = jdbc;
        this.indexing = indexing;
        this.segmenters = segmenters;
        this.temporaryObjects = temporaryObjects;
        this.speech = speech;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
        this.inputCleanup = inputCleanup;
        this.usage = usage;
    }

    @Override
    public String jobType() {
        return "stt";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        RecordingSegmenter segmenter = require(segmenters.getIfAvailable(), "Audio segmenter");
        SpeechTemporaryObjectPort objects = require(temporaryObjects.getIfAvailable(), "Speech object store");
        SpeechToTextPort transcriber = require(speech.getIfAvailable(), "Speech provider");
        Recording recording = jdbc.sql("""
                        SELECT object_key,duration_seconds,status FROM audio_recordings
                        WHERE id=:id AND owner_id=:owner AND course_id=:course AND session_id=:session
                          AND status IN ('queued','running')
                        """).param("id", job.recordingId()).param("owner", job.ownerId())
                .param("course", job.courseId()).param("session", job.sessionId())
                .query((row, ignored) -> new Recording(row.getString("object_key"),
                        row.getLong("duration_seconds"))).optional()
                .orElseThrow(() -> new JobExecutionException("STALE_INPUT", "Recording is not confirmed.", false));
        Duration configured = Duration.ofSeconds(properties.uploads().sttSegmentDurationSeconds());
        Duration maximum = configured.compareTo(MAX_CHIRP_SEGMENT) <= 0 ? configured : MAX_CHIRP_SEGMENT;
        List<RecordingSegmenter.AudioSegment> segments;
        try {
            segments = segmenter.split(recording.objectKey(), Duration.ofSeconds(recording.durationSeconds()), maximum);
        } catch (RuntimeException exception) {
            throw new JobExecutionException("AUDIO_SEGMENT_FAILED", "Audio segmentation failed.", false);
        }
        List<URI> inputUris = new ArrayList<>();
        Set<URI> deletedInputUris = new HashSet<>();
        boolean allInputsTerminal = false;
        try {
            for (int index = 0; index < segments.size(); index++) {
                inputUris.add(objects.put(job.id(), index, segments.get(index).path()));
            }
            ProviderState state = ProviderState.parse(providerRequestId(job.id()));
            for (int index = 0; index < segments.size(); index++) {
                URI inputUri = inputUris.get(index);
                if (state.completedThrough(index)) {
                    objects.delete(inputUri);
                    deletedInputUris.add(inputUri);
                    continue;
                }
                SpeechToTextPort.Input input = new SpeechToTextPort.Input(inputUri, segments.get(index).offset());
                String operationId;
                long endSeconds = index + 1 < segments.size()
                        ? segments.get(index + 1).offset().toSeconds() : recording.durationSeconds();
                long unitCount = Math.max(0, endSeconds - segments.get(index).offset().toSeconds());
                AiProviderUsageLedger.UsageHandle providerUsage;
                if (state.resumes(index)) {
                    operationId = state.operationId();
                    if (state.usageId() == null) {
                        providerUsage = usage.begin(job.id(), job.ownerId(), "speech.recognize", "google-chirp",
                                properties.speech().model(), "audio_second", unitCount);
                        ProviderState upgraded = ProviderState.active(index, operationId, providerUsage.id());
                        persistProviderRequestId(job, state.raw(), upgraded.raw());
                        state = upgraded;
                    } else {
                        providerUsage = new AiProviderUsageLedger.UsageHandle(state.usageId(), job.id(),
                                "speech.recognize", "google-chirp", properties.speech().model(), unitCount);
                    }
                } else {
                    inputCleanup.schedule(job.id(), job.ownerId(), inputUris);
                    providerUsage = usage.begin(job.id(), job.ownerId(), "speech.recognize", "google-chirp",
                            properties.speech().model(), "audio_second", unitCount);
                    try {
                        operationId = transcriber.start(input);
                        ProviderState active = ProviderState.active(index, operationId, providerUsage.id());
                        persistProviderRequestId(job, state.raw(), active.raw());
                        state = active;
                    } catch (RuntimeException exception) {
                        usage.fail(providerUsage, "PROVIDER_START_UNKNOWN");
                        throw new JobExecutionException("PROVIDER_START_UNKNOWN",
                                "Speech operation start could not be confirmed.", false);
                    }
                }
                Optional<SpeechToTextPort.Transcript> result;
                try {
                    do {
                        result = transcriber.await(operationId, input,
                                Duration.ofSeconds(properties.jobs().providerTimeoutSeconds()));
                    } while (result.isEmpty());
                } catch (SpeechToTextPort.TerminalOperationException exception) {
                    usage.fail(providerUsage, "PROVIDER_FAILED");
                    throw exception;
                } catch (RuntimeException exception) {
                    if (job.attemptCount() >= job.maxAttempts()) {
                        usage.fail(providerUsage, "PROVIDER_UNAVAILABLE");
                    }
                    throw exception;
                }
                SpeechToTextPort.Transcript transcript = result.orElseThrow();
                usage.succeed(providerUsage);
                ProviderState completed = ProviderState.completed(index);
                checkpoint(job, state.raw(), completed.raw(), transcript);
                state = completed;
                objects.delete(inputUri);
                deletedInputUris.add(inputUri);
            }
            inputCleanup.completed(job.id(), job.ownerId());
        } catch (SpeechToTextPort.TerminalOperationException exception) {
            allInputsTerminal = true;
            throw new JobExecutionException("PROVIDER_FAILED", "Speech provider rejected the operation.", false);
        } catch (RuntimeException exception) {
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Speech provider failed.", true);
        } finally {
            try {
                if (allInputsTerminal) {
                    inputUris.stream().filter(uri -> !deletedInputUris.contains(uri)).forEach(objects::delete);
                    inputCleanup.completed(job.id(), job.ownerId());
                }
            } finally {
                segmenter.cleanup(segments);
            }
        }
        return () -> publish(job);
    }

    private String providerRequestId(UUID jobId) {
        return jdbc.sql("SELECT provider_request_id FROM ai_jobs WHERE id=:id")
                .param("id", jobId).query(String.class).optional().orElse(null);
    }

    private void persistProviderRequestId(JobQueue.ClaimedJob job, String expected, String operationId) {
        int updated = jdbc.sql("""
                        UPDATE ai_jobs SET provider_request_id=:operation
                        WHERE id=:id AND status='running' AND claimed_by=:worker
                          AND provider_request_id IS NOT DISTINCT FROM :expected
                        """).param("operation", operationId).param("id", job.id())
                .param("worker", job.claimedBy()).param("expected", expected).update();
        if (updated != 1) throw new IllegalStateException("Could not persist speech operation ID.");
    }

    private void checkpoint(JobQueue.ClaimedJob job, String expected, String completed,
                            SpeechToTextPort.Transcript transcript) {
        transactions.executeWithoutResult(ignored -> {
            for (SpeechToTextPort.Segment value : transcript.segments()) {
                insertTranscript(job, new PreparedSegment(value.startMs(), value.endMs(), value.text(),
                        value.confidence(), transcript.provider(), transcript.model()));
            }
            persistProviderRequestId(job, expected, completed);
        });
    }

    private void insertTranscript(JobQueue.ClaimedJob job, PreparedSegment segment) {
        String hash = ContentIndexingService.sha256(job.recordingId() + ":" + segment.startMs() + ":"
                + segment.endMs() + ":" + segment.text());
        UUID id = UUID.nameUUIDFromBytes((job.recordingId() + ":" + segment.startMs() + ":"
                + segment.endMs()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO transcript_segments
                    (id,owner_id,course_id,session_id,recording_id,start_ms,end_ms,text_content,
                     confidence,provider,model_id,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:recording,:start,:end,:text,
                        :confidence,:provider,:model,:hash,:now)
                ON CONFLICT (id) DO UPDATE SET text_content=EXCLUDED.text_content,
                    confidence=EXCLUDED.confidence,provider=EXCLUDED.provider,
                    model_id=EXCLUDED.model_id,source_hash=EXCLUDED.source_hash
                """).param("id", id).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("recording", job.recordingId())
                .param("start", segment.startMs()).param("end", segment.endMs()).param("text", segment.text())
                .param("confidence", segment.confidence()).param("provider", segment.provider())
                .param("model", segment.model()).param("hash", hash)
                .param("now", Timestamp.from(clock.instant())).update();
    }

    private void publish(JobQueue.ClaimedJob job) {
        List<PreparedSegment> segments = jdbc.sql("""
                        SELECT start_ms,end_ms,text_content,confidence,provider,model_id
                        FROM transcript_segments WHERE recording_id=:recording ORDER BY start_ms,id
                        """).param("recording", job.recordingId())
                .query((row, ignored) -> new PreparedSegment(row.getLong("start_ms"), row.getLong("end_ms"),
                        row.getString("text_content"), row.getBigDecimal("confidence") == null ? null
                        : row.getBigDecimal("confidence").doubleValue(),
                        row.getString("provider"), row.getString("model_id"))).list();
        for (PreparedSegment segment : segments) {
            UUID id = UUID.nameUUIDFromBytes((job.recordingId() + ":" + segment.startMs() + ":"
                    + segment.endMs()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            indexing.index(new ContentIndexingService.IndexRequest("transcript", Map.of(
                    "sourceType", "transcript", "recordingId", job.recordingId().toString(),
                    "transcriptSegmentId", id.toString(), "startMs", segment.startMs(), "endMs", segment.endMs(),
                    "inputVersion", job.inputVersion()), job.ownerId(), job.courseId(), job.sessionId(),
                    job.inputVersion(), segment.text()));
        }
        jdbc.sql("UPDATE audio_recordings SET status='succeeded',updated_at=:now WHERE id=:id AND owner_id=:owner")
                .param("now", Timestamp.from(clock.instant())).param("id", job.recordingId())
                .param("owner", job.ownerId()).update();
    }

    private static <T> T require(T value, String name) throws JobExecutionException {
        if (value == null) throw new JobExecutionException("PROVIDER_UNAVAILABLE", name + " unavailable.", true);
        return value;
    }

    private record Recording(String objectKey, long durationSeconds) {}
    private record PreparedSegment(long startMs, long endMs, String text, Double confidence,
                                   String provider, String model) {}

    private record ProviderState(String raw, Integer completedIndex, Integer activeIndex, String operationId,
                                 UUID usageId) {
        static ProviderState parse(String raw) {
            if (raw == null) return new ProviderState(null, null, null, null, null);
            String[] parts = raw.split("\\|", 4);
            try {
                if (parts.length == 2 && parts[0].equals("done")) {
                    return completed(Integer.parseInt(parts[1]));
                }
                if ((parts.length == 3 || parts.length == 4) && parts[0].equals("operation")
                        && !parts[2].isBlank()) {
                    UUID usage = parts.length == 4 ? UUID.fromString(parts[3]) : null;
                    return new ProviderState(raw, null, Integer.parseInt(parts[1]), parts[2], usage);
                }
            } catch (NumberFormatException ignored) {
            }
            throw new IllegalStateException("Invalid persisted speech operation state.");
        }

        static ProviderState active(int index, String operationId, UUID usageId) {
            return new ProviderState("operation|" + index + "|" + operationId + "|" + usageId,
                    null, index, operationId, usageId);
        }

        static ProviderState completed(int index) {
            return new ProviderState("done|" + index, index, null, null, null);
        }

        boolean completedThrough(int index) {
            return (completedIndex != null && completedIndex >= index) || (activeIndex != null && activeIndex > index);
        }

        boolean resumes(int index) {
            return activeIndex != null && activeIndex == index;
        }
    }
}
