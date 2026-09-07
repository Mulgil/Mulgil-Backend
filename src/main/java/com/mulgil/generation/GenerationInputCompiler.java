package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

@Component
public final class GenerationInputCompiler {
    public CompiledInput compile(GenerationSnapshotService.Snapshot snapshot) {
        StringBuilder text = new StringBuilder("@phase ").append(snapshot.phase()).append('\n');
        List<Citation> citations = new ArrayList<>(snapshot.sources().size());
        Group previous = null;
        for (int index = 0; index < snapshot.sources().size(); index++) {
            GenerationSnapshotService.Source source = snapshot.sources().get(index);
            Group group = new Group(source.type(), source.sourceId(), source.inputVersion());
            if (!group.equals(previous)) {
                text.append("@group ").append(group.type()).append('|').append(group.sourceId())
                        .append('|').append(group.inputVersion()).append('\n');
                previous = group;
            }
            String id = GenerationCitations.sourceId(index);
            text.append("@source ").append(id).append(" chars=").append(source.text().codePointCount(0, source.text().length()))
                    .append('\n').append(source.text()).append("\n@end ").append(id).append('\n');
            citations.add(new Citation(id, source.sourceReference()));
        }
        return new CompiledInput(text.toString(), citations);
    }

    CompiledInput compileSelected(String query, String intent,
                                  List<SelectedTopicRetrievalService.SelectedChunk> chunks) {
        if (!Set.of("summary", "mindmap", "quiz").contains(intent)) {
            throw new IllegalArgumentException("Unsupported selected-topic intent.");
        }
        StringBuilder text = new StringBuilder("@selected-v1\nintent ").append(intent).append('\n');
        appendEncodedRecord(text, "query", "-", query);
        List<Citation> citations = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            String id = GenerationCitations.sourceId(index);
            appendEncodedRecord(text, "source", id, chunk.text());
            citations.add(new Citation(id, chunk.sourceReference()));
        }
        return new CompiledInput(text.toString(), citations);
    }

    private static void appendEncodedRecord(StringBuilder output, String kind, String id, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        output.append("record ").append(kind).append(' ').append(id)
                .append(" utf8=").append(bytes.length).append(" base64=").append(encoded.length())
                .append('\n').append(encoded).append('\n');
    }

    public record CompiledInput(String text, List<Citation> citations) {
        public CompiledInput {
            Objects.requireNonNull(text);
            citations = List.copyOf(citations);
        }
    }

    public record Citation(String id, JsonNode sourceReference) {
        public Citation {
            Objects.requireNonNull(id);
            Objects.requireNonNull(sourceReference);
        }
    }

    private record Group(String type, UUID sourceId, int inputVersion) {}
}
