package com.mulgil.resource;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
final class MaterialUploadCleanupScheduler {
    private final ResourceRepository repository;
    private final Clock clock;

    MaterialUploadCleanupScheduler(ResourceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${MATERIAL_UPLOAD_CLEANUP_POLL_INTERVAL_MILLIS:60000}")
    void cleanupExpired() {
        repository.expireMaterialUploads(clock.instant());
    }
}
