package com.mulgil.generation;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentRequest;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Profile("!test & !smoke")
final class VertexGenerationModelAdapter implements GenerationModelPort {
    private static final String GROUNDED_JSON_CONTRACT = """
            Generate only from the supplied sources. Copy every sourceRef exactly from a supplied source.
            Return one JSON object with:
            - summary.items: non-empty array of {text, sourceRefs}; summary.tables is optional.
            - mindmap.nodes: non-empty array of {id, label, sourceRefs}; mindmap.edges: array of {from, to}.
            - quizQuestions: non-empty array whose items contain type (true_false or multiple_choice),
              question {text, sourceRefs, and exactly four options for multiple_choice},
              answer {value, sourceRefs}, and explanation {text, sourceRefs}.
            Every sourceRefs array must be non-empty. Do not invent or alter references.
            """;
    private final MulgilProperties properties;

    VertexGenerationModelAdapter(MulgilProperties properties) {
        this.properties = properties;
    }

    @Override
    public String generateJson(String prompt, String responseSchema) {
        String location = properties.google().cloudLocation();
        String model = "projects/%s/locations/%s/publishers/google/models/%s".formatted(
                properties.google().cloudProject(), location, properties.vertex().generationModel());
        GenerateContentRequest request = GenerateContentRequest.newBuilder()
                .setModel(model)
                .addContents(Content.newBuilder().setRole("user").addParts(Part.newBuilder()
                        .setText(GROUNDED_JSON_CONTRACT + "\nContract version: " + responseSchema
                                + "\nInput: " + prompt)))
                .setGenerationConfig(GenerationConfig.newBuilder().setResponseMimeType("application/json"))
                .build();
        try (PredictionServiceClient client = PredictionServiceClient.create(
                PredictionServiceSettings.newBuilder()
                        .setEndpoint(location + "-aiplatform.googleapis.com:443").build())) {
            return responseText(client.generateContent(request));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Vertex generation client.", exception);
        }
    }

    private static String responseText(GenerateContentResponse response) {
        if (response.getCandidatesCount() == 0
                || response.getCandidates(0).getContent().getPartsCount() == 0) {
            throw new IllegalStateException("Vertex returned no generated JSON.");
        }
        String value = response.getCandidates(0).getContent().getParts(0).getText().strip();
        if (value.isEmpty()) throw new IllegalStateException("Vertex returned empty generated JSON.");
        return value;
    }
}
