package com.mulgil.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.generation.GenerationModelPort;
import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.notification.FcmPort;
import com.mulgil.ocr.VisionOcrPort;
import com.mulgil.resource.ResourceContentProbe;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class FinalIntegrationFakes {
    @Bean @Primary public FakeGcs gcs() { return new FakeGcs(); }
    @Bean @Primary public FakeProbe probe(FakeGcs gcs) { return new FakeProbe(gcs); }
    @Bean @Primary public FakeVision vision() { return new FakeVision(); }
    @Bean public VertexState vertex(ObjectMapper json) { return new VertexState(json); }
    @Bean @Primary public ChunkEmbeddingPort embeddings(VertexState vertex) { return vertex::embed; }
    @Bean @Primary public GenerationModelPort generation(VertexState vertex) { return vertex::generateJson; }
    @Bean @Primary public FakeFcm fcm() { return new FakeFcm(); }

    public static final class FakeGcs implements CloudStoragePort {
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private volatile String lastKey;

        @Override public URI createUploadUrl(String key, String type, long length, Instant expires) {
            calls.incrementAndGet(); lastKey = key; return URI.create("https://storage.invalid/upload");
        }
        @Override public URI createDownloadUrl(String key, Instant expires) {
            calls.incrementAndGet(); return URI.create("https://storage.invalid/download");
        }
        @Override public StoredObjectMetadata metadata(String key) {
            calls.incrementAndGet(); byte[] value = objects.get(key);
            return value == null ? null : new StoredObjectMetadata(key.endsWith(".m4a") ? "audio/m4a" : "application/pdf",
                    value.length, hash(value));
        }
        @Override public void delete(String key) { calls.incrementAndGet(); objects.remove(key); }
        @Override public byte[] read(String key) { calls.incrementAndGet(); return objects.get(key); }
        public void putLast(byte[] value) { objects.put(lastKey, value); }
        public String lastKey() { return lastKey; }
        public int calls() { return calls.get(); }
        public void reset() { calls.set(0); objects.clear(); lastKey = null; }
        static String hash(byte[] value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
            catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }
    }

    public static final class FakeProbe implements ResourceContentProbe {
        private final FakeGcs gcs;
        private volatile int pages = 1;
        private volatile long duration = 1200;
        FakeProbe(FakeGcs gcs) { this.gcs = gcs; }
        @Override public PdfInspection inspectPdf(String key) {
            byte[] value = gcs.read(key); return new PdfInspection(pages,
                    FakeGcs.hash(value));
        }
        @Override public AudioInspection inspectAudio(String key) {
            byte[] value = gcs.read(key); return new AudioInspection(duration,
                    FakeGcs.hash(value));
        }
        public void duration(long value) { duration = value; }
        public void reset() { pages = 1; duration = 1200; }
    }

    public static final class FakeVision implements VisionOcrPort {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public OcrResult extract(byte[] image) {
            calls.incrementAndGet();
            return new OcrResult(List.of(new OcrBlock("scanned source", 0.55,
                    new NormalizedBox(0.1, 0.1, 0.8, 0.2))), "fake-vision", "vision-v1");
        }
        public int calls() { return calls.get(); }
        public void reset() { calls.set(0); }
    }

    public static final class VertexState {
        private final ObjectMapper json;
        private final AtomicInteger embeddingCalls = new AtomicInteger();
        private final AtomicInteger generationCalls = new AtomicInteger();
        private volatile Mode mode = Mode.VALID;
        VertexState(ObjectMapper json) { this.json = json; }
        ChunkEmbeddingPort.Embedding embed(String text) {
            embeddingCalls.incrementAndGet();
            if (mode == Mode.TIMEOUT) throw new IllegalStateException("provider timeout sentinel-secret");
            return new ChunkEmbeddingPort.Embedding(new ArrayList<>(Collections.nCopies(768, 0.2f)), "fake-embedding");
        }
        String generateJson(String prompt, String schema) {
            generationCalls.incrementAndGet();
            if (mode == Mode.TIMEOUT) throw new IllegalStateException("provider timeout sentinel-secret");
            if (mode == Mode.MALFORMED) return "{malformed sentinel-secret raw-user-content";
            try {
                JsonNode ref = json.readTree(prompt).path("sources").get(0).path("sourceRef");
                JsonNode refs = mode == Mode.INVALID_REF ? json.createArrayNode()
                        : json.createArrayNode().add(ref);
                var root = json.createObjectNode();
                root.putObject("summary").putArray("items").addObject().put("text", "Grounded summary")
                        .set("sourceRefs", refs.deepCopy());
                root.putObject("mindmap").putArray("nodes").addObject().put("id", "n1")
                        .put("label", "Grounded node").set("sourceRefs", refs.deepCopy());
                root.withObject("mindmap").putArray("edges");
                var question = root.putArray("quizQuestions").addObject().put("type", "true_false");
                question.putObject("question").put("text", "Grounded question").set("sourceRefs", refs.deepCopy());
                question.putObject("answer").put("value", true).set("sourceRefs", refs.deepCopy());
                question.putObject("explanation").put("text", "Grounded explanation").set("sourceRefs", refs);
                return json.writeValueAsString(root);
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        public void mode(Mode value) { mode = value; }
        public int embeddingCalls() { return embeddingCalls.get(); }
        public int generationCalls() { return generationCalls.get(); }
        public void reset() { embeddingCalls.set(0); generationCalls.set(0); mode = Mode.VALID; }
        public enum Mode { VALID, TIMEOUT, INVALID_REF, MALFORMED }
    }

    public static final class FakeFcm implements FcmPort {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Message last;
        @Override public String send(String token, Message message) {
            calls.incrementAndGet(); last = message; return "fake-fcm-" + calls.get();
        }
        public int calls() { return calls.get(); }
        public Message last() { return last; }
        public void reset() { calls.set(0); last = null; }
    }
}
