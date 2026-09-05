package com.mulgil.indexing;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.embedding.EmbeddingProviderException;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

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
    void returnsStaleInputWhenTerminalPublicationFindsChangedRow_andPublishesSibling() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil")) {
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("CREATE EXTENSION vector").update();
            jdbc.sql("""
                    CREATE TABLE chunks (
                        id uuid PRIMARY KEY, owner_id uuid NOT NULL, course_id uuid NOT NULL,
                        session_id uuid NOT NULL, text_content text NOT NULL, source_hash text NOT NULL,
                        source_ref jsonb NOT NULL, embedding vector(768), embedding_model text
                    )
                    """).update();
            UUID owner = UUID.randomUUID();
            UUID course = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            JobQueue.ClaimedJob staleJob = job(new UUID(0, 1), owner, course, session, "a".repeat(64));
            JobQueue.ClaimedJob currentJob = job(new UUID(0, 2), owner, course, session, "b".repeat(64));
            UUID staleChunk = UUID.randomUUID();
            UUID currentChunk = UUID.randomUUID();
            insertChunk(jdbc, staleChunk, staleJob, "stale");
            insertChunk(jdbc, currentChunk, currentJob, "current");
            @SuppressWarnings("unchecked")
            ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
            when(ports.getIfAvailable()).thenReturn(new ChunkEmbeddingPort() {
                @Override
                public Embedding embed(String text) {
                    throw new AssertionError("single embed must not be called");
                }

                @Override
                public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                    jdbc.sql("UPDATE chunks SET text_content='changed' WHERE id=:id")
                            .param("id", staleChunk).update();
                    return texts.stream().map(text ->
                            new Embedding(Collections.nCopies(768, 1f), "fake")).toList();
                }
            });
            MulgilProperties properties = mock(MulgilProperties.class);
            when(properties.vertex()).thenReturn(
                    new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
            AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
            when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenAnswer(ignored -> usageHandle());
            ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

            List<ChunkEmbedJobHandler.BatchOutcome> outcomes = handler.handleBatch(List.of(staleJob, currentJob));

            assertThat(outcomes).extracting(ChunkEmbedJobHandler.BatchOutcome::succeeded)
                    .containsExactly(false, true);
            assertThat(outcomes.getFirst().failure().code()).isEqualTo("STALE_INPUT");
            assertThat(outcomes.getFirst().failure().retryable()).isFalse();
            assertThat(jdbc.sql("SELECT embedding IS NULL FROM chunks WHERE id=:id")
                    .param("id", staleChunk).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT embedding IS NOT NULL FROM chunks WHERE id=:id")
                    .param("id", currentChunk).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void returnsStaleInputForChangedCheckpointRow_andKeepsOtherJobSuccessful() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil")) {
            postgres.start();
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("CREATE EXTENSION vector").update();
            jdbc.sql("""
                    CREATE TABLE chunks (
                        id uuid PRIMARY KEY, owner_id uuid NOT NULL, course_id uuid NOT NULL,
                        session_id uuid NOT NULL, text_content text NOT NULL, source_hash text NOT NULL,
                        source_ref jsonb NOT NULL, embedding vector(768), embedding_model text
                    )
                    """).update();
            UUID owner = UUID.randomUUID();
            UUID course = UUID.randomUUID();
            UUID session = UUID.randomUUID();
            JobQueue.ClaimedJob staleJob = job(new UUID(0, 1), owner, course, session, "a".repeat(64));
            JobQueue.ClaimedJob currentJob = job(new UUID(0, 2), owner, course, session, "b".repeat(64));
            UUID staleChunk = UUID.randomUUID();
            UUID currentChunk = UUID.randomUUID();
            insertChunk(jdbc, staleChunk, staleJob, "stale");
            insertChunk(jdbc, currentChunk, currentJob, "current");
            @SuppressWarnings("unchecked")
            ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
            when(ports.getIfAvailable()).thenReturn(new ChunkEmbeddingPort() {
                @Override
                public Embedding embed(String text) {
                    throw new AssertionError("single embed must not be called");
                }

                @Override
                public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                    jdbc.sql("UPDATE chunks SET text_content='changed' WHERE id=:id")
                            .param("id", staleChunk).update();
                    List<Embedding> completed = texts.stream().map(text ->
                            new Embedding(Collections.nCopies(768, 1f), "fake")).toList();
                    observer.checkpoint(0, completed);
                    throw unavailable();
                }
            });
            MulgilProperties properties = mock(MulgilProperties.class);
            when(properties.vertex()).thenReturn(
                    new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
            AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
            when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                    .thenAnswer(ignored -> usageHandle());
            ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

            List<ChunkEmbedJobHandler.BatchOutcome> outcomes = handler.handleBatch(List.of(staleJob, currentJob));

            assertThat(outcomes).extracting(ChunkEmbedJobHandler.BatchOutcome::succeeded)
                    .containsExactly(false, true);
            assertThat(outcomes.getFirst().failure().code()).isEqualTo("STALE_INPUT");
            assertThat(outcomes.getFirst().failure().retryable()).isFalse();
            assertThat(jdbc.sql("SELECT embedding IS NULL FROM chunks WHERE id=:id")
                    .param("id", staleChunk).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT embedding IS NOT NULL FROM chunks WHERE id=:id")
                    .param("id", currentChunk).query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void batchesFiveClaimedJobsIntoOneOrderedPortCall_andMapsVectorsToTheirJobs() throws Exception {
        List<JobQueue.ClaimedJob> jobs = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> job(new UUID(0, 5 - index), Integer.toString(index)))
                .toList();
        List<String> call = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        List<String> published = new ArrayList<>();
        JdbcClient jdbc = batchJdbc(published);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
        ChunkEmbeddingPort port = new ChunkEmbeddingPort() {
            @Override
            public Embedding embed(String text) {
                throw new AssertionError("single embed must not be called");
            }

            @Override
            public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                calls.incrementAndGet();
                call.addAll(texts);
                return texts.stream().map(text -> new Embedding(
                        Collections.nCopies(768, Float.parseFloat(text)), "fake")).toList();
            }
        };
        when(ports.getIfAvailable()).thenReturn(port);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
        AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
        when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(ignored -> usageHandle());
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

        List<ChunkEmbedJobHandler.BatchOutcome> outcomes = handler.handleBatch(jobs);
        outcomes.forEach(outcome -> outcome.publication().publish());

        assertThat(call).containsExactly("4", "3", "2", "1", "0");
        assertThat(calls).hasValue(1);
        assertThat(published).containsExactlyInAnyOrder("0:0.0", "1:1.0", "2:2.0", "3:3.0", "4:4.0");
        assertThat(outcomes).extracting(outcome -> outcome.job().id())
                .containsExactlyElementsOf(jobs.stream().map(JobQueue.ClaimedJob::id).toList());
        assertThat(outcomes).allMatch(ChunkEmbedJobHandler.BatchOutcome::succeeded);
        ArgumentCaptor<Long> characters = ArgumentCaptor.forClass(Long.class);
        verify(usage, times(5)).begin(any(), any(), anyString(), anyString(), anyString(), anyString(),
                characters.capture());
        assertThat(characters.getAllValues()).containsOnly(1L);
        verify(usage, times(5)).succeed(any(AiProviderUsageLedger.UsageHandle.class));
    }

    @Test
    void reportsOnlyCheckpointedJobsReadyAfterLaterProviderFailure() throws Exception {
        List<JobQueue.ClaimedJob> jobs = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> job(new UUID(0, index + 1), Integer.toString(index)))
                .toList();
        List<String> published = new ArrayList<>();
        JdbcClient jdbc = batchJdbc(published);
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
                        () -> List.of(new Embedding(Collections.nCopies(768, 0f), "fake")));
                observer.checkpoint(0, first);
                throw unavailable();
            }
        };
        when(ports.getIfAvailable()).thenReturn(port);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
        AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
        when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(ignored -> usageHandle());
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

        List<ChunkEmbedJobHandler.BatchOutcome> outcomes = handler.handleBatch(jobs);

        assertThat(outcomes).extracting(ChunkEmbedJobHandler.BatchOutcome::succeeded)
                .containsExactly(true, false, false);
        assertThat(outcomes.subList(1, 3)).extracting(outcome -> outcome.failure().code())
                .containsOnly("PROVIDER_UNAVAILABLE");
        assertThat(published).containsExactly("0:0.0");
        verify(usage).succeed(any(AiProviderUsageLedger.UsageHandle.class));
        verify(usage, times(2)).fail(any(AiProviderUsageLedger.UsageHandle.class), anyString());
    }

    @Test
    void returnsPerJobFailuresForBatchVectorCountMismatch_withoutPublishing() throws Exception {
        List<JobQueue.ClaimedJob> jobs = List.of(
                job(new UUID(0, 1), "0"), job(new UUID(0, 2), "1"));
        List<String> published = new ArrayList<>();
        JdbcClient jdbc = batchJdbc(published);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChunkEmbeddingPort> ports = mock(ObjectProvider.class);
        ChunkEmbeddingPort port = new ChunkEmbeddingPort() {
            @Override
            public Embedding embed(String text) {
                throw new AssertionError("single embed must not be called");
            }

            @Override
            public List<Embedding> embedAll(List<String> texts, ProviderCallObserver observer) {
                return List.of(new Embedding(Collections.nCopies(768, 0f), "fake"));
            }
        };
        when(ports.getIfAvailable()).thenReturn(port);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
        AiProviderUsageLedger usage = mock(AiProviderUsageLedger.class);
        when(usage.begin(any(), any(), anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(ignored -> usageHandle());
        ChunkEmbedJobHandler handler = new ChunkEmbedJobHandler(jdbc, ports, properties, usage);

        List<ChunkEmbedJobHandler.BatchOutcome> outcomes = handler.handleBatch(jobs);

        assertThat(outcomes).allMatch(outcome -> !outcome.succeeded());
        assertThat(outcomes).extracting(outcome -> outcome.failure().code()).containsOnly("PROVIDER_FAILED");
        assertThat(outcomes).extracting(outcome -> outcome.failure().retryable()).containsOnly(false);
        assertThat(published).isEmpty();
        verify(usage, times(2)).fail(any(AiProviderUsageLedger.UsageHandle.class), anyString());
    }

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
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
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
        when(properties.vertex()).thenReturn(new MulgilProperties.Vertex("generation", "embedding", "us-central1", 5));
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

    private static JobQueue.ClaimedJob job(UUID id, String marker) {
        return new JobQueue.ClaimedJob(id, "chunk_embed", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, null, null, 1, marker, 1, 3, "test");
    }

    private static JobQueue.ClaimedJob job(UUID id, UUID owner, UUID course, UUID session, String sourceHash) {
        return new JobQueue.ClaimedJob(id, "chunk_embed", owner, course, session,
                null, null, null, null, null, 1, sourceHash, 1, 3, "test");
    }

    private static void insertChunk(JdbcClient jdbc, UUID id, JobQueue.ClaimedJob job, String text) {
        jdbc.sql("""
                INSERT INTO chunks (id,owner_id,course_id,session_id,text_content,source_hash,source_ref)
                VALUES (:id,:owner,:course,:session,:text,:hash,'{}')
                """).param("id", id).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("text", text).param("hash", job.sourceHash()).update();
    }

    private static JdbcClient batchJdbc(List<String> published) throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
            java.util.Map<String, Object> parameters = new java.util.HashMap<>();
            when(statement.param(anyString(), any())).thenAnswer(parameter -> {
                parameters.put(parameter.getArgument(0), parameter.getArgument(1));
                return statement;
            });
            if (sql.contains("SELECT id")) {
                when(statement.query(any(RowMapper.class))).thenAnswer(queryInvocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<Object> mapper = queryInvocation.getArgument(0);
                    String marker = (String) parameters.get("hash");
                    ResultSet row = mock(ResultSet.class);
                    when(row.getObject("id", UUID.class)).thenReturn(new UUID(1, Long.parseLong(marker) + 1));
                    when(row.getString("text_content")).thenReturn(marker);
                    when(row.getString("source_hash")).thenReturn(marker);
                    when(row.getString("source_ref")).thenReturn("{}");
                    JdbcClient.MappedQuerySpec<Object> query = mock(JdbcClient.MappedQuerySpec.class);
                    Object mapped = mapper.mapRow(row, 0);
                    when(query.list()).thenReturn(List.of(mapped));
                    return query;
                });
            } else {
                when(statement.update()).thenAnswer(ignored -> {
                    PGvectorVector vector = new PGvectorVector(parameters.get("embedding"));
                    published.add(parameters.get("text") + ":" + vector.first());
                    return 1;
                });
            }
            return statement;
        });
        return jdbc;
    }

    private record PGvectorVector(Object value) {
        float first() {
            return ((com.pgvector.PGvector) value).toArray()[0];
        }
    }
}
