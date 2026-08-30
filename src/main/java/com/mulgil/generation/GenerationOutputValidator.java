package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.job.JobHandler;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
final class GenerationOutputValidator {
    private final ObjectMapper json;

    GenerationOutputValidator(ObjectMapper json) {
        this.json = json;
    }

    Output parse(String raw, GenerationSnapshotService.Snapshot snapshot, boolean session, boolean quiz)
            throws JobHandler.JobExecutionException {
        try {
            JsonNode root = json.readTree(raw);
            require(root != null && root.isObject());
            Set<JsonNode> allowed = new HashSet<>(snapshot.sources().stream()
                    .map(GenerationSnapshotService.Source::sourceReference).toList());
            JsonNode summary = root.path("summary");
            if (!quiz) validateSummary(summary, allowed);
            JsonNode mindmap = root.path("mindmap");
            if (session) validateMindmap(mindmap, allowed);
            JsonNode questions = root.path("quizQuestions");
            if (session || quiz) validateQuestions(questions, allowed);
            return new Output(summary, mindmap.path("nodes"), mindmap.path("edges"), questions,
                    List.copyOf(allowed));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void validateSummary(JsonNode summary, Set<JsonNode> allowed) {
        JsonNode items = summary.path("items");
        require(items.isArray() && !items.isEmpty());
        items.forEach(item -> {
            require(nonblank(item, "text"));
            validateRefs(item.path("sourceRefs"), allowed);
        });
        JsonNode tables = summary.path("tables");
        if (tables.isArray()) tables.forEach(table -> table.path("rows").forEach(row ->
                row.path("cells").forEach(cell -> {
                    require(nonblank(cell, "text") || nonblank(cell, "value"));
                    validateRefs(cell.path("sourceRefs"), allowed);
                })));
    }

    private static void validateMindmap(JsonNode mindmap, Set<JsonNode> allowed) {
        JsonNode nodes = mindmap.path("nodes");
        JsonNode edges = mindmap.path("edges");
        require(nodes.isArray() && !nodes.isEmpty() && edges.isArray());
        Set<String> ids = new HashSet<>();
        nodes.forEach(node -> {
            require(nonblank(node, "id") && nonblank(node, "label"));
            validateRefs(node.path("sourceRefs"), allowed);
            ids.add(node.path("id").asText());
        });
        edges.forEach(edge -> require(ids.contains(edge.path("from").asText())
                && ids.contains(edge.path("to").asText())));
    }

    private static void validateQuestions(JsonNode questions, Set<JsonNode> allowed) {
        require(questions.isArray() && !questions.isEmpty());
        questions.forEach(question -> {
            String type = question.path("type").asText();
            require(type.equals("true_false") || type.equals("multiple_choice"));
            JsonNode prompt = question.path("question");
            require(nonblank(prompt, "text"));
            validateRefs(prompt.path("sourceRefs"), allowed);
            JsonNode answer = question.path("answer");
            require(answer.hasNonNull("value"));
            validateRefs(answer.path("sourceRefs"), allowed);
            JsonNode explanation = question.path("explanation");
            require(nonblank(explanation, "text"));
            validateRefs(explanation.path("sourceRefs"), allowed);
            if (type.equals("multiple_choice")) require(prompt.path("options").size() == 4);
        });
    }

    private static void validateRefs(JsonNode refs, Set<JsonNode> allowed) {
        require(refs.isArray() && !refs.isEmpty());
        refs.forEach(reference -> require(allowed.contains(reference)));
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
