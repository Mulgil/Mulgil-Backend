package com.mulgil.indexing;

import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.common.config.MulgilProperties;
import com.pgvector.PGvector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
final class ChunkEmbedJobHandler implements JobHandler {
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
        List<PendingChunk> chunks = jdbc.sql("""
                        SELECT id, text_content, source_hash, source_ref::text FROM chunks
                        WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                          AND source_hash=:hash AND embedding IS NULL
                        ORDER BY id
                        """).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("hash", job.sourceHash())
                .query((row, ignored) -> new PendingChunk(row.getObject("id", UUID.class),
                        row.getString("text_content"), row.getString("source_hash"),
                        row.getString("source_ref"))).list();
        List<EmbeddedChunk> results = chunks.stream().map(chunk -> {
            ChunkEmbeddingPort.Embedding embedding = usage.observe(job, "vertex.embed", "vertex",
                    properties.vertex().embeddingModel(), "unicode_code_point",
                    chunk.text().codePoints().count(),
                    () -> port.embed(chunk.text()));
            if (embedding.values().size() != 768) throw new IllegalArgumentException("Embedding must have 768 values.");
            float[] values = new float[embedding.values().size()];
            for (int index = 0; index < values.length; index++) values[index] = embedding.values().get(index);
            return new EmbeddedChunk(chunk.id(), chunk.text(), chunk.sourceHash(), chunk.sourceReference(),
                    new PGvector(values), embedding.model());
        }).toList();
        return () -> results.forEach(result -> jdbc.sql("""
                        UPDATE chunks SET embedding=:embedding, embedding_model=:model
                        WHERE id=:id AND owner_id=:owner AND course_id=:course AND session_id=:session
                          AND text_content=:text AND source_hash=:hash
                          AND source_ref=CAST(:reference AS jsonb) AND embedding IS NULL
                        """).param("embedding", result.embedding()).param("model", result.model())
                .param("id", result.id()).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("text", result.text())
                .param("hash", result.sourceHash()).param("reference", result.sourceReference()).update());
    }

    private record PendingChunk(UUID id, String text, String sourceHash, String sourceReference) {}
    private record EmbeddedChunk(UUID id, String text, String sourceHash, String sourceReference,
                                 PGvector embedding, String model) {}
}
