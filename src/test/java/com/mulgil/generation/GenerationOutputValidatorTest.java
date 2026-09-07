package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulgil.job.JobHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationOutputValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GenerationOutputValidator validator = new GenerationOutputValidator(json);
    private final GenerationInputCompiler compiler = new GenerationInputCompiler();

    @Test
    void restoresFullSourceReference_whenOutputUsesKnownCitationId() throws Exception {
        JsonNode sourceReference = json.readTree("""
                {"sourceType":"pdf","materialId":"a5b4b8dd-2745-4cf3-90d5-a9d5c7a43c88",
                 "contentBlockId":"07281917-5eef-4058-9c1c-4e49fc2d5ca2","pageNumber":3,
                 "bboxNorm":{"x":0.1,"y":0.2,"width":0.3,"height":0.4},"inputVersion":1}
                """);
        GenerationSnapshotService.Snapshot snapshot = snapshot(sourceReference);
        String raw = """
                {"summary":{"items":[{"text":"Grounded summary","sourceIds":["s1"]}]}}
                """;

        GenerationOutputValidator.Output output = validator.parse(
                raw, compiler.compile(snapshot), GenerationModelPort.Artifact.SUMMARY);

        JsonNode item = output.summary().path("items").get(0);
        assertThat(item.has("sourceIds")).isFalse();
        assertThat(item.path("sourceRefs").get(0)).isEqualTo(sourceReference);
        assertThat(output.sourceReferences()).containsExactly(sourceReference);
    }

    @Test
    void rejectsUnknownCitationId_whenOutputDoesNotResolveToSnapshot() throws Exception {
        String raw = """
                {"summary":{"items":[{"text":"Ungrounded summary","sourceIds":["s99"]}]}}
                """;

        assertThatThrownBy(() -> validator.parse(raw,
                compiler.compile(snapshot(json.readTree("{\"sourceType\":\"pdf\"}"))),
                GenerationModelPort.Artifact.SUMMARY))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_SOURCE_REFERENCES");
                    assertThat(exception.getMessage())
                            .isEqualTo("Generated content did not resolve to the current source snapshot.");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    @Test
    void rejectsMissingCitationSourceIds_asInvalidSourceReferences() throws Exception {
        assertInvalidSourceReferences("""
                {"summary":{"items":[{"text":"Ungrounded summary"}]}}
                """);
    }

    @Test
    void rejectsNonArrayCitationSourceIds_asInvalidSourceReferences() throws Exception {
        assertInvalidSourceReferences("""
                {"summary":{"items":[{"text":"Ungrounded summary","sourceIds":"s1"}]}}
                """);
    }

    @Test
    void rejectsEmptyCitationSourceIds_asInvalidSourceReferences() throws Exception {
        assertInvalidSourceReferences("""
                {"summary":{"items":[{"text":"Ungrounded summary","sourceIds":[]}]}}
                """);
    }

    @Test
    void acceptsMindmapBoundsAndRehydratesEverySourceId_whenOutputIsAtLimits() throws Exception {
        JsonNode sourceReference = json.readTree("{\"sourceType\":\"pdf\"}");
        ObjectNode raw = mindmapAtLimits();

        GenerationOutputValidator.Output output = validator.parse(raw.toString(),
                compiler.compile(snapshot(sourceReference)), GenerationModelPort.Artifact.MINDMAP);

        assertThat(output.mindmapNodes()).hasSize(GenerationOutputValidator.MAX_MINDMAP_NODES);
        assertThat(output.mindmapEdges()).hasSize(GenerationOutputValidator.MAX_MINDMAP_EDGES);
        assertThat(output.mindmapNodes().get(0).path("sourceRefs"))
                .containsExactly(sourceReference, sourceReference, sourceReference);
    }

    @Test
    void rejectsMindmapNodeCount_whenOutputExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        addNode((ArrayNode) raw.path("mindmap").path("nodes"), "overflow", "label");

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMindmapEdgeCount_whenOutputExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        addEdge((ArrayNode) raw.path("mindmap").path("edges"), "n1", "n2");

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMindmapLabel_whenCodePointLengthExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        ((ObjectNode) raw.path("mindmap").path("nodes").get(0))
                .put("label", "🌱".repeat(GenerationOutputValidator.MAX_MINDMAP_LABEL_CODE_POINTS + 1));

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMindmapNodeId_whenCodePointLengthExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        ((ArrayNode) raw.path("mindmap").path("edges")).removeAll();
        ((ObjectNode) raw.path("mindmap").path("nodes").get(0))
                .put("id", "🔧".repeat(GenerationOutputValidator.MAX_MINDMAP_ID_CODE_POINTS + 1));

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMindmapEdgeEndpoint_whenCodePointLengthExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        ((ObjectNode) raw.path("mindmap").path("edges").get(0))
                .put("from", "🔧".repeat(GenerationOutputValidator.MAX_MINDMAP_ID_CODE_POINTS + 1));

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMindmapSourceIds_whenOutputExceedsLimit() throws Exception {
        ObjectNode raw = mindmapAtLimits();
        ((ArrayNode) raw.path("mindmap").path("nodes").get(0).path("sourceIds")).add("s1");

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void rejectsMalformedJson_asInvalidGenerationOutput() throws Exception {
        assertInvalidGenerationOutput("{");
    }

    @Test
    void rejectsWrongMindmapTypes_asInvalidGenerationOutput() throws Exception {
        ObjectNode raw = json.createObjectNode();
        raw.putObject("mindmap").put("nodes", "not-an-array").putArray("edges");

        assertInvalidGenerationOutput(raw);
    }

    @Test
    void acceptsTypedTrueFalseAndMultipleChoiceQuizAnswers() throws Exception {
        ObjectNode root = json.createObjectNode();
        ArrayNode questions = root.putArray("quizQuestions");
        addQuizQuestion(questions, "true_false", true, null);
        addQuizQuestion(questions, "multiple_choice", 2, List.of("A", "B", "C", "D"));

        GenerationOutputValidator.Output output = validator.parse(root.toString(), compiledInput(),
                GenerationModelPort.Artifact.QUIZ);

        assertThat(output.questions()).hasSize(2);
        assertThat(output.questions().get(0).path("answer").path("value").isBoolean()).isTrue();
        assertThat(output.questions().get(1).path("answer").path("value").intValue()).isEqualTo(2);
    }

    @Test
    void rejectsMalformedQuizShapes_withSafeFixedDiagnostics() throws Exception {
        assertQuizRejected("{\"quizQuestions\":[]}", "QUESTIONS_COUNT", "quizQuestions", 1, 0);
        assertQuizRejected(quiz("multiple_choice", 1, null),
                "OPTIONS_COUNT", "quizQuestions[0].question.options", 4, 0);
        assertQuizRejected(quiz("multiple_choice", 1, List.of("A", "B", "C")),
                "OPTIONS_COUNT", "quizQuestions[0].question.options", 4, 3);
        assertQuizRejected(quiz("multiple_choice", 1, List.of("A", "B", "C", "D", "E")),
                "OPTIONS_COUNT", "quizQuestions[0].question.options", 4, 5);
        assertQuizRejected(quiz("multiple_choice", 1, List.of("A", " ", "C", "D")),
                "OPTION_NONBLANK", "quizQuestions[0].question.options[1]", null, null);
        assertQuizRejected(quizWithQuestionText(" "),
                "TEXT_NONBLANK", "quizQuestions[0].question.text", null, null);
        assertQuizRejected(quizWithoutAnswer(),
                "ANSWER_REQUIRED", "quizQuestions[0].answer", null, null);
        assertQuizRejected(quizWithoutAnswerValue(),
                "ANSWER_VALUE_REQUIRED", "quizQuestions[0].answer.value", null, null);
        assertQuizRejected(quiz("true_false", "true", null),
                "ANSWER_TYPE", "quizQuestions[0].answer.value", null, null);
        assertQuizRejected(quiz("multiple_choice", true, List.of("A", "B", "C", "D")),
                "ANSWER_TYPE", "quizQuestions[0].answer.value", null, null);
        assertQuizRejected(quiz("multiple_choice", 4, List.of("A", "B", "C", "D")),
                "ANSWER_RANGE", "quizQuestions[0].answer.value", 3, 4);
    }

    @Test
    void keepsSourceReferenceFailuresDistinctAndSanitizesMalformedJson() throws Exception {
        assertThatThrownBy(() -> validator.parse(quizWithSourceIds(json.createArrayNode().add(7)),
                compiledInput(), GenerationModelPort.Artifact.QUIZ))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_SOURCE_REFERENCES");
                    assertThat(exception.validationDetails().rule()).isEqualTo("SOURCE_IDS_TEXT");
                    assertThat(exception.validationDetails().path())
                            .isEqualTo("quizQuestions[0].question.sourceIds[0]");
                });

        assertThatThrownBy(() -> validator.parse(quizWithSourceIds(json.createArrayNode().add("stale")),
                compiledInput(), GenerationModelPort.Artifact.QUIZ))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_SOURCE_REFERENCES");
                    assertThat(exception.validationDetails().rule()).isEqualTo("SOURCE_ID_UNKNOWN");
                    assertThat(exception.validationDetails().path())
                            .isEqualTo("quizQuestions[0].question.sourceIds[0]");
                });

        String privateFragment = "private-answer-sentinel";
        assertThatThrownBy(() -> validator.parse("{\"" + privateFragment + "\":",
                compiledInput(), GenerationModelPort.Artifact.QUIZ))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_GENERATION_OUTPUT");
                    assertThat(exception.getMessage()).isEqualTo("Generated content was invalid.");
                    assertThat(exception.getMessage()).doesNotContain(privateFragment);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.validationDetails().rule()).isEqualTo("JSON_SYNTAX");
                    assertThat(exception.validationDetails().path()).isEqualTo("$");
                });
    }

    private void assertInvalidGenerationOutput(JsonNode raw) throws Exception {
        assertInvalidGenerationOutput(raw.toString());
    }

    private void assertInvalidGenerationOutput(String raw) throws Exception {
        assertThatThrownBy(() -> validator.parse(raw, compiler.compile(snapshot(
                json.createObjectNode().put("sourceType", "pdf"))), GenerationModelPort.Artifact.MINDMAP))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_GENERATION_OUTPUT");
                    assertThat(exception.getMessage()).isEqualTo("Generated content was invalid.");
                    assertThat(exception.retryable()).isFalse();
                });
    }

    private void assertInvalidSourceReferences(String raw) throws Exception {
        assertThatThrownBy(() -> validator.parse(raw, compiler.compile(snapshot(
                json.createObjectNode().put("sourceType", "pdf"))), GenerationModelPort.Artifact.SUMMARY))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> assertThat(((JobHandler.JobExecutionException) failure).code())
                        .isEqualTo("INVALID_SOURCE_REFERENCES"));
    }

    private void assertQuizRejected(String raw, String rule, String path,
                                    Integer expectedCount, Integer actualCount) throws Exception {
        assertThatThrownBy(() -> validator.parse(raw, compiledInput(), GenerationModelPort.Artifact.QUIZ))
                .isInstanceOf(JobHandler.JobExecutionException.class)
                .satisfies(failure -> {
                    JobHandler.JobExecutionException exception = (JobHandler.JobExecutionException) failure;
                    assertThat(exception.code()).isEqualTo("INVALID_GENERATION_OUTPUT");
                    assertThat(exception.getMessage()).isEqualTo("Generated content was invalid.");
                    assertThat(exception.validationDetails().rule()).isEqualTo(rule);
                    assertThat(exception.validationDetails().path()).isEqualTo(path);
                    assertThat(exception.validationDetails().expectedCount()).isEqualTo(expectedCount);
                    assertThat(exception.validationDetails().actualCount()).isEqualTo(actualCount);
                });
    }

    private String quiz(String type, Object answer, List<String> options) throws Exception {
        ObjectNode root = json.createObjectNode();
        addQuizQuestion(root.putArray("quizQuestions"), type, answer, options);
        return json.writeValueAsString(root);
    }

    private String quizWithQuestionText(String text) throws Exception {
        ObjectNode root = json.createObjectNode();
        addQuizQuestion(root.putArray("quizQuestions"), "true_false", true, null)
                .withObject("question").put("text", text);
        return json.writeValueAsString(root);
    }

    private String quizWithoutAnswer() throws Exception {
        ObjectNode root = json.createObjectNode();
        addQuizQuestion(root.putArray("quizQuestions"), "true_false", true, null).remove("answer");
        return json.writeValueAsString(root);
    }

    private String quizWithoutAnswerValue() throws Exception {
        ObjectNode root = json.createObjectNode();
        addQuizQuestion(root.putArray("quizQuestions"), "true_false", true, null)
                .withObject("answer").remove("value");
        return json.writeValueAsString(root);
    }

    private String quizWithSourceIds(JsonNode sourceIds) throws Exception {
        ObjectNode root = json.createObjectNode();
        addQuizQuestion(root.putArray("quizQuestions"), "true_false", true, null)
                .withObject("question").set("sourceIds", sourceIds);
        return json.writeValueAsString(root);
    }

    private ObjectNode addQuizQuestion(ArrayNode questions, String type, Object answer, List<String> options) {
        ObjectNode item = questions.addObject().put("type", type);
        ObjectNode question = item.putObject("question").put("text", "질문");
        question.putArray("sourceIds").add("s1");
        if (options != null) question.set("options", json.valueToTree(options));
        item.putObject("answer").set("value", json.valueToTree(answer));
        item.withObject("answer").putArray("sourceIds").add("s1");
        item.putObject("explanation").put("text", "설명").putArray("sourceIds").add("s1");
        return item;
    }

    private GenerationInputCompiler.CompiledInput compiledInput() {
        return compiler.compile(snapshot(json.createObjectNode().put("sourceType", "pdf")));
    }

    private ObjectNode mindmapAtLimits() {
        ObjectNode root = json.createObjectNode();
        ObjectNode mindmap = root.putObject("mindmap");
        ArrayNode nodes = mindmap.putArray("nodes");
        String longestId = "🔧".repeat(GenerationOutputValidator.MAX_MINDMAP_ID_CODE_POINTS);
        addNode(nodes, longestId, "🌱".repeat(GenerationOutputValidator.MAX_MINDMAP_LABEL_CODE_POINTS));
        for (int index = 1; index < GenerationOutputValidator.MAX_MINDMAP_NODES; index++) {
            addNode(nodes, "n" + index, index == 1 ? "라벨 API v3" : "라벨 " + index);
        }
        ArrayNode edges = mindmap.putArray("edges");
        for (int index = 0; index < GenerationOutputValidator.MAX_MINDMAP_EDGES; index++) {
            addEdge(edges, longestId, "n1");
        }
        return root;
    }

    private static void addNode(ArrayNode nodes, String id, String label) {
        ObjectNode node = nodes.addObject().put("id", id).put("label", label);
        node.putArray("sourceIds").add("s1").add("s1").add("s1");
    }

    private static void addEdge(ArrayNode edges, String from, String to) {
        edges.addObject().put("from", from).put("to", to);
    }

    private static GenerationSnapshotService.Snapshot snapshot(JsonNode sourceReference) {
        UUID owner = UUID.fromString("7a919b95-cc62-4dc8-b593-d5f3fe3c2ab8");
        UUID course = UUID.fromString("37470e18-0d75-4cc0-9a81-06f653507349");
        UUID session = UUID.fromString("ea370654-29e2-4a35-9f15-062374a1fb26");
        GenerationSnapshotService.Source source = new GenerationSnapshotService.Source("pdf",
                UUID.fromString("a5b4b8dd-2745-4cf3-90d5-a9d5c7a43c88"), 1, "source-hash", "source text",
                sourceReference, true);
        return new GenerationSnapshotService.Snapshot("exam_summary", owner, course, session, null,
                List.of(source), "source", "snapshot-hash", true, GenerationSnapshotService.Readiness.READY);
    }
}
