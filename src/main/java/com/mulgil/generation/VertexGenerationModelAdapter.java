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
                .setGenerationConfig(generationConfig())
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

    static GenerationConfig generationConfig() {
        Schema references = array(sourceReference()).toBuilder().setMinItems(1).build();
        Schema groundedText = object()
                .putProperties("text", scalar(Type.STRING))
                .putProperties("sourceRefs", references)
                .addRequired("text").addRequired("sourceRefs").build();
        Schema summary = object().putProperties("items", array(groundedText))
                .addRequired("items").build();
        Schema node = object().putProperties("id", scalar(Type.STRING))
                .putProperties("label", scalar(Type.STRING)).putProperties("sourceRefs", references)
                .addRequired("id").addRequired("label").addRequired("sourceRefs").build();
        Schema edge = object().putProperties("from", scalar(Type.STRING))
                .putProperties("to", scalar(Type.STRING)).addRequired("from").addRequired("to").build();
        Schema mindmap = object().putProperties("nodes", array(node)).putProperties("edges", array(edge))
                .addRequired("nodes").addRequired("edges").build();
        Schema prompt = groundedText.toBuilder().putProperties("options", array(scalar(Type.STRING))).build();
        Schema answer = object().putProperties("value", Schema.newBuilder()
                        .addAnyOf(scalar(Type.STRING)).addAnyOf(scalar(Type.BOOLEAN)).build())
                .putProperties("sourceRefs", references).addRequired("value").addRequired("sourceRefs").build();
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

    private static Schema sourceReference() {
        Schema.Builder reference = object().putProperties("sourceType", scalar(Type.STRING));
        for (String field : new String[]{"materialId", "examResourceId", "contentBlockId", "noteId",
                "handwritingBlockId", "recordingId", "transcriptSegmentId"}) {
            reference.putProperties(field, scalar(Type.STRING));
        }
        for (String field : new String[]{"pageNumber", "paragraphOffset", "inputVersion", "startMs", "endMs"}) {
            reference.putProperties(field, scalar(Type.INTEGER));
        }
        Schema bbox = object().putProperties("x", scalar(Type.NUMBER))
                .putProperties("y", scalar(Type.NUMBER)).putProperties("width", scalar(Type.NUMBER))
                .putProperties("height", scalar(Type.NUMBER)).addRequired("x").addRequired("y")
                .addRequired("width").addRequired("height").build();
        return reference.putProperties("bboxNorm", bbox)
                .addRequired("sourceType").build();
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
