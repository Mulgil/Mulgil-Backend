package com.mulgil.generation;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class DemoGenerationGuard {
    private static final Logger log = LoggerFactory.getLogger(DemoGenerationGuard.class);
    private final JdbcClient jdbc;
    private final MulgilProperties properties;

    DemoGenerationGuard(JdbcClient jdbc, MulgilProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    String idempotencyKey(UUID ownerId, String sourceHash, String provider, String modelId,
                          String promptVersion, String scope) {
        String cacheKey = ContentIndexingService.sha256(String.join("\u001f",
                sourceHash, provider, modelId, promptVersion));
        String key = ContentIndexingService.sha256(cacheKey + "\u001f" + scope);
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:owner,0))")
                .param("owner", ownerId.toString()).query(Integer.class).single();
        boolean cached = properties.demo().cacheEnabled()
                && jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_jobs WHERE idempotency_key=:key)")
                .param("key", key).query(Boolean.class).single();
        if (cached) {
            log.atInfo().addKeyValue("event", "generation.cache.hit")
                    .addKeyValue("ownerId", ownerId).addKeyValue("sourceHash", sourceHash).log("generation cache hit");
            return key;
        }
        int jobsToday = jdbc.sql("""
                        SELECT count(*) FROM ai_jobs
                        WHERE owner_id=:owner AND created_at >= date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                          AND job_type IN ('preview_generate','review_generate',
                                           'exam_summary_generate','exam_quiz_generate')
                        """).param("owner", ownerId).query(Integer.class).single();
        if (jobsToday >= properties.demo().maxAiJobsPerDay()) {
            log.atWarn().addKeyValue("event", "generation.quota.rejected")
                    .addKeyValue("ownerId", ownerId).addKeyValue("jobsToday", jobsToday).log("generation quota rejected");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_DAILY_LIMIT_REACHED",
                    "Daily AI job limit reached.");
        }
        return properties.demo().cacheEnabled() ? key
                : ContentIndexingService.sha256(key + "\u001f" + UUID.randomUUID());
    }
}
