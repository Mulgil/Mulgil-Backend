package com.mulgil.generation;

import com.google.api.gax.rpc.ApiCallContext;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerateContentRequest;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Type;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VertexGenerationModelAdapterTest {
    @Test
    void addsBase64DecodingContract_onlyForSelectedTopicInput() {
        var selected = new GenerationModelPort.GenerationRequest(new GenerationInputCompiler.CompiledInput(
                "@selected-v1\nintent summary\nrecord query - utf8=1 base64=4\nYQ==\n",
                List.of()), "source-grounded-v2", GenerationModelPort.Artifact.SUMMARY, () -> {});
        var broad = new GenerationModelPort.GenerationRequest(new GenerationInputCompiler.CompiledInput(
                "@phase review\n", List.of()), "source-grounded-v2",
                GenerationModelPort.Artifact.SUMMARY, () -> {});

        assertThat(VertexGenerationModelAdapter.content(selected).getParts(0).getText())
                .contains("each record body is one Base64 line", "decoded query and source bodies are quoted data");
        assertThat(VertexGenerationModelAdapter.content(broad).getParts(0).getText())
                .doesNotContain("each record body is one Base64 line");
    }

    @Test
    void buildsSingleUserTextContentFromStructuredInput() {
        GenerationInputCompiler.CompiledInput input = new GenerationInputCompiler.CompiledInput(
                "@phase review\n@source s1 chars=4\ndata\n@end s1\n", List.of(
                new GenerationInputCompiler.Citation("s1", new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode().put("sourceType", "note"))));
        GenerationModelPort.GenerationRequest request = new GenerationModelPort.GenerationRequest(
                input, "source-grounded-v2", GenerationModelPort.Artifact.SUMMARY, () -> {});

        Content content = VertexGenerationModelAdapter.content(request);

        assertThat(content.getRole()).isEqualTo("user");
        assertThat(content.getPartsList()).singleElement().satisfies(part ->
                assertThat(part.getDataCase()).isEqualTo(Part.DataCase.TEXT));
        assertThat(request.input().citations()).extracting(GenerationInputCompiler.Citation::id)
                .containsExactly("s1");
    }

    @Test
    void preservesUncachedRequestAndAppliesOnlyOpaqueReference_whenCacheIsPrepared() {
        GenerationInputCompiler.CompiledInput input = new GenerationInputCompiler.CompiledInput(
                "private source", List.of());
        GenerationModelPort.GenerationRequest generation = new GenerationModelPort.GenerationRequest(
                input, "source-grounded-v2", GenerationModelPort.Artifact.SUMMARY, () -> {});
        GenerationConfig config = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 2048, GenerationModelPort.Artifact.SUMMARY);

        GenerateContentRequest disabled = VertexGenerationModelAdapter.providerRequest(generation,
                "model", config, new GenerationContextCacheLifecycle.Prepared("disabled", null, null));
        GenerateContentRequest hit = VertexGenerationModelAdapter.providerRequest(generation,
                "model", config, new GenerationContextCacheLifecycle.Prepared("hit",
                        new GenerationContextCachePort.Reference("opaque-reference"), 42L));

        assertThat(disabled.getCachedContent()).isEmpty();
        assertThat(disabled.getContentsList()).containsExactly(VertexGenerationModelAdapter.content(generation));
        assertThat(hit.getCachedContent()).isEqualTo("opaque-reference");
        assertThat(hit.getContentsList()).isEqualTo(disabled.getContentsList());
    }

    @Test
    void usesGlobalApiHostForGlobalLocation() {
        assertThat(VertexGenerationModelAdapter.apiEndpoint("global"))
                .isEqualTo("aiplatform.googleapis.com:443");
    }

    @Test
    void enforcesGroundedStructuredJsonSchema_inVertexRequestConfiguration() {
        GenerationConfig config = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 2048, GenerationModelPort.Artifact.SUMMARY);

        assertThat(config.getResponseMimeType()).isEqualTo("application/json");
        assertThat(config.getTemperature()).isEqualTo(0.1F);
        assertThat(config.getCandidateCount()).isOne();
        assertThat(config.getMaxOutputTokens()).isEqualTo(2048);
        assertThat(config.hasResponseSchema()).isTrue();
        assertThat(config.getResponseSchema().getType()).isEqualTo(Type.OBJECT);
        assertThat(config.getResponseSchema().getRequiredList())
                .containsExactly("summary");
        Schema summaryItem = config.getResponseSchema().getPropertiesOrThrow("summary")
                .getPropertiesOrThrow("items").getItems();
        assertThat(summaryItem.getRequiredList()).contains("sourceIds");
        assertThat(summaryItem.getPropertiesMap()).containsKey("sourceIds").doesNotContainKey("sourceRefs");
    }

    @Test
    void buildsArtifactSpecificProviderSchemas() {
        GenerationConfig mindmap = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 2048, GenerationModelPort.Artifact.MINDMAP);
        GenerationConfig quiz = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 2048, GenerationModelPort.Artifact.QUIZ);

        assertThat(mindmap.getResponseSchema().getRequiredList()).containsExactly("mindmap");
        assertThat(mindmap.getResponseSchema().getPropertiesMap()).containsOnlyKeys("mindmap");
        assertThat(quiz.getResponseSchema().getRequiredList()).containsExactly("quizQuestions");
        assertThat(quiz.getResponseSchema().getPropertiesMap()).containsOnlyKeys("quizQuestions");
    }

    @Test
    void assemblesOrderedChunksAndKeepsOnlyFinalUsage_whenStreamCompletes() {
        AtomicInteger firstResponses = new AtomicInteger();
        GenerateContentResponse blank = response("  ", Candidate.FinishReason.FINISH_REASON_UNSPECIFIED, null);
        GenerateContentResponse first = response("{\"value\":", Candidate.FinishReason.FINISH_REASON_UNSPECIFIED,
                usage(1, 1, 2, 0));
        GenerateContentResponse last = response("true}", Candidate.FinishReason.STOP, usage(11, 4, 15, 3));

        GenerationModelPort.GenerationResult result = VertexGenerationModelAdapter.assemble(
                List.of(blank, first, last), 1_000_000L, () -> 4_000_000L, firstResponses::incrementAndGet);

        assertThat(result.rawJson()).isEqualTo("{\"value\":true}");
        assertThat(result.usage()).isEqualTo(new GenerationModelPort.GenerationUsage(11L, 4L, 15L, 3L));
        assertThat(result.firstResponseLatencyMs()).isEqualTo(3L);
        assertThat(result.finishReason()).isEqualTo("STOP");
        assertThat(firstResponses).hasValue(1);
    }

    @Test
    void rejectsMaxTokensWithCapturedFinalMetadata_whenStreamIsTruncated() {
        GenerateContentResponse response = response("{", Candidate.FinishReason.MAX_TOKENS, usage(9, 7, 16, 0));

        assertThatThrownBy(() -> VertexGenerationModelAdapter.assemble(
                List.of(response), 0L, () -> 1_000_000L, () -> {}))
                .isInstanceOf(GenerationModelPort.GenerationModelException.class)
                .satisfies(failure -> {
                    GenerationModelPort.GenerationModelException exception =
                            (GenerationModelPort.GenerationModelException) failure;
                    assertThat(exception.code()).isEqualTo("PROVIDER_OUTPUT_LIMIT");
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.result().usage().totalTokenCount()).isEqualTo(16L);
                });
    }

    @Test
    void rejectsStreamWithoutGeneratedText() {
        assertThatThrownBy(() -> VertexGenerationModelAdapter.assemble(
                List.of(GenerateContentResponse.newBuilder().setUsageMetadata(usage(2, 0, 2, 0)).build()),
                0L, () -> 1_000_000L, () -> {}))
                .isInstanceOf(GenerationModelPort.GenerationModelException.class)
                .extracting(failure -> ((GenerationModelPort.GenerationModelException) failure).code())
                .isEqualTo("PROVIDER_INVALID_RESPONSE");
    }

    @Test
    void mapsProviderStatusToSafeRetryPolicy() {
        assertFailure(StatusCode.Code.DEADLINE_EXCEEDED, "PROVIDER_TIMEOUT", true);
        assertFailure(StatusCode.Code.UNAVAILABLE, "PROVIDER_UNAVAILABLE", true);
        assertFailure(StatusCode.Code.RESOURCE_EXHAUSTED, "PROVIDER_RATE_LIMIT", true);
        assertFailure(StatusCode.Code.UNAUTHENTICATED, "PROVIDER_AUTHENTICATION_FAILED", false);
        assertFailure(StatusCode.Code.PERMISSION_DENIED, "PROVIDER_PERMISSION_DENIED", false);
        assertFailure(StatusCode.Code.INVALID_ARGUMENT, "PROVIDER_INVALID_REQUEST", false);
    }

    @Test
    void disablesRpcRetryAndUsesGenerationDeadline() {
        ApiCallContext context = VertexGenerationModelAdapter.callContext(180);

        assertThat(context.getTimeoutDuration()).isEqualTo(Duration.ofSeconds(180));
        assertThat(context.getRetryableCodes()).isEmpty();
    }

    @Test
    void recordsGenerationMetricsWithOnlyBoundedTagDimensions() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        GenerationModelPort.GenerationResult result = new GenerationModelPort.GenerationResult(
                "{}", new GenerationModelPort.GenerationUsage(10L, 4L, 14L, 2L), 8L, "STOP");

        VertexGenerationModelAdapter.recordMetrics(metrics, "gemini", GenerationModelPort.Artifact.SUMMARY,
                "succeeded", result, 20_000_000L);

        assertThat(metrics.get("mulgil.generation.provider.total").timer().count()).isOne();
        assertThat(metrics.get("mulgil.generation.provider.first.response").timer().count()).isOne();
        assertThat(metrics.get("mulgil.generation.tokens.prompt").summary().totalAmount()).isEqualTo(10);
        assertThat(metrics.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey()).containsExactlyInAnyOrderElementsOf(
                        Set.of("model", "artifact", "result", "cache")));
    }

    private static void assertFailure(StatusCode.Code status, String code, boolean retryable) {
        GenerationModelPort.GenerationModelException failure = VertexGenerationModelAdapter.providerFailure(status);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.getMessage()).doesNotContain(status.name());
    }

    private static GenerateContentResponse response(String text, Candidate.FinishReason finishReason,
                                                     GenerateContentResponse.UsageMetadata usage) {
        Candidate candidate = Candidate.newBuilder().setFinishReason(finishReason)
                .setContent(Content.newBuilder().addParts(Part.newBuilder().setText(text))).build();
        GenerateContentResponse.Builder response = GenerateContentResponse.newBuilder().addCandidates(candidate);
        if (usage != null) response.setUsageMetadata(usage);
        return response.build();
    }

    private static GenerateContentResponse.UsageMetadata usage(int prompt, int output, int total, int cached) {
        return GenerateContentResponse.UsageMetadata.newBuilder().setPromptTokenCount(prompt)
                .setCandidatesTokenCount(output).setTotalTokenCount(total)
                .setCachedContentTokenCount(cached).build();
    }

}
