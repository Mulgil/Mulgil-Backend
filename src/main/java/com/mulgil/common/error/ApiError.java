package com.mulgil.common.error;

import java.util.Map;

public record ApiError(String code, String message, Map<String, Object> details) {
    public ApiError {
        details = Map.copyOf(details);
    }
}
