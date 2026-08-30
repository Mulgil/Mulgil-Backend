package com.mulgil.generation;

import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VertexGenerationModelAdapterTest {
    @Test
    void enforcesGroundedStructuredJsonSchema_inVertexRequestConfiguration() {
        GenerationConfig config = VertexGenerationModelAdapter.generationConfig();

        assertThat(config.getResponseMimeType()).isEqualTo("application/json");
        assertThat(config.hasResponseSchema()).isTrue();
        assertThat(config.getResponseSchema().getType()).isEqualTo(Type.OBJECT);
        assertThat(config.getResponseSchema().getRequiredList())
                .containsExactlyInAnyOrder("summary", "mindmap", "quizQuestions");
    }
}
