package com.mulgil.generation;

import com.mulgil.storage.CloudStoragePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
final class SelectedTopicPayloadStore {
    private final JdbcClient jdbc;
    private final ObjectProvider<CloudStoragePort> storage;
    private final Clock clock;
    private final TransactionTemplate cleanupTransaction;

    SelectedTopicPayloadStore(JdbcClient jdbc, ObjectProvider<CloudStoragePort> storage, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.clock = clock;
        this.cleanupTransaction = new TransactionTemplate(transactionManager);
        this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    String put(UUID ownerId, UUID jobId, String payload, String hash, Instant expiresAt) {
        String key = "temporary/target-generations/" + ownerId + "/" + jobId + ".json";
        Instant now = clock.instant();
        cleanupTransaction.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO resource_object_deletions
                    (object_key,not_before,attempt_count,status,last_error,created_at,updated_at)
                VALUES (:key,:expiry,0,'pending',NULL,:now,:now)
                ON CONFLICT (object_key) DO NOTHING
                """).param("key", key).param("expiry", Timestamp.from(expiresAt))
                .param("now", Timestamp.from(now)).update());
        CloudStoragePort objects = storage.getIfAvailable();
        if (objects == null) throw new IllegalStateException("Private payload storage is unavailable.");
        objects.putPrivate(key, payload.getBytes(StandardCharsets.UTF_8), "application/json", hash);
        return key;
    }

    String read(String key, String expectedHash) {
        CloudStoragePort objects = storage.getIfAvailable();
        if (objects == null) throw new IllegalStateException("Private payload storage is unavailable.");
        byte[] bytes = objects.read(key);
        if (bytes == null) throw new IllegalStateException("Private payload is unavailable.");
        String payload = new String(bytes, StandardCharsets.UTF_8);
        if (!ContentHash.sha256(payload).equals(expectedHash)) {
            throw new IllegalStateException("Private payload hash does not match.");
        }
        return payload;
    }

    void release(String key) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE resource_object_deletions SET not_before=LEAST(not_before,:now),updated_at=:now
                WHERE object_key=:key AND status='pending'
                """).param("now", Timestamp.from(now)).param("key", key).update();
    }

    private static final class ContentHash {
        private static String sha256(String value) {
            return com.mulgil.indexing.ContentIndexingService.sha256(value);
        }
    }
}

@Component
final class SelectedTopicPayloadCleanupScheduler {
    private final JdbcClient jdbc;
    private final Clock clock;

    SelectedTopicPayloadCleanupScheduler(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${SELECTED_TOPIC_PAYLOAD_CLEANUP_POLL_INTERVAL_MILLIS:60000}")
    void releaseTerminal() {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE resource_object_deletions deletion
                SET not_before=LEAST(deletion.not_before,:now),updated_at=:now
                FROM selected_topic_generations target
                LEFT JOIN ai_jobs job ON job.id=target.job_id AND job.owner_id=target.owner_id
                WHERE deletion.object_key=target.payload_object_key AND deletion.status='pending' AND (
                    job.id IS NULL OR job.status IN ('succeeded','cancelled','outdated') OR
                    (job.status='failed' AND (job.error_code NOT IN
                        ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE',
                         'LEASE_EXPIRED','DATABASE_DEADLOCK') OR job.attempt_count>=job.max_attempts))
                )
                """).param("now", Timestamp.from(now)).update();
    }
}
