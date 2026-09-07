package com.mulgil.common.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MulgilPropertiesGenerationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void bindsSafeDefaultAndExplicitZeroFromApplicationConfiguration() throws Exception {
        assertThat(bindGeneration(new MockEnvironment()).mindmapThinkingBudget()).isEqualTo(1024);
        assertThat(bindGeneration(new MockEnvironment().withProperty(
                "VERTEX_GENERATION_MINDMAP_THINKING_BUDGET", "0")).mindmapThinkingBudget()).isZero();
    }

    @Test
    void rejectsMalformedThinkingBudgetDuringBinding() {
        assertThatThrownBy(() -> bindGeneration(new MockEnvironment().withProperty(
                "VERTEX_GENERATION_MINDMAP_THINKING_BUDGET", "prompt-like-not-a-number")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void keepsSpringDatasourceAndConsoleLoggingPropertiesAtTheirSpringKeys() throws Exception {
        MockEnvironment environment = applicationEnvironment();

        DataSourceProperties datasource = Binder.get(environment).bind("spring.datasource",
                Bindable.of(DataSourceProperties.class))
                .orElseThrow(() -> new IllegalStateException("datasource configuration not bound"));

        assertThat(datasource.getUrl()).isEqualTo("jdbc:postgresql://localhost:5432/mulgil");
        assertThat(environment.getProperty("logging.pattern.console")).contains("%kvp");
    }

    @Test
    void acceptsThinkingBudgetBoundsBelowMindmapOutputLimit() {
        assertThat(validator.validate(generation(1024, 8192))).isEmpty();
        assertThat(validator.validate(generation(0, 8192))).isEmpty();
        assertThat(validator.validate(generation(24576, 24577))).isEmpty();
    }

    @Test
    void rejectsNegativeExcessiveAndNotBelowOutputLimitThinkingBudgets() {
        assertThat(validator.validate(generation(-1, 8192))).isNotEmpty();
        assertThat(validator.validate(generation(24577, 24578))).isNotEmpty();
        assertThat(validator.validate(generation(8192, 8192))).isNotEmpty();
        assertThat(validator.validate(generation(8193, 8192))).isNotEmpty();
    }

    private static MulgilProperties.Generation generation(int thinkingBudget, int mindmapMaxOutputTokens) {
        return new MulgilProperties.Generation(
                0.1, 1, 8192, mindmapMaxOutputTokens, thinkingBudget, 8192,
                180, 100000, false, 3600);
    }

    private static MulgilProperties.Generation bindGeneration(MockEnvironment environment) throws Exception {
        return Binder.get(applicationEnvironment(environment)).bind("mulgil.generation",
                Bindable.of(MulgilProperties.Generation.class))
                .orElseThrow(() -> new IllegalStateException("generation configuration not bound"));
    }

    private static MockEnvironment applicationEnvironment() throws Exception {
        return applicationEnvironment(new MockEnvironment());
    }

    private static MockEnvironment applicationEnvironment(MockEnvironment environment) throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
