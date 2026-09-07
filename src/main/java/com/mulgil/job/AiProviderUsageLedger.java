package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.generation.GenerationModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;

@Service
public final class AiProviderUsageLedger {
    private static final Logger log = LoggerFactory.getLogger(AiProviderUsageLedger.class);

    private final JdbcClient jdbc;
    private final MulgilProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AiProviderUsageLedger(JdbcClient jdbc, MulgilProperties properties, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T observe(JobQueue.ClaimedJob job, String operation, String provider, String model,
                         String unitType, Long unitCount, Supplier<T> providerCall) {
        return observe(job.id(), job.ownerId(), operation, provider, model, unitType, unitCount,
                ignored -> unitCount, ignored -> "PROVIDER_FAILED", providerCall);
    }

    public <T> T observe(JobQueue.ClaimedJob job, String operation, String provider, String model,
                         String unitType, Long unitCount, Function<T, Long> completedUnits,
                         Function<RuntimeException, String> failureCode, Supplier<T> providerCall) {
        return observe(job.id(), job.ownerId(), operation, provider, model, unitType, unitCount,
                completedUnits, failureCode, providerCall);
    }

    public <T> T observe(UUID ownerId, String operation, String provider, String model,
                         String unitType, Long unitCount, Supplier<T> providerCall) {
        return observe(null, ownerId, operation, provider, model, unitType, unitCount,
                ignored -> unitCount, ignored -> "PROVIDER_FAILED", providerCall);
    }

    public GenerationModelPort.GenerationResult observeGeneration(
            JobQueue.ClaimedJob job, String model, long promptUnitCount,
            Supplier<GenerationModelPort.GenerationResult> providerCall) {
        return observeGeneration(job.id(), job.ownerId(), model, promptUnitCount, providerCall);
    }

    public GenerationModelPort.GenerationResult observeGeneration(
            UUID ownerId, String model, long promptUnitCount,
            Supplier<GenerationModelPort.GenerationResult> providerCall) {
        return observeGeneration(null, ownerId, model, promptUnitCount, providerCall);
    }

    private GenerationModelPort.GenerationResult observeGeneration(
            UUID jobId, UUID ownerId, String model, long promptUnitCount,
            Supplier<GenerationModelPort.GenerationResult> providerCall) {
        UsageHandle usage = begin(jobId, ownerId, "vertex.generate", "vertex", model,
                "unicode_code_point", promptUnitCount);
        try {
            GenerationModelPort.GenerationResult result = providerCall.get();
            finishGeneration(usage, "succeeded", null, result, promptUnitCount);
            return result;
        } catch (GenerationModelPort.GenerationModelException exception) {
            finishGeneration(usage, "failed", exception.code(), exception.result(), promptUnitCount);
            throw exception;
        } catch (RuntimeException exception) {
            fail(usage, "PROVIDER_FAILED");
            throw exception;
        }
    }

    public GenerationModelPort.TokenCount observeGenerationTokenCount(
            JobQueue.ClaimedJob job, String model,
            Supplier<GenerationModelPort.TokenCount> providerCall) {
        return observe(job, "vertex.count_tokens", "vertex", model, "token", null,
                GenerationModelPort.TokenCount::inputTokens,
                exception -> exception instanceof GenerationModelPort.GenerationModelException failure
                        ? failure.code() : "PROVIDER_FAILED",
                providerCall);
    }

    private <T> T observe(UUID jobId, UUID ownerId, String operation, String provider, String model,
                          String unitType, Long unitCount, Function<T, Long> completedUnits,
                          Function<RuntimeException, String> failureCode, Supplier<T> providerCall) {
        UsageHandle usage = begin(jobId, ownerId, operation, provider, model, unitType, unitCount);
        try {
            T result = providerCall.get();
            succeed(usage, completedUnits.apply(result));
            return result;
        } catch (RuntimeException exception) {
            fail(usage, failureCode.apply(exception));
            throw exception;
        }
    }

    public UsageHandle begin(UUID jobId, UUID ownerId, String operation, String provider, String model,
                             String unitType, Long unitCount) {
        if (unitCount != null && unitCount < 0) {
            throw new IllegalArgumentException("Provider unit count must not be negative.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Long cost = estimatedCost(operation, unitCount);
        transactions.executeWithoutResult(status -> jdbc.sql("""
                INSERT INTO ai_provider_usage
                    (id,job_id,owner_id,operation,provider,model_id,status,unit_type,unit_count,
                     estimated_cost_microusd,started_at)
                VALUES (:id,:job,:owner,:operation,:provider,:model,'started',:unitType,:units,:cost,:now)
                """).param("id", id).param("job", jobId).param("owner", ownerId)
                .param("operation", operation).param("provider", provider).param("model", model)
                .param("unitType", unitType).param("units", unitCount).param("cost", cost)
                .param("now", Timestamp.from(now)).update());
        log(jobId, operation, provider, model, "started", null, unitCount, cost);
        return new UsageHandle(id, jobId, operation, provider, model, unitCount);
    }

    public void succeed(UsageHandle usage) {
        succeed(usage, usage.unitCount());
    }

    public void succeed(UsageHandle usage, Long unitCount) {
        finish(usage.id(), usage.jobId(), usage.operation(), usage.provider(), usage.model(),
                "succeeded", null, unitCount);
    }

    public void fail(UsageHandle usage, String errorCode) {
        finish(usage.id(), usage.jobId(), usage.operation(), usage.provider(), usage.model(),
                "failed", safeErrorCode(errorCode), usage.unitCount());
    }

    private void finish(UUID id, UUID jobId, String operation, String provider, String model,
                        String status, String errorCode, Long unitCount) {
        Instant now = clock.instant();
        Long cost = estimatedCost(operation, unitCount);
        Long latency = transactions.execute(tx -> jdbc.sql("""
                UPDATE ai_provider_usage SET status=:status,error_code=:error,
                    unit_count=:units,estimated_cost_microusd=:cost,
                    latency_ms=GREATEST(0,CAST(EXTRACT(EPOCH FROM (:now-started_at))*1000 AS bigint)),
                    finished_at=:now
                WHERE id=:id AND status='started'
                RETURNING latency_ms
                """).param("status", status).param("error", errorCode).param("units", unitCount)
                .param("cost", cost).param("now", Timestamp.from(now))
                .param("id", id).query(Long.class).optional().orElse(null));
        log(jobId, operation, provider, model, status, latency, unitCount, cost);
    }

    private void finishGeneration(UsageHandle handle, String status, String errorCode,
                                  GenerationModelPort.GenerationResult result, long promptUnitCount) {
        Long unitCount = result == null ? promptUnitCount
                : promptUnitCount + (long) result.rawJson().codePointCount(0, result.rawJson().length());
        GenerationModelPort.GenerationUsage metadata = result == null ? null : result.usage();
        Instant now = clock.instant();
        Long cost = estimatedCost(handle.operation(), unitCount);
        Long latency = transactions.execute(tx -> jdbc.sql("""
                UPDATE ai_provider_usage SET status=:status,error_code=:error,
                    unit_count=:units,estimated_cost_microusd=:cost,
                    prompt_token_count=:promptTokens,candidate_token_count=:candidateTokens,
                    total_token_count=:totalTokens,cached_content_token_count=:cachedTokens,
                    thoughts_token_count=:thoughtsTokens,finish_reason=:finishReason,
                    context_cache_status=:cacheStatus,context_cache_token_count=:cacheTokenCount,
                    first_response_latency_ms=:firstResponse,
                    latency_ms=GREATEST(0,CAST(EXTRACT(EPOCH FROM (:now-started_at))*1000 AS bigint)),
                    finished_at=:now
                WHERE id=:id AND status='started'
                RETURNING latency_ms
                """).param("status", status).param("error", safeErrorCodeOrNull(errorCode))
                .param("units", unitCount).param("cost", cost)
                .param("promptTokens", metadata == null ? null : metadata.promptTokenCount())
                .param("candidateTokens", metadata == null ? null : metadata.candidateTokenCount())
                .param("totalTokens", metadata == null ? null : metadata.totalTokenCount())
                .param("cachedTokens", metadata == null ? null : metadata.cachedContentTokenCount())
                .param("thoughtsTokens", metadata == null ? null : metadata.thoughtsTokenCount())
                .param("finishReason", result == null ? null : result.finishReason())
                .param("cacheStatus", result == null ? null : result.contextCacheStatus())
                .param("cacheTokenCount", result == null ? null : result.contextCacheTokenCount())
                .param("firstResponse", result == null ? null : result.firstResponseLatencyMs())
                .param("now", Timestamp.from(now)).param("id", handle.id())
                .query(Long.class).optional().orElse(null));
        log(handle.jobId(), handle.operation(), handle.provider(), handle.model(), status, latency, unitCount, cost);
    }

    private Long estimatedCost(String operation, Long unitCount) {
        long rate = switch (operation) {
            case "vision.ocr" -> properties.aiRates().visionImageMicrousd();
            case "speech.recognize" -> properties.aiRates().speechSecondMicrousd();
            case "vertex.embed" -> properties.aiRates().embeddingCharacterMicrousd();
            case "vertex.generate" -> properties.aiRates().generationCharacterMicrousd();
            default -> 0;
        };
        if (rate == 0 || unitCount == null) return null;
        try {
            return Math.multiplyExact(rate, unitCount);
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static void log(UUID jobId, String operation, String provider, String model, String status,
                            Long latency, Long unitCount, Long cost) {
        log.atInfo().addKeyValue("event", "ai.provider.usage")
                .addKeyValue("jobId", jobId).addKeyValue("operation", operation)
                .addKeyValue("provider", provider).addKeyValue("model", model)
                .addKeyValue("status", status).addKeyValue("latencyMs", latency)
                .addKeyValue("unitCount", unitCount).addKeyValue("estimatedCostMicrousd", cost)
                .log("AI provider usage");
    }

    private static String safeErrorCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,100}")) return "PROVIDER_FAILED";
        return value;
    }

    private static String safeErrorCodeOrNull(String value) {
        return value == null ? null : safeErrorCode(value);
    }

    public record UsageHandle(UUID id, UUID jobId, String operation, String provider, String model,
                              Long unitCount) {}
}
