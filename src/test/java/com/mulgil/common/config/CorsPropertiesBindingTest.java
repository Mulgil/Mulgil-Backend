package com.mulgil.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesBindingTest {
    private static final List<String> PRODUCTION_ORIGINS = List.of(
            "https://web-three-ochre-20.vercel.app",
            "https://web-git-*-jungy2kyung-6444s-projects.vercel.app"
    );

    @Test
    void bindsProductionOriginsWhenDefaultProfileIsActive() {
        try (ConfigurableApplicationContext context = context("default")) {
            assertThat(cors(context).allowedOriginPatterns()).containsExactlyElementsOf(PRODUCTION_ORIGINS);
        }
    }

    @Test
    void bindsDevelopmentOriginsWhenDevProfileIsActive() {
        try (ConfigurableApplicationContext context = context("dev")) {
            assertThat(cors(context).allowedOriginPatterns())
                    .containsExactly("http://localhost:*", "http://127.0.0.1:*");
        }
    }

    @Test
    void bindsProductionOriginsWhenProdProfileIsActive() {
        try (ConfigurableApplicationContext context = context("prod")) {
            assertThat(cors(context).allowedOriginPatterns()).containsExactlyElementsOf(PRODUCTION_ORIGINS);
        }
    }

    @Test
    void bindsEnvironmentOverrideWhenProvided() {
        try (ConfigurableApplicationContext context = context(
                "prod",
                "--CORS_ALLOWED_ORIGIN_PATTERNS=https://override.example,http://localhost:4173"
        )) {
            assertThat(cors(context).allowedOriginPatterns())
                    .containsExactly("https://override.example", "http://localhost:4173");
        }
    }

    private ConfigurableApplicationContext context(String profile, String... arguments) {
        String[] commandLineArguments = new String[arguments.length + 1];
        commandLineArguments[0] = "--spring.profiles.active=" + profile;
        System.arraycopy(arguments, 0, commandLineArguments, 1, arguments.length);

        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .properties(
                        "JWT_HS256_SECRET_BASE64=test-secret",
                        "GOOGLE_OAUTH_CLIENT_ID=test-client",
                        "GOOGLE_CLOUD_PROJECT=test-project",
                        "GCS_BUCKET=test-bucket"
                )
                .run(commandLineArguments);
    }

    private MulgilProperties.Cors cors(ConfigurableApplicationContext context) {
        return context.getBean(MulgilProperties.class).cors();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MulgilProperties.class)
    static class TestConfiguration {
    }
}
