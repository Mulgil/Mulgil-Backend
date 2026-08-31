package com.mulgil.embedding;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;

public final class EmbeddingProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    EmbeddingProviderException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public static EmbeddingProviderException from(ApiException exception) {
        StatusCode.Code status = exception.getStatusCode().getCode();
        if (status == StatusCode.Code.DEADLINE_EXCEEDED) {
            return new EmbeddingProviderException("PROVIDER_TIMEOUT", "Vertex embedding request timed out.", true);
        }
        if (status == StatusCode.Code.RESOURCE_EXHAUSTED) {
            return new EmbeddingProviderException("PROVIDER_RATE_LIMIT",
                    "Vertex embedding rate limit exceeded.", true);
        }
        if (status == StatusCode.Code.UNAVAILABLE || status == StatusCode.Code.INTERNAL
                || status == StatusCode.Code.ABORTED || exception.isRetryable()) {
            return new EmbeddingProviderException("PROVIDER_UNAVAILABLE",
                    "Vertex embedding provider unavailable.", true);
        }
        return new EmbeddingProviderException("PROVIDER_FAILED", "Vertex embedding request failed.", false);
    }
}
