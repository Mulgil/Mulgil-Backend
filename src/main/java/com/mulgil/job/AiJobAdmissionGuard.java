package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public final class AiJobAdmissionGuard {
    private static final Logger log = LoggerFactory.getLogger(AiJobAdmissionGuard.class);
    private static final Set<String> BILLABLE_TYPES = Set.of(
            "pdf_ocr", "handwriting_ocr", "stt", "chunk_embed",
            "preview_generate", "review_generate", "exam_summary_generate", "exam_quiz_generate");

    private final JdbcClient jdbc;
    private final MulgilProperties properties;

    public AiJobAdmissionGuard(JdbcClient jdbc, MulgilProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public String generationKey(String sourceHash, String provider, String modelId,
                                String promptVersion, String scope) {
        String cacheKey = ContentIndexingService.sha256(String.join("\u001f",
                sourceHash, provider, modelId, promptVersion));
        return ContentIndexingService.sha256(cacheKey + "\u001f" + scope);
    }

    public void admit(UUID ownerId, String jobType, String idempotencyKey) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:owner,0))")
                .param("owner", ownerId.toString()).query(Integer.class).single();
        boolean existing = jdbc.sql("SELECT EXISTS(SELECT 1 FROM ai_jobs WHERE idempotency_key=:key)")
                .param("key", idempotencyKey).query(Boolean.class).single();
        if (existing || !BILLABLE_TYPES.contains(jobType)) return;
        int jobsToday = jdbc.sql("""
                        SELECT count(*) FROM ai_jobs
                        WHERE owner_id=:owner
                          AND created_at >= date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
                          AND job_type IN (:types)
                        """).param("owner", ownerId).param("types", BILLABLE_TYPES)
                .query(Integer.class).single();
        if (jobsToday < properties.demo().maxAiJobsPerDay()) return;
        log.atWarn().addKeyValue("event", "ai.job.quota.rejected")
                .addKeyValue("ownerId", ownerId).addKeyValue("jobType", jobType)
                .addKeyValue("jobsToday", jobsToday).log("AI job quota rejected");
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_DAILY_LIMIT_REACHED",
                "Daily AI job limit reached.");
    }
}
