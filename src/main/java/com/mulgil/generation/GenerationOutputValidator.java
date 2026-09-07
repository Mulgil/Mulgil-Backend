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
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw invalidGenerationOutput(details("JSON_SYNTAX", "$"));
        }
        try {
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
            throw invalidSourceReferences(exception.details);
        } catch (ValidationException exception) {
            throw invalidGenerationOutput(exception.details);
        } catch (IllegalArgumentException exception) {
            throw invalidGenerationOutput(details("STRUCTURE", "$"));
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
        rejectUnless(questions.isArray(), "QUESTIONS_TYPE", "quizQuestions");
        rejectUnless(!questions.isEmpty(), "QUESTIONS_COUNT", "quizQuestions", 1, questions.size());
        for (int index = 0; index < questions.size(); index++) {
            JsonNode question = questions.get(index);
            String base = "quizQuestions[" + index + "]";
            rejectUnless(question.isObject(), "QUESTION_OBJECT", base);
            String type = question.path("type").asText();
            rejectUnless(type.equals("true_false") || type.equals("multiple_choice"),
                    "QUESTION_TYPE", base + ".type");
            JsonNode prompt = question.path("question");
            rejectUnless(nonblank(prompt, "text"), "TEXT_NONBLANK", base + ".question.text");
            resolveRefs(prompt, citations, Integer.MAX_VALUE, base + ".question");
            if (type.equals("multiple_choice")) {
                JsonNode options = prompt.path("options");
                rejectUnless(options.isArray(), "OPTIONS_COUNT", base + ".question.options", 4, 0);
                rejectUnless(options.size() == 4, "OPTIONS_COUNT", base + ".question.options", 4,
                        options.size());
                for (int option = 0; option < options.size(); option++) {
                    rejectUnless(options.get(option).isTextual() && !options.get(option).asText().isBlank(),
                            "OPTION_NONBLANK", base + ".question.options[" + option + "]");
                }
            }
            JsonNode answer = question.path("answer");
            rejectUnless(answer.isObject(), "ANSWER_REQUIRED", base + ".answer");
            rejectUnless(answer.hasNonNull("value"), "ANSWER_VALUE_REQUIRED", base + ".answer.value");
            JsonNode value = answer.path("value");
            if (type.equals("true_false")) {
                rejectUnless(value.isBoolean(), "ANSWER_TYPE", base + ".answer.value");
            } else {
                rejectUnless(value.isIntegralNumber() && value.canConvertToInt(),
                        "ANSWER_TYPE", base + ".answer.value");
                rejectUnless(value.intValue() >= 0 && value.intValue() <= 3,
                        "ANSWER_RANGE", base + ".answer.value", 3, value.intValue());
            }
            resolveRefs(answer, citations, Integer.MAX_VALUE, base + ".answer");
            JsonNode explanation = question.path("explanation");
            rejectUnless(nonblank(explanation, "text"),
                    "TEXT_NONBLANK", base + ".explanation.text");
            resolveRefs(explanation, citations, Integer.MAX_VALUE, base + ".explanation");
        }
    }

    private static void resolveRefs(JsonNode node, Map<String, JsonNode> citations) {
        resolveRefs(node, citations, Integer.MAX_VALUE);
    }

    private static void resolveRefs(JsonNode node, Map<String, JsonNode> citations, int maxSourceIds) {
        resolveRefs(node, citations, maxSourceIds, "$");
    }

    private static void resolveRefs(JsonNode node, Map<String, JsonNode> citations,
                                    int maxSourceIds, String path) {
        if (!node.isObject()) throw new InvalidSourceReferenceException(
                details("SOURCE_OBJECT", path));
        ObjectNode grounded = (ObjectNode) node;
        JsonNode sourceIds = grounded.path("sourceIds");
        if (!sourceIds.isArray() || sourceIds.isEmpty()) throw new InvalidSourceReferenceException(
                details("SOURCE_IDS_REQUIRED", path + ".sourceIds"));
        require(sourceIds.size() <= maxSourceIds);
        ArrayNode sourceRefs = grounded.putArray("sourceRefs");
        for (int index = 0; index < sourceIds.size(); index++) {
            JsonNode sourceId = sourceIds.get(index);
            String sourcePath = path + ".sourceIds[" + index + "]";
            if (!sourceId.isTextual()) throw new InvalidSourceReferenceException(
                    details("SOURCE_IDS_TEXT", sourcePath));
            JsonNode sourceReference = citations.get(sourceId.asText());
            if (sourceReference == null) throw new InvalidSourceReferenceException(
                    details("SOURCE_ID_UNKNOWN", sourcePath));
            sourceRefs.add(sourceReference.deepCopy());
        }
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
        rejectUnless(condition, "STRUCTURE", "$");
    }

    private static void rejectUnless(boolean condition, String rule, String path) {
        rejectUnless(condition, rule, path, null, null);
    }

    private static void rejectUnless(boolean condition, String rule, String path,
                                     Integer expectedCount, Integer actualCount) {
        if (!condition) throw new ValidationException(details(rule, path, expectedCount, actualCount));
    }

    private static JobHandler.ValidationDetails details(String rule, String path) {
        return details(rule, path, null, null);
    }

    private static JobHandler.ValidationDetails details(String rule, String path,
                                                        Integer expectedCount, Integer actualCount) {
        return new JobHandler.ValidationDetails(rule, path, expectedCount, actualCount);
    }

    private static JobHandler.JobExecutionException invalidSourceReferences(JobHandler.ValidationDetails details) {
        return new JobHandler.JobExecutionException("INVALID_SOURCE_REFERENCES",
                "Generated content did not resolve to the current source snapshot.", false, details);
    }

    private static JobHandler.JobExecutionException invalidGenerationOutput(JobHandler.ValidationDetails details) {
        return new JobHandler.JobExecutionException("INVALID_GENERATION_OUTPUT",
                "Generated content was invalid.", false, details);
    }

    private static final class ValidationException extends IllegalArgumentException {
        private final JobHandler.ValidationDetails details;

        private ValidationException(JobHandler.ValidationDetails details) {
            this.details = details;
        }
    }

    private static final class InvalidSourceReferenceException extends IllegalArgumentException {
        private final JobHandler.ValidationDetails details;

        private InvalidSourceReferenceException(JobHandler.ValidationDetails details) {
            this.details = details;
        }
    }

    record Output(JsonNode summary, JsonNode mindmapNodes, JsonNode mindmapEdges,
                  JsonNode questions, List<JsonNode> sourceReferences) {}
}
