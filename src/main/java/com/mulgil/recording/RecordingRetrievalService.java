package com.mulgil.recording;

import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.AiProviderUsageLedger;
import com.pgvector.PGvector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class RecordingRetrievalService {
    private final JdbcClient jdbc;
    private final ObjectProvider<ChunkEmbeddingPort> embeddings;
    private final MulgilProperties properties;
    private final AiProviderUsageLedger usage;

    RecordingRetrievalService(JdbcClient jdbc, ObjectProvider<ChunkEmbeddingPort> embeddings,
                              MulgilProperties properties, AiProviderUsageLedger usage) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.properties = properties;
        this.usage = usage;
    }

    public List<Result> search(UUID ownerId, UUID courseId, UUID sessionId, String query, int limit) {
        if (limit < 1) throw new IllegalArgumentException("Limit must be positive.");
        ChunkEmbeddingPort port = embeddings.getIfAvailable();
        if (port == null) return List.of();
        List<Float> values = usage.observe(ownerId, "vertex.embed", "vertex",
                properties.vertex().embeddingModel(), "unicode_code_point", query.codePoints().count(),
                () -> port.embed(query)).values();
        float[] vector = new float[values.size()];
        for (int index = 0; index < vector.length; index++) vector[index] = values.get(index);
        return jdbc.sql("""
                        SELECT id,text_content,source_ref::text,embedding <=> :query AS distance
                        FROM chunks
                        WHERE owner_id=:owner AND course_id=:course AND session_id=:session
                          AND embedding IS NOT NULL
                        ORDER BY embedding <=> :query,id LIMIT :limit
                        """).param("query", new PGvector(vector)).param("owner", ownerId)
                .param("course", courseId).param("session", sessionId).param("limit", limit)
                .query((row, ignored) -> new Result(row.getObject("id", UUID.class), row.getString("text_content"),
                        row.getString("source_ref"), row.getDouble("distance"))).list();
    }

    public record Result(UUID id, String text, String sourceReference, double distance) {}
}
