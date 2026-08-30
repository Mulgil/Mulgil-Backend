package com.mulgil.recording;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.stt.SpeechToTextPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    RecordingSttJobHandler(JdbcClient jdbc, ContentIndexingService indexing,
                           ObjectProvider<RecordingSegmenter> segmenters,
                           ObjectProvider<SpeechTemporaryObjectPort> temporaryObjects,
                           ObjectProvider<SpeechToTextPort> speech, MulgilProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.indexing = indexing;
        this.segmenters = segmenters;
        this.temporaryObjects = temporaryObjects;
        this.speech = speech;
        this.properties = properties;
        this.clock = clock;
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
        List<PreparedSegment> prepared = new ArrayList<>();
        List<URI> inputUris = new ArrayList<>();
        boolean terminal = false;
        try {
            for (int index = 0; index < segments.size(); index++) {
                inputUris.add(objects.put(job.id(), index, segments.get(index).path()));
            }
            List<SpeechToTextPort.Input> inputs = java.util.stream.IntStream.range(0, segments.size())
                    .mapToObj(index -> new SpeechToTextPort.Input(inputUris.get(index), segments.get(index).offset()))
                    .toList();
            String operationId = providerRequestId(job.id());
            if (operationId == null) {
                try {
                    operationId = transcriber.start(inputs);
                    persistProviderRequestId(job, operationId);
                } catch (RuntimeException exception) {
                    throw new JobExecutionException("PROVIDER_START_UNKNOWN",
                            "Speech operation start could not be confirmed.", false);
                }
            }
            Optional<SpeechToTextPort.Transcript> result;
            do {
                result = transcriber.await(operationId, inputs,
                        Duration.ofSeconds(properties.jobs().providerTimeoutSeconds()));
            } while (result.isEmpty());
            terminal = true;
            SpeechToTextPort.Transcript transcript = result.orElseThrow();
            for (SpeechToTextPort.Segment value : transcript.segments()) {
                prepared.add(new PreparedSegment(value.startMs(), value.endMs(), value.text(), value.confidence(),
                        transcript.provider(), transcript.model()));
            }
        } catch (SpeechToTextPort.TerminalOperationException exception) {
            terminal = true;
            throw new JobExecutionException("PROVIDER_FAILED", "Speech provider rejected the operation.", false);
        } catch (RuntimeException exception) {
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Speech provider failed.", true);
        } finally {
            if (terminal) inputUris.forEach(objects::delete);
            segmenter.cleanup(segments);
        }
        return () -> publish(job, prepared);
    }

    private String providerRequestId(UUID jobId) {
        return jdbc.sql("SELECT provider_request_id FROM ai_jobs WHERE id=:id")
                .param("id", jobId).query(String.class).optional().orElse(null);
    }

    private void persistProviderRequestId(JobQueue.ClaimedJob job, String operationId) {
        int updated = jdbc.sql("""
                        UPDATE ai_jobs SET provider_request_id=:operation
                        WHERE id=:id AND status='running' AND claimed_by=:worker AND provider_request_id IS NULL
                        """).param("operation", operationId).param("id", job.id())
                .param("worker", job.claimedBy()).update();
        if (updated != 1) throw new IllegalStateException("Could not persist speech operation ID.");
    }

    private void publish(JobQueue.ClaimedJob job, List<PreparedSegment> segments) {
        jdbc.sql("DELETE FROM transcript_segments WHERE recording_id=:recording")
                .param("recording", job.recordingId()).update();
        for (PreparedSegment segment : segments) {
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
                    """).param("id", id).param("owner", job.ownerId()).param("course", job.courseId())
                    .param("session", job.sessionId()).param("recording", job.recordingId())
                    .param("start", segment.startMs()).param("end", segment.endMs()).param("text", segment.text())
                    .param("confidence", segment.confidence()).param("provider", segment.provider())
                    .param("model", segment.model()).param("hash", hash)
                    .param("now", Timestamp.from(clock.instant())).update();
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
}
