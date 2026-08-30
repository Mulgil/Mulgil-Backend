package com.mulgil.generation;

import com.mulgil.common.security.CurrentUser;
import com.mulgil.common.error.ApiError;
import com.mulgil.job.JobQueue;
import com.mulgil.progress.QuizProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class GenerationController {
    private final GenerationService service;
    private final QuizProgressService progress;

    GenerationController(GenerationService service, QuizProgressService progress) {
        this.service = service;
        this.progress = progress;
    }

    @GetMapping("/sessions/{sessionId}/summaries")
    GenerationService.SessionGeneration summary(@PathVariable UUID sessionId, @RequestParam String type) {
        return service.summary(CurrentUser.id(), sessionId, type);
    }

    @GetMapping("/exams/{examId}/summary")
    @Operation(summary = "Read an exam summary artifact")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generated exam summary",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenerationService.ExamGeneration.class))),
            @ApiResponse(responseCode = "404", description = "EXAM_NOT_FOUND or GENERATION_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "INSUFFICIENT_SOURCE_DATA",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    })
    GenerationService.ExamGeneration examSummary(@PathVariable UUID examId) {
        return service.examSummary(CurrentUser.id(), examId);
    }

    @GetMapping("/exams/{examId}/predicted-quiz")
    @Operation(summary = "Read public predicted-quiz questions for an exam")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Public questions without answers or explanations",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(
                            schema = @Schema(implementation = QuizProgressService.QuizQuestion.class)))),
            @ApiResponse(responseCode = "404", description = "EXAM_NOT_FOUND",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "QUIZ_NOT_READY",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    })
    List<QuizProgressService.QuizQuestion> predictedQuiz(@PathVariable UUID examId) {
        return progress.examQuiz(CurrentUser.id(), examId);
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
