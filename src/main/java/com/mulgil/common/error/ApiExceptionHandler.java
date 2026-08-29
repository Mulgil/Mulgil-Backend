package com.mulgil.common.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> apiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        String field = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "request"
                : exception.getBindingResult().getFieldErrors().getFirst().getField();
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed.", Map.of("field", field)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedBody() {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed.", Map.of("field", "request")));
    }
}
