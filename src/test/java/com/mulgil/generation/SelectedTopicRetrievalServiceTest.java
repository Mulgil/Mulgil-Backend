package com.mulgil.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedTopicRetrievalServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void selectsDeterministicSourceDiversity_whenNearestChunksShareOneSource() throws Exception {
        UUID firstSource = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondSource = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var candidates = List.of(
                chunk(1, firstSource, 0.01), chunk(2, firstSource, 0.02),
                chunk(3, secondSource, 0.03), chunk(4, secondSource, 0.04));

        List<SelectedTopicRetrievalService.SelectedChunk> selected =
                SelectedTopicRetrievalService.selectDiverse(candidates, 3);

        assertThat(selected).extracting(SelectedTopicRetrievalService.SelectedChunk::chunkId)
                .containsExactly(id(1), id(3), id(2));
    }

    @Test
    void rejectsStoredSourceReferenceMismatch_againstTrustedRelationalColumns() {
        UUID note = id(20);
        UUID block = id(21);
        var source = new SelectedTopicRetrievalService.RelationalSource(
                "text", null, note, null, block, null, 7, null, null, null,
                null, null, null, null, 2);
        String valid = "{\"sourceType\":\"note\",\"noteId\":\"" + note
                + "\",\"contentBlockId\":\"" + block + "\",\"paragraphOffset\":7,\"inputVersion\":2}";
        String forged = "{\"sourceType\":\"note\",\"noteId\":\"" + id(99)
                + "\",\"contentBlockId\":\"" + block + "\",\"paragraphOffset\":7}";

        assertThat(SelectedTopicRetrievalService.sourceReference(JSON, source, valid).path("noteId").asText())
                .isEqualTo(note.toString());
        assertThatThrownBy(() -> SelectedTopicRetrievalService.sourceReference(JSON, source, forged))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stored source reference does not match its relational source.");
    }

    private static SelectedTopicRetrievalService.SelectedChunk chunk(int id, UUID sourceId, double distance)
            throws Exception {
        return new SelectedTopicRetrievalService.SelectedChunk(id(id), "note", sourceId, 1,
                "hash", "text " + id, JSON.readTree("{\"sourceType\":\"note\",\"noteId\":\"" + sourceId
                        + "\",\"contentBlockId\":\"" + id(id) + "\",\"paragraphOffset\":0}"), distance);
    }

    private static UUID id(int value) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(value));
    }
}
