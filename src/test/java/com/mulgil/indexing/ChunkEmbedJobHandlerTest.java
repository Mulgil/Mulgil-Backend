package com.mulgil.indexing;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.embedding.EmbeddingProviderException;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkEmbedJobHandlerTest {
    @Test
    void mapsTransientFailureAfterCheckpointToRetryableJobFailure_andKeepsMatchingLedgerCode() throws Exception {
        AtomicInteger checkpoints = new AtomicInteger();
        JdbcClient jdbc = jdbcWithChunks(List.of("first", "second", "third"), checkpoints);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
        ChunkEmbeddingPort port = new ChunkEmbeddingPort() {
            @Override
            public Embedding embed(String text) {
                throw new AssertionError("single embed must not be called");
            }

            @Override
            public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                List<Embedding> first = observer.observe(0, List.of(texts.getFirst()),
                        () -> List.of(new Embedding(Collections.nCopies(768, 1f), "fake")));
                observer.checkpoint(0, first);
                try {
                    return observer.observe(1, List.of(texts.get(1)), () -> { throw unavailable(); });
                } catch (ApiException exception) {
                    throw EmbeddingProviderException.from(exception);
                }
            }
        };
        when(ports.getIfAvailable()).thenReturn(port);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", 5));
        AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
        AiProviderUsageLedger.UsageHandle firstUsage = usageHandle();
        AiProviderUsageLedger.UsageHandle failedUsage = usageHandle();
        when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(firstUsage, failedUsage);
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

        assertThatThrownBy(() -> handler.handle(job()))
                .isInstanceOfSatisfying(JobHandler.JobExecutionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.retryable()).isTrue();
                });

        assertThat(checkpoints).hasValue(1);
        verify(usage).succeed(firstUsage);
        verify(usage).fail(failedUsage, "PROVIDER_UNAVAILABLE");
        verify(usage, times(2)).begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong());
    }

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

            @Override
            public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                return embedAll(texts);
            }
        };
        when(ports.getIfAvailable()).thenReturn(mismatched);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", 5));
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(
                jdbc, ports, properties, mock(AiProviderUsageLedger.class));
        assertThatThrownBy(() -> handler.handle(job()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Embedding count must match chunk count.");

        verify(jdbc, never()).sql(contains("UPDATE chunks"));
    }

    private static JdbcClient jdbcWithChunks(List<String> texts, AtomicInteger checkpoints) throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec select = mock(JdbcClient.StatementSpec.class);
        JdbcClient.StatementSpec update = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("SELECT id") ? select : update);
        when(select.param(anyString(), any())).thenReturn(select);
        when(update.param(anyString(), any())).thenReturn(update);
        when(update.update()).thenAnswer(ignored -> { checkpoints.incrementAndGet(); return 1; });
        when(select.query(any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(0);
            List<Object> rows = new ArrayList<>();
            for (String text : texts) {
                ResultSet row = mock(ResultSet.class);
                when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                when(row.getString("text_content")).thenReturn(text);
                when(row.getString("source_hash")).thenReturn("a".repeat(64));
                when(row.getString("source_ref")).thenReturn("{}");
                rows.add(mapper.mapRow(row, rows.size()));
            }
            @SuppressWarnings("unchecked")
            JdbcClient.MappedQuerySpec<Object> query = mock(JdbcClient.MappedQuerySpec.class);
            when(query.list()).thenReturn(rows);
            return query;
        });
        return jdbc;
    }

    private static AiProviderUsageLedger.UsageHandle usageHandle() {
        return new AiProviderUsageLedger.UsageHandle(
                UUID.randomUUID(), UUID.randomUUID(), "vertex.embed", "vertex", "embedding", 1L);
    }

    private static ApiException unavailable() {
        ApiException exception = mock(ApiException.class);
        StatusCode status = mock(StatusCode.class);
        when(status.getCode()).thenReturn(StatusCode.Code.UNAVAILABLE);
        when(exception.getStatusCode()).thenReturn(status);
        return exception;
    }

    private static JobQueue.ClaimedJob job() {
        return new JobQueue.ClaimedJob(UUID.randomUUID(), "chunk_embed", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                1, "a".repeat(64), 1, 3, "test");
    }
}
