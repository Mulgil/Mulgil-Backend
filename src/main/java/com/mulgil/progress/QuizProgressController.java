package com.mulgil.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulgil.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
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
    QuizProgressService.AttemptResult attempt(@PathVariable UUID questionId,
                                              @Valid @RequestBody AttemptRequest request) {
        return service.attempt(CurrentUser.id(), questionId, request.answer());
    }

    record AttemptRequest(@NotNull JsonNode answer) {}
}
