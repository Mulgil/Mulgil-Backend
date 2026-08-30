package com.mulgil.indexing;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkEmbedJobHandlerTest {
    @Test
    void rejectsEmbeddingCountMismatchBeforeAnyDatabaseWrite() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
        ChunkEmbeddingPort mismatched = new ChunkEmbeddingPort() {
            @Override
            public Embedding embed(String text) {
                throw new AssertionError("single embed must not be called");
            }

            @Override
            public List<Embedding> embedAll(List<String> texts) {
                return List.of(new Embedding(Collections.nCopies(768, 1f), "fake"));
            }
        };
        when(ports.getIfAvailable()).thenReturn(mismatched);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", 5));
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(
                jdbc, ports, properties, mock(AiProviderUsageLedger.class));
        UUID owner = UUID.randomUUID();
        JobQueue.ClaimedJob job = new JobQueue.ClaimedJob(UUID.randomUUID(), "chunk_embed", owner,
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                1, "a".repeat(64), 1, 3, "test");

        assertThatThrownBy(() -> handler.handle(job))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Embedding count must match chunk count.");

        verify(jdbc, never()).sql(contains("UPDATE chunks"));
    }
}
