package com.mulgil.stt;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public interface SpeechToTextPort {
    Transcript transcribe(URI objectUri, Duration offset);

    record Transcript(List<Segment> segments, String provider, String model) {
        public Transcript {
            segments = List.copyOf(segments);
        }
    }

    record Segment(long startMs, long endMs, String text, Double confidence) {}
}
