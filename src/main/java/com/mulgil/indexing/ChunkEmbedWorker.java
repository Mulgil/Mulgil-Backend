package com.mulgil.indexing;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!test")
public final class ChunkEmbedWorker {
    private static final Logger log = LoggerFactory.getLogger(ChunkEmbedWorker.class);
    private static final int VERTEX_BATCH_LIMIT = 5;

    private final JobQueue queue;
    private final ChunkEmbedJobHandler handler;
    private final int claimSize;
    private final long shutdownWaitSeconds;
    private final ThreadPoolExecutor batches;
    private final ScheduledExecutorService heartbeats;
    private final Semaphore slots;
    private final java.util.Set<JobQueue.ClaimedJob> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String workerId = UUID.randomUUID().toString();

    ChunkEmbedWorker(JobQueue queue, ChunkEmbedJobHandler handler, MulgilProperties properties) {
        this.queue = queue;
        this.handler = handler;
        this.claimSize = Math.min(VERTEX_BATCH_LIMIT, properties.vertex().embeddingBatchSize());
        int concurrency = properties.jobs().chunkEmbedConcurrency();
        this.shutdownWaitSeconds = Math.min(properties.jobs().providerTimeoutSeconds(), 29) + 1;
        this.slots = new Semaphore(concurrency);
        this.batches = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), Thread.ofPlatform().name("chunk-embed-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.heartbeats = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("chunk-embed-heartbeat").factory());
        long heartbeatMillis = Math.max(1, Math.min(TimeUnit.SECONDS.toMillis(20),
                TimeUnit.SECONDS.toMillis(properties.jobs().leaseSeconds()) / 3));
        heartbeats.scheduleAtFixedRate(this::heartbeatActive, heartbeatMillis, heartbeatMillis,
                TimeUnit.MILLISECONDS);
    }

    @Scheduled(fixedDelayString = "${JOB_POLL_INTERVAL_MILLIS:1000}")
    synchronized void poll() {
        while (!closed.get() && slots.tryAcquire()) {
            List<JobQueue.ClaimedJob> jobs;
            try {
                jobs = queue.claimChunkEmbeddings(workerId, claimSize);
            } catch (RuntimeException exception) {
                slots.release();
                log.error("Chunk embedding claim failed.");
                return;
            }
            if (jobs.isEmpty()) {
                slots.release();
                return;
            }
            active.addAll(jobs);
            try {
                batches.execute(() -> process(jobs));
            } catch (RejectedExecutionException exception) {
                failClaimed(jobs, "Embedding worker unavailable.");
                active.removeAll(jobs);
                slots.release();
                return;
            }
        }
    }

    private void process(List<JobQueue.ClaimedJob> jobs) {
        try {
            queue.finishChunkEmbeddings(handler.handleBatch(jobs));
        } catch (RuntimeException exception) {
            log.error("Chunk embedding batch failed unexpectedly. jobCount={}", jobs.size());
            failClaimed(jobs, "Embedding batch failed.");
        } finally {
            active.removeAll(jobs);
            slots.release();
        }
    }

    private void heartbeatActive() {
        active.forEach(job -> {
            try {
                queue.heartbeat(job.id(), job.claimedBy());
            } catch (RuntimeException exception) {
                log.warn("Chunk embedding heartbeat failed.");
            }
        });
    }

    private void failClaimed(List<JobQueue.ClaimedJob> jobs, String message) {
        jobs.forEach(job -> queue.fail(job, "PROVIDER_UNAVAILABLE", message, true));
    }

    @PreDestroy
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        batches.shutdown();
        try {
            if (!batches.awaitTermination(shutdownWaitSeconds, TimeUnit.SECONDS)) batches.shutdownNow();
        } catch (InterruptedException exception) {
            batches.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (!active.isEmpty()) failClaimed(List.copyOf(active), "Embedding worker stopped.");
        heartbeats.shutdownNow();
    }
}
