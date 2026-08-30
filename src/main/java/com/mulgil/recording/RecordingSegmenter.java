package com.mulgil.recording;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

interface RecordingSegmenter {
    List<AudioSegment> split(String objectKey, Duration duration, Duration maxSegmentDuration);

    void cleanup(List<AudioSegment> segments);

    record AudioSegment(Path path, Duration offset) {}
}
