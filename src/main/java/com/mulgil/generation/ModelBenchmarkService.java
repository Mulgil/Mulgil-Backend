package com.mulgil.generation;

import com.mulgil.common.config.MulgilProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
final class ModelBenchmarkService {
    private static final String CONTRACT = GenerationScheduler.PROMPT_VERSION;
    private final JdbcClient jdbc;
    private final ObjectProvider<GenerationModelPort> models;
    private final MulgilProperties properties;
    private final GenerationOutputValidator validator;
    private final GenerationInputCompiler compiler;
    private final Clock clock;

    ModelBenchmarkService(JdbcClient jdbc, ObjectProvider<GenerationModelPort> models,
                          MulgilProperties properties, GenerationOutputValidator validator,
                          GenerationInputCompiler compiler, Clock clock) {
        this.jdbc = jdbc;
        this.models = models;
        this.properties = properties;
        this.validator = validator;
        this.compiler = compiler;
        this.clock = clock;
    }

    BenchmarkResult run(GenerationSnapshotService.Snapshot snapshot,
                        GenerationModelPort.Artifact artifact, String candidateModel) {
        requireCandidate(candidateModel);
        if (snapshot == null || !snapshot.ready()) throw new IllegalArgumentException("Snapshot must be ready.");
        GenerationModelPort model = models.getIfAvailable();
        if (model == null) throw new IllegalStateException("Generation provider unavailable.");
        GenerationInputCompiler.CompiledInput input = compiler.compile(snapshot);
        var request = new GenerationModelPort.GenerationRequest(
                input, CONTRACT, artifact, () -> {}, snapshot.ownerId(), snapshot.snapshotHash());
        long started = System.nanoTime();
        GenerationModelPort.GenerationResult result = null;
        String failure = null;
        boolean valid = false;
        try {
            result = model.benchmark(request, candidateModel);
            validator.parse(result.rawJson(), input, artifact);
            valid = true;
            return new BenchmarkResult(candidateModel, true);
        } catch (GenerationModelPort.GenerationModelException exception) {
            result = exception.result();
            failure = safeCode(exception.code());
            throw exception;
        } catch (com.mulgil.job.JobHandler.JobExecutionException exception) {
            failure = safeCode(exception.code());
            throw new IllegalArgumentException("Benchmark output is invalid.");
        } catch (RuntimeException exception) {
            failure = "BENCHMARK_FAILED";
            throw exception;
        } finally {
            record(snapshot, artifact, candidateModel, result, valid, failure,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000));
        }
    }

    boolean operationallyApproved(String modelId) {
        return properties.modelBenchmark().enabled()
                && properties.modelBenchmark().candidateModels().contains(modelId) && jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM generation_model_approvals
                    WHERE model_id=:model AND approved_at <= now() AND expires_at > now()
                      AND EXISTS(SELECT 1 FROM generation_model_benchmarks benchmark
                          WHERE benchmark.model_id=:model AND benchmark.valid_output
                            AND benchmark.retention_expires_at > now()))
                """).param("model", modelId).query(Boolean.class).single();
    }

    private void requireCandidate(String candidateModel) {
        if (!properties.modelBenchmark().enabled()) throw new IllegalStateException("Model benchmark is disabled.");
        if (!properties.modelBenchmark().candidateModels().contains(candidateModel)) {
            throw new IllegalArgumentException("Benchmark candidate is not allowlisted.");
        }
    }

    private void record(GenerationSnapshotService.Snapshot snapshot, GenerationModelPort.Artifact artifact,
                        String model, GenerationModelPort.GenerationResult result, boolean valid,
                        String failure, long latency) {
        Instant now = clock.instant();
        GenerationModelPort.GenerationUsage usage = result == null ? null : result.usage();
        jdbc.sql("""
                INSERT INTO generation_model_benchmarks
                    (id,owner_id,model_id,artifact,prompt_version,schema_version,source_hash,
                     prompt_token_count,candidate_token_count,total_token_count,cached_content_token_count,
                     thoughts_token_count,finish_reason,first_response_latency_ms,
                     provider_latency_ms,valid_output,failure_code,
                     retention_expires_at,created_at)
                VALUES (:id,:owner,:model,:artifact,:prompt,:schema,:hash,:promptTokens,:candidateTokens,
                        :totalTokens,:cachedTokens,:thoughtsTokens,:finishReason,:firstResponse,
                        :latency,:valid,:failure,:expires,:now)
                """).param("id", UUID.randomUUID()).param("owner", snapshot.ownerId()).param("model", model)
                .param("artifact", artifact.metricValue()).param("prompt", GenerationScheduler.PROMPT_VERSION)
                .param("schema", CONTRACT).param("hash", snapshot.snapshotHash())
                .param("promptTokens", usage == null ? null : usage.promptTokenCount())
                .param("candidateTokens", usage == null ? null : usage.candidateTokenCount())
                .param("totalTokens", usage == null ? null : usage.totalTokenCount())
                .param("cachedTokens", usage == null ? null : usage.cachedContentTokenCount())
                .param("thoughtsTokens", usage == null ? null : usage.thoughtsTokenCount())
                .param("finishReason", result == null ? null : result.finishReason())
                .param("firstResponse", result == null ? null : result.firstResponseLatencyMs())
                .param("latency", latency).param("valid", valid).param("failure", failure)
                .param("expires", Timestamp.from(now.plusSeconds(
                        properties.modelBenchmark().retentionDays() * 86_400L)))
                .param("now", Timestamp.from(now)).update();
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : "BENCHMARK_FAILED";
    }

    record BenchmarkResult(String modelId, boolean validOutput) {}
}
