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
    static final int MAX_MINDMAP_NODES = 24;
    static final int MAX_MINDMAP_EDGES = 36;
    static final int MAX_MINDMAP_LABEL_CODE_POINTS = 80;
    static final int MAX_MINDMAP_ID_CODE_POINTS = 64;
    static final int MAX_MINDMAP_SOURCE_IDS = 3;

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
        } catch (InvalidSourceReferenceException exception) {
            throw invalidSourceReferences();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidGenerationOutput();
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
        require(nodes.isArray() && !nodes.isEmpty() && nodes.size() <= MAX_MINDMAP_NODES
                && edges.isArray() && edges.size() <= MAX_MINDMAP_EDGES);
        Set<String> ids = new HashSet<>();
        nodes.forEach(node -> {
            String id = boundedNonblank(node, "id", MAX_MINDMAP_ID_CODE_POINTS);
            boundedNonblank(node, "label", MAX_MINDMAP_LABEL_CODE_POINTS);
            resolveRefs(node, citations, MAX_MINDMAP_SOURCE_IDS);
            ids.add(id);
        });
        edges.forEach(edge -> {
            String from = boundedNonblank(edge, "from", MAX_MINDMAP_ID_CODE_POINTS);
            String to = boundedNonblank(edge, "to", MAX_MINDMAP_ID_CODE_POINTS);
            require(ids.contains(from) && ids.contains(to));
        });
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
        resolveRefs(node, citations, Integer.MAX_VALUE);
    }

    private static void resolveRefs(JsonNode node, Map<String, JsonNode> citations, int maxSourceIds) {
        require(node.isObject());
        ObjectNode grounded = (ObjectNode) node;
        JsonNode sourceIds = grounded.path("sourceIds");
        if (!sourceIds.isArray() || sourceIds.isEmpty()) throw new InvalidSourceReferenceException();
        require(sourceIds.size() <= maxSourceIds);
        ArrayNode sourceRefs = grounded.putArray("sourceRefs");
        sourceIds.forEach(sourceId -> {
            require(sourceId.isTextual());
            JsonNode sourceReference = citations.get(sourceId.asText());
            if (sourceReference == null) throw new InvalidSourceReferenceException();
            sourceRefs.add(sourceReference.deepCopy());
        });
        grounded.remove("sourceIds");
    }

    private static String boundedNonblank(JsonNode node, String field, int maxCodePoints) {
        require(nonblank(node, field));
        String value = node.path(field).asText();
        require(value.codePointCount(0, value.length()) <= maxCodePoints);
        return value;
    }

    private static boolean nonblank(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).asText().isBlank();
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("Invalid grounded generation output.");
    }

    private static JobHandler.JobExecutionException invalidSourceReferences() {
        return new JobHandler.JobExecutionException("INVALID_SOURCE_REFERENCES",
                "Generated content did not resolve to the current source snapshot.", false);
    }

    private static JobHandler.JobExecutionException invalidGenerationOutput() {
        return new JobHandler.JobExecutionException("INVALID_GENERATION_OUTPUT",
                "Generated content was invalid.", false);
    }

    private static final class InvalidSourceReferenceException extends IllegalArgumentException {}

    record Output(JsonNode summary, JsonNode mindmapNodes, JsonNode mindmapEdges,
                  JsonNode questions, List<JsonNode> sourceReferences) {}
}
