package com.mulgil.recording;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Component
final class SpeechInputCleanupScheduler {
    private static final Duration PROVIDER_COMPLETION_BOUND = Duration.ofHours(24);
    private static final Duration DELETE_SAFETY_BUFFER = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final ObjectProvider<SpeechTemporaryObjectPort> temporaryObjects;
    private final Clock clock;

    SpeechInputCleanupScheduler(JdbcClient jdbc, ObjectProvider<SpeechTemporaryObjectPort> temporaryObjects,
                                Clock clock) {
        this.jdbc = jdbc;
        this.temporaryObjects = temporaryObjects;
        this.clock = clock;
    }

    void schedule(UUID jobId, UUID ownerId, List<URI> objectUris) {
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        INSERT INTO speech_input_cleanups
                            (job_id,owner_id,object_uris,not_before,created_at)
                        VALUES (:job,:owner,CAST(:uris AS text[]),:notBefore,:now)
                        ON CONFLICT (job_id) DO UPDATE SET
                            object_uris=EXCLUDED.object_uris,
                            not_before=GREATEST(speech_input_cleanups.not_before,EXCLUDED.not_before)
                        WHERE speech_input_cleanups.owner_id=EXCLUDED.owner_id
                        """).param("job", jobId).param("owner", ownerId)
                .param("uris", objectUris.stream().map(URI::toString).toArray(String[]::new))
                .param("notBefore", Timestamp.from(now.plus(PROVIDER_COMPLETION_BOUND).plus(DELETE_SAFETY_BUFFER)))
                .param("now", Timestamp.from(now)).update();
        if (updated != 1) throw new IllegalStateException("Could not schedule speech input cleanup.");
    }

    void completed(UUID jobId, UUID ownerId) {
        jdbc.sql("DELETE FROM speech_input_cleanups WHERE job_id=:job AND owner_id=:owner")
                .param("job", jobId).param("owner", ownerId).update();
    }

    @Scheduled(fixedDelayString = "${SPEECH_INPUT_CLEANUP_POLL_INTERVAL_MILLIS:60000}")
    void cleanupDue() {
        SpeechTemporaryObjectPort objects = temporaryObjects.getIfAvailable();
        if (objects == null) return;
        Instant now = clock.instant();
        List<Cleanup> cleanups = jdbc.sql("""
                        SELECT cleanup.job_id,cleanup.object_uris,cleanup.not_before
                        FROM speech_input_cleanups cleanup
                        LEFT JOIN ai_jobs job ON job.id=cleanup.job_id AND job.owner_id=cleanup.owner_id
                        WHERE cleanup.not_before<=:now AND (
                            job.id IS NULL OR job.status IN ('cancelled','outdated','succeeded') OR
                            (job.status='failed' AND (job.error_code NOT IN
                                ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE','LEASE_EXPIRED')
                                OR job.attempt_count>=job.max_attempts)))
                        ORDER BY cleanup.not_before,cleanup.job_id
                        """).param("now", Timestamp.from(now))
                .query((row, ignored) -> new Cleanup(row.getObject("job_id", UUID.class),
                        uris(row.getArray("object_uris")), row.getTimestamp("not_before").toInstant())).list();
        for (Cleanup cleanup : cleanups) {
            cleanup.objectUris().forEach(objects::delete);
            jdbc.sql("DELETE FROM speech_input_cleanups WHERE job_id=:job AND not_before=:notBefore")
                    .param("job", cleanup.jobId()).param("notBefore", Timestamp.from(cleanup.notBefore())).update();
        }
    }

    private static List<URI> uris(Array values) throws SQLException {
        return Arrays.stream((String[]) values.getArray()).map(URI::create).toList();
    }

    private record Cleanup(UUID jobId, List<URI> objectUris, Instant notBefore) {}
}
