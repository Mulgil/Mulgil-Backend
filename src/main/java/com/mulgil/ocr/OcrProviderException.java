package com.mulgil.ocr;

public final class OcrProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public OcrProviderException(String code, String message, boolean retryable) {
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
}
