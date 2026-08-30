package com.mulgil.stt;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface SpeechToTextPort {
    String start(List<Input> inputs);

    Optional<Transcript> await(String operationId, List<Input> inputs, Duration pollTimeout);

    record Input(URI objectUri, Duration offset) {}

    record Transcript(List<Segment> segments, String provider, String model) {
        public Transcript {
            segments = List.copyOf(segments);
        }
    }

    record Segment(long startMs, long endMs, String text, Double confidence) {}

    final class TerminalOperationException extends RuntimeException {
        public TerminalOperationException(String message) {
            super(message);
        }
    }
}
