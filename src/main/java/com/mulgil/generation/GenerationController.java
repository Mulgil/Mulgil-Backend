package com.mulgil.generation;

import com.mulgil.common.security.CurrentUser;
import com.mulgil.job.JobQueue;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class GenerationController {
    private final GenerationService service;

    GenerationController(GenerationService service) {
        this.service = service;
    }

    @GetMapping("/sessions/{sessionId}/summaries")
    GenerationService.SessionGeneration summary(@PathVariable UUID sessionId, @RequestParam String type) {
        return service.summary(CurrentUser.id(), sessionId, type);
    }

    @PostMapping("/exams/{examId}/summary/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobQueue.JobAccepted generateExamSummary(@PathVariable UUID examId) {
        return service.generateExam(CurrentUser.id(), examId, false);
    }

    @PostMapping("/exams/{examId}/predicted-quiz/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobQueue.JobAccepted generatePredictedQuiz(@PathVariable UUID examId) {
        return service.generateExam(CurrentUser.id(), examId, true);
    }
}
