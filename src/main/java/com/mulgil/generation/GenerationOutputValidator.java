package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulgil.job.JobHandler;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
final class GenerationOutputValidator {
    private final ObjectMapper json;

    GenerationOutputValidator(ObjectMapper json) {
        this.json = json;
    }

    Output parse(String raw, GenerationInputCompiler.CompiledInput input, GenerationModelPort.Artifact artifact)
            throws JobHandler.JobExecutionException {
        try {
            JsonNode root = json.readTree(raw);
            require(root != null && root.isObject());
            Map<String, JsonNode> citations = citations(input);
            JsonNode summary = root.path("summary");
            JsonNode mindmap = root.path("mindmap");
            JsonNode questions = root.path("quizQuestions");
            switch (artifact) {
                case SUMMARY -> validateSummary(summary, citations);
                case MINDMAP -> validateMindmap(mindmap, citations);
                case QUIZ -> validateQuestions(questions, citations);
            }
            return new Output(summary, mindmap.path("nodes"), mindmap.path("edges"), questions,
                    List.copyOf(citations.values()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static Map<String, JsonNode> citations(GenerationInputCompiler.CompiledInput input) {
        Map<String, JsonNode> citations = new LinkedHashMap<>();
        input.citations().forEach(citation -> citations.put(citation.id(), citation.sourceReference()));
        return citations;
    }

    private static void validateSummary(JsonNode summary, Map<String, JsonNode> citations) {
        JsonNode items = summary.path("items");
        require(items.isArray() && !items.isEmpty());
        items.forEach(item -> {
            require(nonblank(item, "text"));
            resolveRefs(item, citations);
        });
        JsonNode tables = summary.path("tables");
        if (tables.isArray()) tables.forEach(table -> table.path("rows").forEach(row ->
                row.path("cells").forEach(cell -> {
                    require(nonblank(cell, "text") || nonblank(cell, "value"));
                    resolveRefs(cell, citations);
                })));
    }

    private static void validateMindmap(JsonNode mindmap, Map<String, JsonNode> citations) {
        JsonNode nodes = mindmap.path("nodes");
        JsonNode edges = mindmap.path("edges");
        require(nodes.isArray() && !nodes.isEmpty() && edges.isArray());
        Set<String> ids = new HashSet<>();
        nodes.forEach(node -> {
            require(nonblank(node, "id") && nonblank(node, "label"));
            resolveRefs(node, citations);
            ids.add(node.path("id").asText());
        });
        edges.forEach(edge -> require(ids.contains(edge.path("from").asText())
                && ids.contains(edge.path("to").asText())));
    }

    private static void validateQuestions(JsonNode questions, Map<String, JsonNode> citations) {
        require(questions.isArray() && !questions.isEmpty());
        questions.forEach(question -> {
            String type = question.path("type").asText();
            require(type.equals("true_false") || type.equals("multiple_choice"));
            JsonNode prompt = question.path("question");
            require(nonblank(prompt, "text"));
            resolveRefs(prompt, citations);
            JsonNode answer = question.path("answer");
            require(answer.hasNonNull("value"));
            resolveRefs(answer, citations);
            JsonNode explanation = question.path("explanation");
            require(nonblank(explanation, "text"));
            resolveRefs(explanation, citations);
            if (type.equals("multiple_choice")) require(prompt.path("options").size() == 4);
        });
    }

    private static void resolveRefs(JsonNode node, Map<String, JsonNode> citations) {
        require(node.isObject());
        ObjectNode grounded = (ObjectNode) node;
        JsonNode sourceIds = grounded.path("sourceIds");
        require(sourceIds.isArray() && !sourceIds.isEmpty());
        ArrayNode sourceRefs = grounded.putArray("sourceRefs");
        sourceIds.forEach(sourceId -> {
            require(sourceId.isTextual());
            JsonNode sourceReference = citations.get(sourceId.asText());
            require(sourceReference != null);
            sourceRefs.add(sourceReference.deepCopy());
        });
        grounded.remove("sourceIds");
    }

    private static boolean nonblank(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid grounded generation output.");
    }

    private static JobHandler.JobExecutionException invalid() {
        return new JobHandler.JobExecutionException("INVALID_SOURCE_REFERENCES",
                "Generated content did not resolve to the current source snapshot.", false);
    }

    record Output(JsonNode summary, JsonNode mindmapNodes, JsonNode mindmapEdges,
                  JsonNode questions, List<JsonNode> sourceReferences) {}
}
