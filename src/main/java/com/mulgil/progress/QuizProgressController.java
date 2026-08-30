package com.mulgil.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulgil.common.error.ApiError;
import com.mulgil.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class QuizProgressController {
    private final QuizProgressService service;

    QuizProgressController(QuizProgressService service) {
        this.service = service;
    }

    @GetMapping("/sessions/{sessionId}/quiz")
    List<QuizProgressService.QuizQuestion> quiz(@PathVariable UUID sessionId) {
        return service.quiz(CurrentUser.id(), sessionId);
    }

    @PostMapping("/quiz/questions/{questionId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit a practice or predicted-quiz answer")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Grading result and scoped aggregate progress",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(
                            implementation = QuizProgressService.AttemptResult.class))),
            @ApiResponse(responseCode = "404", description = "QUIZ_NOT_FOUND: foreign, missing, or not-ready question",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED or QUIZ_INVALID",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    })
    QuizProgressService.AttemptResult attempt(@PathVariable UUID questionId,
                                              @Valid @RequestBody AttemptRequest request) {
        return service.attempt(CurrentUser.id(), questionId, request.answer());
    }

    @Schema(name = "QuizAttemptRequest")
    record AttemptRequest(@NotNull JsonNode answer) {}
}
