package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
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

    public record UsageHandle(UUID id, UUID jobId, String operation, String provider, String model,
                              Long unitCount) {}
}
