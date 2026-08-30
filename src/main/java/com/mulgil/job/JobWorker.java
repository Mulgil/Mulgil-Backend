package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
@EnableScheduling
@Profile("!test")
class JobWorker {
    private final JobQueue queue;
    private final Map<String, JobHandler> handlers;
    private final long heartbeatPeriodMillis;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final String workerId = UUID.randomUUID().toString();

    JobWorker(JobQueue queue, List<JobHandler> handlers, MulgilProperties properties) {
        this.queue = queue;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
        this.heartbeatPeriodMillis = Math.max(1, Math.min(TimeUnit.SECONDS.toMillis(20),
                TimeUnit.SECONDS.toMillis(properties.jobs().leaseSeconds()) / 3));
    }

    @Scheduled(fixedDelayString = "${JOB_POLL_INTERVAL_MILLIS:1000}")
    void poll() {
        if (handlers.isEmpty()) return;
        JobQueue.ClaimedJob job = queue.claim(workerId, handlers.keySet());
        if (job == null) return;
        JobHandler handler = handlers.get(job.type());
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> queue.heartbeat(job.id(), workerId), heartbeatPeriodMillis,
                heartbeatPeriodMillis, TimeUnit.MILLISECONDS);
        try {
            queue.complete(job, handler.handle(job));
        } catch (JobHandler.JobExecutionException exception) {
            queue.fail(job, exception.code(), exception.getMessage(), exception.retryable());
        } catch (RuntimeException exception) {
            queue.fail(job, "JOB_HANDLER_FAILED", "Job handler failed.", false);
        } finally {
            heartbeat.cancel(false);
        }
    }

    @PreDestroy
    void close() {
        heartbeats.close();
    }
}
