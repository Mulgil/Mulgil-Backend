package com.mulgil.generation;

import java.util.Objects;
import java.util.UUID;

public interface GenerationModelPort {
    GenerationResult generate(GenerationRequest request);
    TokenCount countTokens(GenerationRequest request);

    default GenerationResult benchmark(GenerationRequest request, String modelId) {
        throw new UnsupportedOperationException("Model benchmarking is not supported by this adapter.");
    }

    enum Artifact {
        SUMMARY("summary"), MINDMAP("mindmap"), QUIZ("quiz");

        private final String metricValue;

        Artifact(String metricValue) {
            this.metricValue = metricValue;
        }

        String metricValue() {
            return metricValue;
        }
    }

    record GenerationRequest(GenerationInputCompiler.CompiledInput input, String responseSchema,
                             Artifact artifact, Runnable onFirstResponse, UUID ownerId, String snapshotHash) {
        public GenerationRequest(GenerationInputCompiler.CompiledInput input, String responseSchema,
                                 Artifact artifact, Runnable onFirstResponse) {
            this(input, responseSchema, artifact, onFirstResponse, null, null);
        }

        public GenerationRequest {
            Objects.requireNonNull(input);
            Objects.requireNonNull(responseSchema);
            Objects.requireNonNull(artifact);
            Objects.requireNonNull(onFirstResponse);
            if ((ownerId == null) != (snapshotHash == null)) {
                throw new IllegalArgumentException("Cache identity must be complete.");
            }
        }
    }

    record TokenCount(long inputTokens, long contextTokenLimit) {
        public TokenCount {
            if (inputTokens < 0 || contextTokenLimit < 1) {
                throw new IllegalArgumentException("Token counts must be non-negative and the limit positive.");
            }
        }
    }

    record GenerationUsage(Long promptTokenCount, Long candidateTokenCount, Long totalTokenCount,
                           Long cachedContentTokenCount) {
        public GenerationUsage {
            requireNonNegative(promptTokenCount);
            requireNonNegative(candidateTokenCount);
            requireNonNegative(totalTokenCount);
            requireNonNegative(cachedContentTokenCount);
        }

        private static void requireNonNegative(Long value) {
            if (value != null && value < 0) throw new IllegalArgumentException("Token count must not be negative.");
        }
    }

    record GenerationResult(String rawJson, GenerationUsage usage, Long firstResponseLatencyMs,
                            String finishReason, String contextCacheStatus, Long contextCacheTokenCount) {
        public GenerationResult(String rawJson, GenerationUsage usage, Long firstResponseLatencyMs,
                                String finishReason) {
            this(rawJson, usage, firstResponseLatencyMs, finishReason, null, null);
        }

        public GenerationResult {
            Objects.requireNonNull(rawJson);
            Objects.requireNonNull(finishReason);
            if (firstResponseLatencyMs != null && firstResponseLatencyMs < 0) {
                throw new IllegalArgumentException("First response latency must not be negative.");
            }
            if (contextCacheTokenCount != null && contextCacheTokenCount < 0) {
                throw new IllegalArgumentException("Cache token count must not be negative.");
            }
        }

        GenerationResult withCache(String status, Long tokenCount) {
            return new GenerationResult(rawJson, usage, firstResponseLatencyMs, finishReason, status, tokenCount);
        }
    }

    final class GenerationModelException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final GenerationResult result;

        public GenerationModelException(String code, boolean retryable, GenerationResult result) {
            super("Generation provider failed.");
            this.code = code;
            this.retryable = retryable;
            this.result = result;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public GenerationResult result() { return result; }
    }
}
