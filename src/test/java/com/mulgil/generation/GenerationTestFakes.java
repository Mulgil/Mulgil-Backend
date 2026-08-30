package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ChunkEmbeddingPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;

@TestConfiguration
class GenerationTestFakes {
    @Bean
    @Primary
    ChunkEmbeddingPort embeddings() {
        return text -> new ChunkEmbeddingPort.Embedding(
                new ArrayList<>(Collections.nCopies(768, 0.1f)), "fake-embedding");
    }

    @Bean
    @Primary
    FakeGenerationModel generationModel(ObjectMapper json) {
        return new FakeGenerationModel(json);
    }
}

final class FakeGenerationModel implements GenerationModelPort {
    private final ObjectMapper json;
    volatile boolean valid = true;

    FakeGenerationModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String generateJson(String prompt, String responseSchema) {
        try {
            JsonNode sourceRef = json.readTree(prompt).path("sources").get(0).path("sourceRef");
            JsonNode refs = valid ? json.createArrayNode().add(sourceRef) : json.createArrayNode();
            var root = json.createObjectNode();
            root.putObject("summary").putArray("items").addObject().put("text", "Grounded summary")
                    .set("sourceRefs", refs.deepCopy());
            root.putObject("mindmap").putArray("nodes").addObject().put("id", "n1")
                    .put("label", "Grounded node").set("sourceRefs", refs.deepCopy());
            root.withObject("mindmap").putArray("edges");
            var question = root.putArray("quizQuestions").addObject();
            question.put("type", "true_false");
            question.putObject("question").put("text", "Grounded question").set("sourceRefs", refs.deepCopy());
            question.putObject("answer").put("value", true).set("sourceRefs", refs.deepCopy());
            question.putObject("explanation").put("text", "Grounded explanation")
                    .set("sourceRefs", refs.deepCopy());
            return json.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
