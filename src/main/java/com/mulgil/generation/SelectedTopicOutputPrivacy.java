package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulgil.job.JobHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class SelectedTopicOutputPrivacy {
    private SelectedTopicOutputPrivacy() {}

    static void rejectEcho(GenerationOutputValidator.Output output, String query,
                           List<SelectedTopicRetrievalService.SelectedChunk> chunks)
            throws JobHandler.JobExecutionException {
        List<String> sensitive = new ArrayList<>(chunks.size() + 1);
        sensitive.add(query);
        chunks.stream().map(SelectedTopicRetrievalService.SelectedChunk::text)
                .filter(text -> !text.isBlank()).forEach(sensitive::add);
        if (contains(output.summary(), sensitive)
                || contains(output.mindmapNodes(), sensitive)
                || contains(output.mindmapEdges(), sensitive)
                || contains(output.questions(), sensitive)) {
            throw new JobHandler.JobExecutionException("SENSITIVE_GENERATION_OUTPUT",
                    "Generated content contained private request input.", false);
        }
    }

    private static boolean contains(JsonNode node, List<String> sensitive) {
        if (node == null || node.isMissingNode() || node.isNull()) return false;
        if (node.isTextual()) return sensitive.stream().anyMatch(node.textValue()::contains);
        Iterator<JsonNode> children = node.elements();
        while (children.hasNext()) {
            if (contains(children.next(), sensitive)) return true;
        }
        return false;
    }
}
