package com.mulgil.generation;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentRequest;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.PredictionServiceClient;
import com.google.cloud.vertexai.api.PredictionServiceSettings;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Type;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Profile("!test & !smoke")
final class VertexGenerationModelAdapter implements GenerationModelPort {
    private static final String GROUNDED_JSON_CONTRACT = """
            Generate only from the supplied sources. Cite each claim with sourceIds copied exactly from supplied citationId values.
            Return one JSON object with:
            - summary.items: non-empty array of {text, sourceIds}; summary.tables is optional.
            - mindmap.nodes: non-empty array of {id, label, sourceIds}; mindmap.edges: array of {from, to}.
            - quizQuestions: non-empty array whose items contain type (true_false or multiple_choice),
              question {text, sourceIds, and exactly four options for multiple_choice},
              answer {value, sourceIds}, and explanation {text, sourceIds}.
            Every sourceIds array must be non-empty. Do not invent or alter citation IDs.
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
                .setGenerationConfig(generationConfig())
                .build();
        try (PredictionServiceClient client = PredictionServiceClient.create(
                PredictionServiceSettings.newBuilder()
                        .setEndpoint(apiEndpoint(location)).build())) {
            return responseText(client.generateContent(request));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Vertex generation client.", exception);
        }
    }

    static String apiEndpoint(String location) {
        return ("global".equals(location) ? "" : location + "-") + "aiplatform.googleapis.com:443";
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

    static GenerationConfig generationConfig() {
        Schema sourceIds = array(scalar(Type.STRING)).toBuilder().setMinItems(1).build();
        Schema groundedText = object()
                .putProperties("text", scalar(Type.STRING))
                .putProperties("sourceIds", sourceIds)
                .addRequired("text").addRequired("sourceIds").build();
        Schema summary = object().putProperties("items", array(groundedText))
                .addRequired("items").build();
        Schema node = object().putProperties("id", scalar(Type.STRING))
                .putProperties("label", scalar(Type.STRING)).putProperties("sourceIds", sourceIds)
                .addRequired("id").addRequired("label").addRequired("sourceIds").build();
        Schema edge = object().putProperties("from", scalar(Type.STRING))
                .putProperties("to", scalar(Type.STRING)).addRequired("from").addRequired("to").build();
        Schema mindmap = object().putProperties("nodes", array(node)).putProperties("edges", array(edge))
                .addRequired("nodes").addRequired("edges").build();
        Schema prompt = groundedText.toBuilder().putProperties("options", array(scalar(Type.STRING))).build();
        Schema answer = object().putProperties("value", Schema.newBuilder()
                        .addAnyOf(scalar(Type.STRING)).addAnyOf(scalar(Type.BOOLEAN)).build())
                .putProperties("sourceIds", sourceIds).addRequired("value").addRequired("sourceIds").build();
        Schema question = object().putProperties("type", Schema.newBuilder().setType(Type.STRING)
                        .addEnum("true_false").addEnum("multiple_choice").build())
                .putProperties("question", prompt).putProperties("answer", answer)
                .putProperties("explanation", groundedText).addRequired("type").addRequired("question")
                .addRequired("answer").addRequired("explanation").build();
        Schema response = object().putProperties("summary", summary).putProperties("mindmap", mindmap)
                .putProperties("quizQuestions", array(question)).addRequired("summary")
                .addRequired("mindmap").addRequired("quizQuestions").build();
        return GenerationConfig.newBuilder().setResponseMimeType("application/json")
                .setResponseSchema(response).build();
    }

    private static Schema.Builder object() {
        return Schema.newBuilder().setType(Type.OBJECT);
    }

    private static Schema scalar(Type type) {
        return Schema.newBuilder().setType(type).build();
    }

    private static Schema array(Schema items) {
        return Schema.newBuilder().setType(Type.ARRAY).setItems(items).build();
    }
}
