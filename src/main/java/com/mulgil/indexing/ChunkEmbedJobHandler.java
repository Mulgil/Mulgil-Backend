package com.mulgil.indexing;

import com.google.api.gax.rpc.ApiException;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.embedding.EmbeddingProviderException;
import com.pgvector.PGvector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public final class ChunkEmbedJobHandler implements JobHandler {
    private final JdbcClient jdbc;
    private final ObjectProvider<ChunkEmbeddingPort> embeddings;
    private final MulgilProperties properties;
    private final AiProviderUsageLedger usage;

    ChunkEmbedJobHandler(JdbcClient jdbc, ObjectProvider<ChunkEmbeddingPort> embeddings,
                         MulgilProperties properties, AiProviderUsageLedger usage) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.properties = properties;
        this.usage = usage;
    }

    @Override
    public String jobType() {
        return "chunk_embed";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        ChunkEmbeddingPort port = embeddings.getIfAvailable();
        if (port == null) throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Embedding provider unavailable.", true);
        List<PendingChunk> chunks = pendingChunks(job);
        List<ChunkEmbeddingPort.Embedding> embeddings;
        try {
            embeddings = port.embedAll(chunks.stream().map(PendingChunk::text).toList(),
                    new ChunkEmbeddingPort.ProviderCallObserver() {
                        @Override
                        public List<ChunkEmbeddingPort.Embedding> observe(
                                int startIndex, List<String> texts,
                                java.util.function.Supplier<List<ChunkEmbeddingPort.Embedding>> providerCall) {
                            AiProviderUsageLedger.UsageHandle handle = usage.begin(job.id(), job.ownerId(),
                                    "vertex.embed", "vertex", properties.vertex().embeddingModel(),
                                    "unicode_code_point",
                                    texts.stream().mapToLong(text -> text.codePoints().count()).sum());
                            try {
                                List<ChunkEmbeddingPort.Embedding> result = providerCall.get();
                                embeddedChunks(chunks, startIndex, texts.size(), result);
                                usage.succeed(handle);
                                return result;
                            } catch (RuntimeException exception) {
                                usage.fail(handle, failureCode(exception));
                                throw exception;
                            }
                        }

                        @Override
                        public void checkpoint(int startIndex, List<ChunkEmbeddingPort.Embedding> embeddings) {
                            publish(job, embeddedChunks(chunks, startIndex, embeddings.size(), embeddings));
                        }
                    });
        } catch (EmbeddingProviderException exception) {
            throw new JobExecutionException(exception.code(), "Embedding provider failed.", exception.retryable());
        }
        List<EmbeddedChunk> results = embeddedChunks(chunks, 0, chunks.size(), embeddings);
        return () -> publish(job, results);
    }

    public List<BatchOutcome> handleBatch(List<JobQueue.ClaimedJob> jobs) {
        if (jobs.isEmpty()) return List.of();
        ChunkEmbeddingPort port = embeddings.getIfAvailable();
        if (port == null) {
            JobExecutionException failure = new JobExecutionException(
                    "PROVIDER_UNAVAILABLE", "Embedding provider unavailable.", true);
            return jobs.stream().map(job -> BatchOutcome.failed(job, failure)).toList();
        }

        List<BatchChunk> chunks = jobs.stream().sorted(java.util.Comparator.comparing(JobQueue.ClaimedJob::id))
                .flatMap(job -> pendingChunks(job).stream().map(chunk -> new BatchChunk(job, chunk)))
                .toList();
        Map<UUID, List<BatchChunk>> chunksByJob = chunks.stream()
                .collect(Collectors.groupingBy(chunk -> chunk.job().id()));
        Map<UUID, AiProviderUsageLedger.UsageHandle> usages = chunksByJob.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    JobQueue.ClaimedJob job = entry.getValue().getFirst().job();
                    long characters = entry.getValue().stream().map(BatchChunk::chunk)
                            .mapToLong(chunk -> chunk.text().codePoints().count()).sum();
                    return usage.begin(job.id(), job.ownerId(), "vertex.embed", "vertex",
                            properties.vertex().embeddingModel(), "unicode_code_point", characters);
                }));
        boolean[] providerCompleted = new boolean[chunks.size()];
        boolean[] publicationAttempted = new boolean[chunks.size()];
        boolean[] published = new boolean[chunks.size()];
        try {
            List<ChunkEmbeddingPort.Embedding> vectors = port.embedAll(
                    chunks.stream().map(BatchChunk::chunk).map(PendingChunk::text).toList(),
                    new ChunkEmbeddingPort.ProviderCallObserver() {
                        @Override
                        public List<ChunkEmbeddingPort.Embedding> observe(
                                int startIndex, List<String> texts,
                                java.util.function.Supplier<List<ChunkEmbeddingPort.Embedding>> providerCall) {
                            return providerCall.get();
                        }

                        @Override
                        public void checkpoint(int startIndex, List<ChunkEmbeddingPort.Embedding> embeddings) {
                            List<BatchEmbeddedChunk> completed = embeddedBatchChunks(
                                    chunks, startIndex, embeddings.size(), embeddings);
                            for (int offset = 0; offset < completed.size(); offset++) {
                                BatchEmbeddedChunk result = completed.get(offset);
                                int index = startIndex + offset;
                                providerCompleted[index] = true;
                                int updated = publish(result.job(), List.of(result.chunk()));
                                publicationAttempted[index] = true;
                                published[index] = updated == 1;
                            }
                        }
                    });
            List<BatchEmbeddedChunk> completed = embeddedBatchChunks(chunks, 0, chunks.size(), vectors);
            java.util.Arrays.fill(providerCompleted, true);
            for (int index = 0; index < completed.size(); index++) {
                if (publicationAttempted[index]) continue;
                BatchEmbeddedChunk result = completed.get(index);
                int updated = publish(result.job(), List.of(result.chunk()));
                publicationAttempted[index] = true;
                published[index] = updated == 1;
            }
            usages.values().forEach(usage::succeed);
            return jobs.stream().map(job -> stale(chunks, chunksByJob.getOrDefault(job.id(), List.of()),
                            publicationAttempted, published)
                    ? BatchOutcome.failed(job, staleInput())
                    : BatchOutcome.succeeded(job, () -> {})).toList();
        } catch (RuntimeException exception) {
            JobExecutionException failure = jobFailure(exception);
            return jobs.stream().map(job -> {
                List<BatchChunk> jobChunks = chunksByJob.getOrDefault(job.id(), List.of());
                boolean providerComplete = jobChunks.isEmpty()
                        || jobChunks.stream().allMatch(chunk -> providerCompleted[chunks.indexOf(chunk)]);
                boolean complete = jobChunks.isEmpty()
                        || jobChunks.stream().allMatch(chunk -> published[chunks.indexOf(chunk)]);
                AiProviderUsageLedger.UsageHandle handle = usages.get(job.id());
                if (handle != null) {
                    if (providerComplete) usage.succeed(handle); else usage.fail(handle, failureCode(exception));
                }
                if (stale(chunks, jobChunks, publicationAttempted, published)) {
                    return BatchOutcome.failed(job, staleInput());
                }
                return complete ? BatchOutcome.succeeded(job, () -> {}) : BatchOutcome.failed(job, failure);
            }).toList();
        }
    }

    private static boolean stale(List<BatchChunk> chunks, List<BatchChunk> jobChunks,
                                 boolean[] publicationAttempted, boolean[] published) {
        return jobChunks.stream().anyMatch(chunk -> {
            int index = chunks.indexOf(chunk);
            return publicationAttempted[index] && !published[index];
        });
    }

    private static JobExecutionException staleInput() {
        return new JobExecutionException("STALE_INPUT", "Chunk changed before embedding publication.", false);
    }

    private List<PendingChunk> pendingChunks(JobQueue.ClaimedJob job) {
        return jdbc.sql("""
                        SELECT id, text_content, source_hash, source_ref::text FROM chunks
                        WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                          AND source_hash=:hash AND embedding IS NULL
                        ORDER BY id
                        """).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("hash", job.sourceHash())
                .query((row, ignored) -> new PendingChunk(row.getObject("id", UUID.class),
                        row.getString("text_content"), row.getString("source_hash"),
                        row.getString("source_ref"))).list();
    }

    private static JobExecutionException jobFailure(RuntimeException exception) {
        if (exception instanceof EmbeddingProviderException provider) {
            return new JobExecutionException(provider.code(), "Embedding provider failed.", provider.retryable());
        }
        if (exception instanceof ApiException provider) {
            EmbeddingProviderException mapped = EmbeddingProviderException.from(provider);
            return new JobExecutionException(mapped.code(), "Embedding provider failed.", mapped.retryable());
        }
        return new JobExecutionException("PROVIDER_FAILED", "Embedding provider returned invalid output.", false);
    }

    private static String failureCode(RuntimeException exception) {
        if (exception instanceof EmbeddingProviderException provider) return provider.code();
        if (exception instanceof ApiException provider) return EmbeddingProviderException.from(provider).code();
        return "PROVIDER_FAILED";
    }

    private List<EmbeddedChunk> embeddedChunks(List<PendingChunk> chunks, int startIndex, int expectedCount,
                                               List<ChunkEmbeddingPort.Embedding> embeddings) {
        if (embeddings.size() != expectedCount || startIndex < 0 || startIndex + expectedCount > chunks.size()) {
            throw new IllegalArgumentException("Embedding count must match chunk count.");
        }
        return java.util.stream.IntStream.range(0, embeddings.size()).mapToObj(offset -> {
            PendingChunk chunk = chunks.get(startIndex + offset);
            ChunkEmbeddingPort.Embedding embedding = embeddings.get(offset);
            if (embedding.values().size() != 768) {
                throw new IllegalArgumentException("Embedding must have 768 values.");
            }
            float[] values = new float[embedding.values().size()];
            for (int valueIndex = 0; valueIndex < values.length; valueIndex++) {
                values[valueIndex] = embedding.values().get(valueIndex);
            }
            return new EmbeddedChunk(chunk.id(), chunk.text(), chunk.sourceHash(), chunk.sourceReference(),
                    new PGvector(values), embedding.model());
        }).toList();
    }

    private List<BatchEmbeddedChunk> embeddedBatchChunks(List<BatchChunk> chunks, int startIndex, int expectedCount,
                                                         List<ChunkEmbeddingPort.Embedding> embeddings) {
        if (embeddings.size() != expectedCount || startIndex < 0 || startIndex + expectedCount > chunks.size()) {
            throw new IllegalArgumentException("Embedding count must match chunk count.");
        }
        List<EmbeddedChunk> embedded = embeddedChunks(
                chunks.stream().map(BatchChunk::chunk).toList(), startIndex, expectedCount, embeddings);
        return java.util.stream.IntStream.range(0, embedded.size())
                .mapToObj(offset -> new BatchEmbeddedChunk(chunks.get(startIndex + offset).job(), embedded.get(offset)))
                .toList();
    }

    private int publish(JobQueue.ClaimedJob job, List<EmbeddedChunk> results) {
        return results.stream().mapToInt(result -> jdbc.sql("""
                        UPDATE chunks SET embedding=:embedding, embedding_model=:model
                        WHERE id=:id AND owner_id=:owner AND course_id=:course AND session_id=:session
                          AND text_content=:text AND source_hash=:hash
                          AND source_ref=CAST(:reference AS jsonb) AND embedding IS NULL
                        """).param("embedding", result.embedding()).param("model", result.model())
                .param("id", result.id()).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("text", result.text())
                .param("hash", result.sourceHash()).param("reference", result.sourceReference()).update()).sum();
    }

    private record PendingChunk(UUID id, String text, String sourceHash, String sourceReference) {}
    private record EmbeddedChunk(UUID id, String text, String sourceHash, String sourceReference,
                                 PGvector embedding, String model) {}
    private record BatchChunk(JobQueue.ClaimedJob job, PendingChunk chunk) {}
    private record BatchEmbeddedChunk(JobQueue.ClaimedJob job, EmbeddedChunk chunk) {}

    public record BatchOutcome(JobQueue.ClaimedJob job, JobPublication publication, JobExecutionException failure) {
        static BatchOutcome succeeded(JobQueue.ClaimedJob job, JobPublication publication) {
            return new BatchOutcome(job, publication, null);
        }

        static BatchOutcome failed(JobQueue.ClaimedJob job, JobExecutionException failure) {
            return new BatchOutcome(job, null, failure);
        }

        public boolean succeeded() {
            return failure == null;
        }
    }
}
