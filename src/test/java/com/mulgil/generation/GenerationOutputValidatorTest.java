package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                .extracting("code").isEqualTo("INVALID_SOURCE_REFERENCES");
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
