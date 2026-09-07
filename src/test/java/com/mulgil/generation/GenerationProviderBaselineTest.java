package com.mulgil.generation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.google.cloud.vertexai.api.GenerateContentRequest;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.slf4j.event.KeyValuePair;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationProviderBaselineTest {
    @Test
    void mindmapRequestCarriesBoundedThinkingConfiguration_afterTask2() throws Exception {
        GenerationConfig config = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 8192, GenerationModelPort.Artifact.MINDMAP, "gemini-2.5-flash", 1024);
        GenerateContentRequest request = request(config);

        System.out.println("TASK2_PROVIDER_REQUEST_CONFIG=maxOutputTokens="
                + request.getGenerationConfig().getMaxOutputTokens() + " thinkingBudget="
                + request.getGenerationConfig().getThinkingConfig().getThinkingBudget());
        assertThat(request.getModel()).endsWith("/models/gemini-2.5-flash");
        assertThat(config.getThinkingConfig().getThinkingBudget()).isEqualTo(1024);
        assertThat(config.getMaxOutputTokens()).isEqualTo(8192);
    }

    @Test
    void quizProviderSchemaRequiresQuestionsAndBranchesByQuestionType_afterTask4() throws Exception {
        GenerationConfig config = VertexGenerationModelAdapter.generationConfig(
                0.1, 1, 2048, GenerationModelPort.Artifact.QUIZ);
        Schema questions = config.getResponseSchema().getPropertiesOrThrow("quizQuestions");
        Schema multipleChoice = questions.getItems().getAnyOf(1);
        Schema prompt = multipleChoice.getPropertiesOrThrow("question");

        System.out.println("TASK4_QUIZ_SCHEMA_QUESTIONS_MIN_ITEMS=" + questions.getMinItems());
        assertThat(questions.getMinItems()).isOne();
        assertThat(questions.getItems().getAnyOfCount()).isEqualTo(2);
        assertThat(prompt.getRequiredList()).contains("options");
    }

    @Test
    void springBootConsoleEncoderRendersSafeStructuredGenerationFields_afterTask5() throws Exception {
        String providerFailure = renderWithSpringBootConsoleEncoder(List.of(
                new KeyValuePair("event", "generation.provider.failed"),
                new KeyValuePair("jobId", "synthetic-job"),
                new KeyValuePair("operation", "review_mindmap_generate"),
                new KeyValuePair("artifact", "mindmap"),
                new KeyValuePair("errorCode", "PROVIDER_OUTPUT_LIMIT"),
                new KeyValuePair("finishReason", "MAX_TOKENS")));
        String outputRejected = renderWithSpringBootConsoleEncoder(List.of(
                new KeyValuePair("event", "generation.output.rejected"),
                new KeyValuePair("jobId", "synthetic-job"),
                new KeyValuePair("operation", "review_quiz_generate"),
                new KeyValuePair("artifact", "quiz"),
                new KeyValuePair("errorCode", "INVALID_GENERATION_OUTPUT"),
                new KeyValuePair("rule", "OPTIONS_COUNT"),
                new KeyValuePair("path", "quizQuestions[0].question.options"),
                new KeyValuePair("expectedCount", 4),
                new KeyValuePair("actualCount", 3)));
        String rendered = providerFailure + outputRejected;

        System.out.println("TASK1_CURRENT_ENCODER_OUTPUT=" + rendered.strip());
        assertThat(rendered).contains("generation.provider.failed", "synthetic-job", "review_mindmap_generate",
                "mindmap", "PROVIDER_OUTPUT_LIMIT", "MAX_TOKENS", "generation.output.rejected",
                "review_quiz_generate", "quiz", "INVALID_GENERATION_OUTPUT", "OPTIONS_COUNT",
                "quizQuestions[0].question.options", "expectedCount=\"4\"", "actualCount=\"3\"");
        assertThat(rendered).doesNotContain("ARBITRARY_MDC_SENTINEL", "RAW_INPUT_SENTINEL",
                "AUTH_TOKEN_SENTINEL", "CITATION_PAYLOAD_SENTINEL", "RAW_EXCEPTION_SENTINEL");
    }

    private static GenerateContentRequest request(GenerationConfig config) {
        var input = new GenerationInputCompiler.CompiledInput("synthetic-source-body", List.of());
        var generation = new GenerationModelPort.GenerationRequest(input, "source-grounded-v3",
                GenerationModelPort.Artifact.MINDMAP, () -> {});
        return VertexGenerationModelAdapter.providerRequest(generation,
                "projects/test/locations/global/publishers/google/models/gemini-2.5-flash", config,
                new GenerationContextCacheLifecycle.Prepared("disabled", null, null));
    }

    private static String renderWithSpringBootConsoleEncoder(List<KeyValuePair> pairs) throws Exception {
        LoggerContext context = new LoggerContext();
        try {
            context.putProperty("mulgil.test.console-pattern", productionConsolePattern());
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(GenerationProviderBaselineTest.class.getResource(
                    "/logback-task1-baseline.xml"));
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            OutputStreamAppender<?> appender = (OutputStreamAppender<?>) root.getAppender("CONSOLE");
            PatternLayoutEncoder encoder = (PatternLayoutEncoder) appender.getEncoder();
            LoggingEvent event = new LoggingEvent();
            event.setLoggerContext(context);
            event.setLoggerContextRemoteView(context.getLoggerContextRemoteView());
            event.setLoggerName("com.mulgil.generation.GenerationJobHandlers");
            event.setLevel(Level.WARN);
            event.setMessage("generation provider failed");
            pairs.forEach(event::addKeyValuePair);
            event.setMDCPropertyMap(Map.of("arbitraryMdc", "ARBITRARY_MDC_SENTINEL",
                    "input", "RAW_INPUT_SENTINEL", "authorization", "AUTH_TOKEN_SENTINEL",
                    "citations", "CITATION_PAYLOAD_SENTINEL", "exception", "RAW_EXCEPTION_SENTINEL"));
            event.setTimeStamp(0);
            return new String(encoder.encode(event), StandardCharsets.UTF_8);
        } finally {
            context.stop();
        }
    }

    private static String productionConsolePattern() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());
        return Objects.requireNonNull(properties.getProperty("logging.pattern.console"));
    }
}
