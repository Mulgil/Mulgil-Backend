package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URI;
import java.time.Instant;

@TestConfiguration
class GenerationTestFakes {
    @Bean
    @Primary
    FakeChunkEmbedding embeddings() {
        return new FakeChunkEmbedding();
    }

    @Bean
    @Primary
    FakeGenerationModel generationModel(ObjectMapper json) {
        return new FakeGenerationModel(json);
    }

    @Bean
    @Primary
    FakeCloudStorage cloudStorage() {
        return new FakeCloudStorage();
    }
}

final class FakeCloudStorage implements CloudStoragePort {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    volatile int writes;
    volatile int reads;
    volatile int deletes;
    volatile boolean failDelete;

    @Override
    public URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public URI createDownloadUrl(String objectKey, Instant expiresAt) {
        throw new AssertionError("Private target payload must never have a signed URL.");
    }

    @Override
    public StoredObjectMetadata metadata(String objectKey) {
        byte[] content = objects.get(objectKey);
        return content == null ? null : new StoredObjectMetadata("application/json", content.length, null);
    }

    @Override
    public void putPrivate(String objectKey, byte[] content, String contentType, String checksum) {
        if (objects.putIfAbsent(objectKey, content.clone()) != null) {
            throw new IllegalStateException("Object already exists.");
        }
        writes++;
    }

    @Override
    public byte[] read(String objectKey) {
        reads++;
        byte[] content = objects.get(objectKey);
        return content == null ? null : content.clone();
    }

    @Override
    public void delete(String objectKey) {
        if (failDelete) throw new IllegalStateException("fake delete failure");
        deletes++;
        objects.remove(objectKey);
    }

    void reset() {
        objects.clear();
        writes = 0;
        reads = 0;
        deletes = 0;
        failDelete = false;
    }
}

final class FakeChunkEmbedding implements ChunkEmbeddingPort {
    volatile int calls;

    @Override
    public Embedding embed(String text) {
        calls++;
        return new Embedding(new ArrayList<>(Collections.nCopies(768, 0.1f)), "fake-embedding");
    }
}

final class FakeGenerationModel implements GenerationModelPort {
    private final ObjectMapper json;
    volatile boolean valid = true;
    volatile long lastPromptUnits;
    volatile long lastResultUnits;
    volatile int countTokensCalls;
    volatile int generationCalls;
    volatile long countedTokens = 100;
    volatile long contextTokenLimit = 1_048_576;
    volatile String failureCode;
    volatile String countTokensFailureCode;
    volatile String benchmarkModel;
    volatile int benchmarkCalls;
    volatile String providerPayloadMarker;
    volatile String outputText;
    volatile String outputFieldName;
    volatile JsonNode outputScalar;

    FakeGenerationModel(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        generationCalls++;
        return result(request);
    }

    private GenerationResult result(GenerationRequest request) {
        try {
            if (failureCode != null) {
                throw new GenerationModelException(failureCode, true, null);
            }
            String prompt = request.input().text();
            request.onFirstResponse().run();
            String citationId = request.input().citations().get(0).id();
            JsonNode sourceIds = valid ? json.createArrayNode().add(citationId) : json.createArrayNode();
            var root = json.createObjectNode();
            switch (request.artifact()) {
                case SUMMARY -> {
                    var item = root.putObject("summary").putArray("items").addObject()
                            .put("text", outputText == null ? "Grounded summary" : outputText);
                    item.set("sourceIds", sourceIds.deepCopy());
                    if (outputFieldName != null) item.put(outputFieldName, "provider metadata");
                    if (outputScalar != null) item.set("providerEcho", outputScalar.deepCopy());
                }
                case MINDMAP -> {
                    root.putObject("mindmap").putArray("nodes").addObject().put("id", "n1")
                            .put("label", "Grounded node").set("sourceIds", sourceIds.deepCopy());
                    root.withObject("mindmap").putArray("edges");
                }
                case QUIZ -> {
                    var question = root.putArray("quizQuestions").addObject();
                    question.put("type", "true_false");
                    question.putObject("question").put("text", "Grounded question")
                            .set("sourceIds", sourceIds.deepCopy());
                    question.putObject("answer").put("value", true).set("sourceIds", sourceIds.deepCopy());
                    question.putObject("explanation").put("text", "Grounded explanation")
                            .set("sourceIds", sourceIds.deepCopy());
                }
            }
            if (providerPayloadMarker != null) root.put("providerPayload", providerPayloadMarker);
            String raw = json.writeValueAsString(root);
            lastPromptUnits = prompt.codePoints().count();
            lastResultUnits = raw.codePoints().count();
            return new GenerationResult(raw, new GenerationUsage(31L, 17L, 48L, 5L), 7L, "STOP");
        } catch (GenerationModelException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public TokenCount countTokens(GenerationRequest request) {
        countTokensCalls++;
        if (countTokensFailureCode != null) {
            throw new GenerationModelException(countTokensFailureCode, true, null);
        }
        return new TokenCount(countedTokens, contextTokenLimit);
    }

    @Override
    public GenerationResult benchmark(GenerationRequest request, String modelId) {
        benchmarkModel = modelId;
        benchmarkCalls++;
        return result(request);
    }
}
