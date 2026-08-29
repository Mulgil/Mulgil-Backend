package com.mulgil.job;

import com.mulgil.common.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class JobController {
    private final JobQueue queue;

    JobController(JobQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/jobs/{jobId}")
    JobView job(@PathVariable UUID jobId) {
        return view(queue.get(CurrentUser.id(), jobId));
    }

    @GetMapping("/sessions/{sessionId}/jobs")
    List<JobView> jobs(@PathVariable UUID sessionId) {
        return queue.list(CurrentUser.id(), sessionId).stream().map(JobController::view).toList();
    }

    @PostMapping("/jobs/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JobView retry(@PathVariable UUID jobId) {
        return view(queue.retry(CurrentUser.id(), jobId));
    }

    private static JobView view(JobQueue.AiJob job) {
        return new JobView(job.id(), job.type(), job.status(), job.inputVersion(), job.attemptCount(),
                job.maxAttempts(), job.errorCode(), job.createdAt(), job.finishedAt());
    }

    record JobView(UUID id, String type, String status, int inputVersion, int attemptCount,
                   int maxAttempts, String errorCode, Instant createdAt, Instant finishedAt) {}
}
