package com.mulgil.recording;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.job.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

@Service
class RecordingMappingService {
    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final MulgilProperties properties;
    private final Clock clock;

    RecordingMappingService(JdbcClient jdbc, JobQueue jobs, MulgilProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    JobQueue.JobAccepted confirm(UUID ownerId, UUID recordingId, UUID sessionId) {
        Session session = jdbc.sql("""
                        SELECT id, course_id FROM class_sessions
                        WHERE owner_id=:owner AND id=:session FOR UPDATE
                        """).param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> new Session(row.getObject("id", UUID.class),
                        row.getObject("course_id", UUID.class))).optional().orElseThrow(RecordingMappingService::notFound);
        Recording recording = jdbc.sql("""
                        SELECT id,duration_seconds,version,checksum,status,session_id FROM audio_recordings
                        WHERE owner_id=:owner AND id=:recording FOR UPDATE
                        """).param("owner", ownerId).param("recording", recordingId)
                .query((row, ignored) -> new Recording(row.getObject("id", UUID.class),
                        (Long) row.getObject("duration_seconds"), row.getInt("version"), row.getString("checksum"),
                        row.getString("status"), row.getObject("session_id", UUID.class)))
                .optional().orElseThrow(RecordingMappingService::notFound);
        if (!recording.status().equals("uploaded") || recording.durationSeconds() == null
                || recording.sessionId() != null) throw conflict();
        long mappedDuration = jdbc.sql("""
                        SELECT COALESCE(sum(duration_seconds),0) FROM audio_recordings
                        WHERE owner_id=:owner AND session_id=:session
                          AND status NOT IN ('failed','cancelled','outdated')
                        """).param("owner", ownerId).param("session", sessionId).query(Long.class).single();
        if (mappedDuration + recording.durationSeconds() > properties.uploads().maxAudioDurationSeconds()) {
            throw limit();
        }
        jdbc.sql("""
                UPDATE audio_recordings SET course_id=:course,session_id=:session,status='queued',updated_at=:now
                WHERE owner_id=:owner AND id=:recording AND session_id IS NULL AND status='uploaded'
                """).param("course", session.courseId()).param("session", session.id())
                .param("now", Timestamp.from(clock.instant())).param("owner", ownerId)
                .param("recording", recording.id()).update();
        JobQueue.AiJob job = jobs.enqueue(new JobQueue.EnqueueRequest("stt", ownerId, session.courseId(), session.id(),
                null, null, null, recording.id(), null, recording.version(), recording.checksum(),
                "google-chirp", properties.speech().model(), "none"));
        return new JobQueue.JobAccepted(job.id(), job.status());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found.");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "RECORDING_MAPPING_CONFLICT",
                "Recording cannot be mapped in its current state.");
    }

    private static ApiException limit() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UPLOAD_LIMIT_EXCEEDED",
                "Upload limit exceeded.", Map.of("field", "durationSeconds"));
    }

    private record Session(UUID id, UUID courseId) {}
    private record Recording(UUID id, Long durationSeconds, int version, String checksum,
                             String status, UUID sessionId) {}
}
