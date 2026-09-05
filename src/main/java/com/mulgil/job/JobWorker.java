package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.sql.SQLException;
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
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final JobQueue queue;
    private final Map<String, JobHandler> handlers;
    private final long heartbeatPeriodMillis;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final String workerId = UUID.randomUUID().toString();

    JobWorker(JobQueue queue, List<JobHandler> handlers, MulgilProperties properties) {
        this.queue = queue;
        this.handlers = handlers.stream().filter(handler -> !handler.jobType().equals("chunk_embed"))
                .collect(Collectors.toUnmodifiableMap(JobHandler::jobType, Function.identity()));
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
            queue.run(job, handler);
        } catch (JobHandler.JobExecutionException exception) {
            queue.fail(job, exception.code(), exception.getMessage(), exception.retryable());
        } catch (RuntimeException exception) {
            log.error("Job handler failed. jobId={} type={}", job.id(), job.type(), exception);
            if (isDatabaseDeadlock(exception)) {
                queue.fail(job, "DATABASE_DEADLOCK", "Job handler failed.", true);
            } else {
                queue.fail(job, "JOB_HANDLER_FAILED", "Job handler failed.", false);
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private static boolean isDatabaseDeadlock(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException
                    || cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    @PreDestroy
    void close() {
        heartbeats.close();
    }
}
