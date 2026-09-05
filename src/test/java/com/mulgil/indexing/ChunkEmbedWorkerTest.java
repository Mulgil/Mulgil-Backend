package com.mulgil.indexing;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkEmbedWorkerTest {
    @Test
    void claimsFiveAtATime_andRunsNoMoreThanConfiguredConcurrency() throws Exception {
        JobQueue queue = mock(JobQueue.class);
        ChunkEmbedJobHandler handler = mock(ChunkEmbedJobHandler.class);
        MulgilProperties properties = properties(4, 20);
        AtomicInteger nextJob = new AtomicInteger();
        AtomicInteger claims = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch firstWaveStarted = new CountDownLatch(4);
        CountDownLatch releaseFirstWave = new CountDownLatch(1);
        CountDownLatch finalized = new CountDownLatch(29);
        when(queue.claimChunkEmbeddings(anyString(), eq(5))).thenAnswer(ignored -> {
            int start = nextJob.getAndAdd(5);
            if (start >= 143) return List.of();
            claims.incrementAndGet();
            return java.util.stream.IntStream.range(start, Math.min(start + 5, 143))
                    .mapToObj(ChunkEmbedWorkerTest::job).toList();
        });
        when(handler.handleBatch(anyList())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            if (firstWaveStarted.getCount() > 0) {
                firstWaveStarted.countDown();
                assertThat(releaseFirstWave.await(2, TimeUnit.SECONDS)).isTrue();
            }
            active.decrementAndGet();
            return ((List<JobQueue.ClaimedJob>) invocation.getArgument(0)).stream()
                    .map(job -> new ChunkEmbedJobHandler.BatchOutcome(job, () -> {}, null)).toList();
        });
        doAnswer(ignored -> {
            finalized.countDown();
            return null;
        }).when(queue).finishChunkEmbeddings(anyList());
        ChunkEmbedWorker worker = new ChunkEmbedWorker(queue, handler, properties);

        try {
            worker.poll();
            assertThat(firstWaveStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximumActive).hasValue(4);
            releaseFirstWave.countDown();
            for (int attempt = 0; attempt < 100 && finalized.getCount() > 0; attempt++) {
                worker.poll();
                finalized.await(20, TimeUnit.MILLISECONDS);
            }

            assertThat(finalized.getCount()).isZero();
            assertThat(claims).hasValue(29);
            assertThat(maximumActive.get()).isLessThanOrEqualTo(4);
        } finally {
            releaseFirstWave.countDown();
            worker.close();
        }
    }

    @Test
    void heartbeatsBlockedBatch_andStopsPollingAfterCleanShutdown() throws Exception {
        JobQueue queue = mock(JobQueue.class);
        ChunkEmbedJobHandler handler = mock(ChunkEmbedJobHandler.class);
        JobQueue.ClaimedJob job = job(0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch heartbeat = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finalized = new CountDownLatch(1);
        when(queue.claimChunkEmbeddings(anyString(), eq(5))).thenReturn(List.of(job));
        when(queue.heartbeat(job.id(), job.claimedBy())).thenAnswer(ignored -> {
            heartbeat.countDown();
            return true;
        });
        when(handler.handleBatch(anyList())).thenAnswer(ignored -> {
            started.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(new ChunkEmbedJobHandler.BatchOutcome(job, () -> {}, null));
        });
        doAnswer(ignored -> {
            finalized.countDown();
            return null;
        }).when(queue).finishChunkEmbeddings(anyList());
        ChunkEmbedWorker worker = new ChunkEmbedWorker(queue, handler, properties(1, 5));

        try {
            worker.poll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(finalized.await(2, TimeUnit.SECONDS)).isTrue();
            worker.close();
            worker.poll();

            verify(queue, atLeastOnce()).heartbeat(job.id(), job.claimedBy());
            verify(queue, times(1)).claimChunkEmbeddings(anyString(), eq(5));
        } finally {
            release.countDown();
            worker.close();
        }
    }

    private static MulgilProperties properties(int concurrency, int configuredBatchSize) {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.jobs()).thenReturn(new MulgilProperties.Jobs(2, 1, 1, concurrency));
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex(
                "generation", "embedding", "us-central1", configuredBatchSize));
        return properties;
    }

    private static JobQueue.ClaimedJob job(int index) {
        UUID id = new UUID(0, index + 1L);
        return new JobQueue.ClaimedJob(id, "chunk_embed", new UUID(1, 1), new UUID(2, 2), new UUID(3, 3),
                null, null, null, null, null, 1, "%064x".formatted(index + 1), 1, 3, "worker");
    }
}
