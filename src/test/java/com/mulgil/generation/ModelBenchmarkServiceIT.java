package com.mulgil.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ModelBenchmarkServiceIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil")
            .withUsername("mulgil")
            .withPassword("mulgil");

    @Test
    void persistsDirectUsageAndFinishReasonForBenchmarkSuccessAndProviderFailure() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load().migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        ObjectMapper json = new ObjectMapper();
        UUID owner = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO users VALUES (:id,'google',:subject,:email,'Benchmark Owner',now())")
                .param("id", owner).param("subject", "benchmark-" + owner)
                .param("email", owner + "@example.test").update();
        var source = new GenerationSnapshotService.Source("note", sourceId, 1, "a".repeat(64), "source",
                json.readTree("{\"sourceType\":\"note\",\"noteId\":\"" + sourceId
                        + "\",\"contentBlockId\":\"" + UUID.randomUUID()
                        + "\",\"paragraphOffset\":0,\"inputVersion\":1}"), true);
        var snapshot = new GenerationSnapshotService.Snapshot("review", owner, UUID.randomUUID(),
                UUID.randomUUID(), null, List.of(source), source.canonical(), "b".repeat(64), true,
                GenerationSnapshotService.Readiness.READY);
        GenerationModelPort.GenerationResult success = new GenerationModelPort.GenerationResult(
                "{\"summary\":{\"items\":[{\"text\":\"ok\",\"sourceIds\":[\"s1\"]}]}}",
                new GenerationModelPort.GenerationUsage(2L, 3L, 5L, 0L, 0L), 1L, "STOP");
        GenerationModelPort.GenerationResult outputLimit = new GenerationModelPort.GenerationResult(
                "{", new GenerationModelPort.GenerationUsage(20L, 8L, 41L, 0L, 13L), 2L,
                "MAX_TOKENS");
        GenerationModelPort model = mock(GenerationModelPort.class);
        when(model.benchmark(any(), eq("gemini-candidate"))).thenReturn(success)
                .thenThrow(new GenerationModelPort.GenerationModelException(
                        "PROVIDER_OUTPUT_LIMIT", false, outputLimit));
        @SuppressWarnings("unchecked") ObjectProvider<GenerationModelPort> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.modelBenchmark()).thenReturn(new MulgilProperties.ModelBenchmark(
                true, List.of("gemini-candidate"), 30));
        ModelBenchmarkService service = new ModelBenchmarkService(jdbc, models, properties,
                new GenerationOutputValidator(json), new GenerationInputCompiler(),
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        assertThat(service.run(snapshot, GenerationModelPort.Artifact.SUMMARY, "gemini-candidate")
                .validOutput()).isTrue();
        assertThatThrownBy(() -> service.run(snapshot, GenerationModelPort.Artifact.SUMMARY,
                "gemini-candidate")).isInstanceOf(GenerationModelPort.GenerationModelException.class);

        assertThat(jdbc.sql("""
                SELECT thoughts_token_count||':'||finish_reason FROM generation_model_benchmarks
                WHERE valid_output
                """).query(String.class).single()).isEqualTo("0:STOP");
        assertThat(jdbc.sql("""
                SELECT failure_code||':'||thoughts_token_count||':'||finish_reason
                FROM generation_model_benchmarks WHERE NOT valid_output
                """).query(String.class).single()).isEqualTo("PROVIDER_OUTPUT_LIMIT:13:MAX_TOKENS");
        System.out.println("BENCHMARK_USAGE_DB success=0:STOP failure=PROVIDER_OUTPUT_LIMIT:13:MAX_TOKENS result=PASS");
    }
}
