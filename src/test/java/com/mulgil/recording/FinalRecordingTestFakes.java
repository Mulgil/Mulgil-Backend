package com.mulgil.recording;

import com.mulgil.indexing.ChunkEmbeddingPort;
import com.mulgil.stt.SpeechToTextPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@TestConfiguration
public class FinalRecordingTestFakes {
    @Bean @Primary public Counters recordingCounters() { return new Counters(); }
    @Bean @Primary RecordingSegmenter segmenter(Counters counters) { return new Segmenter(counters); }
    @Bean @Primary SpeechTemporaryObjectPort temporary(Counters counters) { return new Temporary(counters); }
    @Bean @Primary SpeechToTextPort speech(Counters counters) { return new Speech(counters); }

    public static final class Counters {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger polls = new AtomicInteger();
        private final AtomicInteger uploads = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        public int starts() { return starts.get(); }
        public int polls() { return polls.get(); }
        public int uploads() { return uploads.get(); }
        public int deletes() { return deletes.get(); }
        public void reset() { starts.set(0); polls.set(0); uploads.set(0); deletes.set(0); }
    }

    private record Segmenter(Counters counters) implements RecordingSegmenter {
        @Override public List<AudioSegment> split(String key, Duration duration, Duration maximum) {
            return List.of(new AudioSegment(Path.of("segment.m4a"), Duration.ZERO));
        }
        @Override public void cleanup(List<AudioSegment> segments) {}
    }
    private record Temporary(Counters counters) implements SpeechTemporaryObjectPort {
        @Override public URI put(UUID job, int index, Path segment) {
            counters.uploads.incrementAndGet(); return URI.create("gs://fake/stt/" + job + "/" + index);
        }
        @Override public void delete(URI uri) { counters.deletes.incrementAndGet(); }
    }
    private record Speech(Counters counters) implements SpeechToTextPort {
        @Override public String start(Input input) {
            return "operations/fake-" + counters.starts.incrementAndGet();
        }
        @Override public Optional<Transcript> await(String operation, Input input, Duration timeout) {
            counters.polls.incrementAndGet();
            return Optional.of(new Transcript(List.of(new Segment(100, 500, "lecture transcript", 0.9)),
                    "fake-chirp", "chirp-v1"));
        }
    }
}
