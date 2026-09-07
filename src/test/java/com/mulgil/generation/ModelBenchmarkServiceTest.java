package com.mulgil.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.when;

class ModelBenchmarkServiceTest {
    @Test
    void rejectsInvocation_whenFeatureIsOffOrCandidateIsNotAllowlisted() {
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.modelBenchmark()).thenReturn(new MulgilProperties.ModelBenchmark(
                false, List.of("gemini-candidate"), 30));
        ModelBenchmarkService service = new ModelBenchmarkService(mock(JdbcClient.class),
                mock(ObjectProvider.class), properties, new GenerationOutputValidator(new ObjectMapper()),
                new GenerationInputCompiler(), Clock.systemUTC());

        assertThatThrownBy(() -> service.run(null, GenerationModelPort.Artifact.SUMMARY,
                "gemini-candidate")).isInstanceOf(IllegalStateException.class)
                .hasMessage("Model benchmark is disabled.");

        when(properties.modelBenchmark()).thenReturn(new MulgilProperties.ModelBenchmark(
                true, List.of("gemini-candidate"), 30));
        assertThatThrownBy(() -> service.run(null, GenerationModelPort.Artifact.SUMMARY,
                "other-model")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Benchmark candidate is not allowlisted.");
    }

    @Test
    void approvalRequiresValidMeasurement_andBenchmarkCannotCallProductionPath() throws Exception {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.modelBenchmark()).thenReturn(new MulgilProperties.ModelBenchmark(
                true, List.of("gemini-candidate"), 30));
        GenerationModelPort model = mock(GenerationModelPort.class);
        ObjectMapper json = new ObjectMapper();
        UUID sourceId = UUID.randomUUID();
        var source = new GenerationSnapshotService.Source("note", sourceId, 1, "a".repeat(64), "source",
                json.readTree("{\"sourceType\":\"note\",\"noteId\":\"" + sourceId
                        + "\",\"contentBlockId\":\"" + UUID.randomUUID()
                        + "\",\"paragraphOffset\":0,\"inputVersion\":1}"), true);
        var snapshot = new GenerationSnapshotService.Snapshot("review", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, List.of(source), source.canonical(), "b".repeat(64), true,
                GenerationSnapshotService.Readiness.READY);
        when(model.benchmark(any(), contains("candidate"))).thenReturn(new GenerationModelPort.GenerationResult(
                "{\"summary\":{\"items\":[{\"text\":\"ok\",\"sourceIds\":[\"s1\"]}]}}",
                new GenerationModelPort.GenerationUsage(2L, 3L, 5L, 0L, 1L), 1L, "STOP"));
        @SuppressWarnings("unchecked") ObjectProvider<GenerationModelPort> models = mock(ObjectProvider.class);
        when(models.getIfAvailable()).thenReturn(model);
        var service = new ModelBenchmarkService(jdbc, models, properties,
                new GenerationOutputValidator(json), new GenerationInputCompiler(),
                Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), java.time.ZoneOffset.UTC));
        when(jdbc.sql(contains("generation_model_approvals")).param(anyString(), any())
                .query(Boolean.class).single()).thenReturn(false);

        assertThat(service.run(snapshot, GenerationModelPort.Artifact.SUMMARY, "gemini-candidate")
                .validOutput()).isTrue();
        verify(model, never()).generate(any());
        assertThat(service.operationallyApproved("gemini-candidate")).isFalse();
        ArgumentCaptor<GenerationModelPort.GenerationRequest> requests = ArgumentCaptor.forClass(
                GenerationModelPort.GenerationRequest.class);
        verify(model).benchmark(requests.capture(), eq("gemini-candidate"));
        assertThat(requests.getValue().responseSchema()).isEqualTo(GenerationScheduler.PROMPT_VERSION);
        verify(jdbc).sql(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("generation_model_approvals") && sql.contains("generation_model_benchmarks")
                        && sql.contains("valid_output")));
        var statements = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).sql(statements.capture());
        assertThat(statements.getAllValues()).noneMatch(sql -> sql.matches(
                "(?s).*\\b(INSERT|UPDATE|DELETE)\\s+(INTO\\s+)?(summaries|mindmaps|quiz_questions|generation_model_approvals)\\b.*"));
        assertThat(statements.getAllValues()).anyMatch(sql -> sql.contains("thoughts_token_count")
                && sql.contains("finish_reason"));
    }
}
