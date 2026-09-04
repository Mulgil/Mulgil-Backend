package com.mulgil.resource;

import com.mulgil.storage.CloudStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
final class ResourceObjectDeletionScheduler {
    private static final Logger log = LoggerFactory.getLogger(ResourceObjectDeletionScheduler.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final ObjectProvider<CloudStoragePort> storage;
    private final Clock clock;

    ResourceObjectDeletionScheduler(JdbcClient jdbc, ObjectProvider<CloudStoragePort> storage, Clock clock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${RESOURCE_OBJECT_DELETION_POLL_INTERVAL_MILLIS:60000}")
    void cleanupDue() {
        CloudStoragePort objects = storage.getIfAvailable();
        if (objects == null) return;
        Instant now = clock.instant();
        List<Deletion> deletions = jdbc.sql("""
                        SELECT object_key,attempt_count FROM resource_object_deletions
                        WHERE status='pending' AND not_before<=:now
                        ORDER BY not_before,object_key
                        """).param("now", Timestamp.from(now))
                .query((row, ignored) -> new Deletion(row.getString("object_key"), row.getInt("attempt_count")))
                .list();
        for (Deletion deletion : deletions) {
            try {
                objects.delete(deletion.objectKey());
                jdbc.sql("""
                                DELETE FROM resource_object_deletions
                                WHERE object_key=:key AND status='pending' AND attempt_count=:attempt
                                """).param("key", deletion.objectKey()).param("attempt", deletion.attemptCount())
                        .update();
            } catch (RuntimeException exception) {
                recordFailure(deletion, now, exception);
            }
        }
    }

    private void recordFailure(Deletion deletion, Instant now, RuntimeException exception) {
        int attempt = deletion.attemptCount() + 1;
        String error = safeError(exception);
        if (attempt >= MAX_ATTEMPTS) {
            int updated = jdbc.sql("""
                            UPDATE resource_object_deletions SET attempt_count=:next,status='failed',
                                last_error=:error,updated_at=:now
                            WHERE object_key=:key AND status='pending' AND attempt_count=:attempt
                            """).param("next", attempt).param("error", error).param("now", Timestamp.from(now))
                    .param("key", deletion.objectKey()).param("attempt", deletion.attemptCount()).update();
            if (updated == 1) {
                log.atError().addKeyValue("event", "resource.object.delete.failed")
                        .addKeyValue("attemptCount", attempt).log("resource object deletion failed");
            }
            return;
        }
        int updated = jdbc.sql("""
                        UPDATE resource_object_deletions SET attempt_count=:next,last_error=:error,
                            not_before=:retry,updated_at=:now
                        WHERE object_key=:key AND status='pending' AND attempt_count=:attempt
                        """).param("next", attempt).param("error", error).param("retry", Timestamp.from(now.plus(RETRY_DELAY)))
                .param("now", Timestamp.from(now)).param("key", deletion.objectKey())
                .param("attempt", deletion.attemptCount()).update();
        if (updated == 1) {
            log.atWarn().addKeyValue("event", "resource.object.delete.retry")
                    .addKeyValue("attemptCount", attempt).log("resource object deletion retry scheduled");
        }
    }

    private static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private record Deletion(String objectKey, int attemptCount) {}
}
