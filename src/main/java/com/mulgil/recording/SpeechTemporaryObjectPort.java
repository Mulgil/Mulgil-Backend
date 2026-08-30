package com.mulgil.recording;

import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;

interface SpeechTemporaryObjectPort {
    URI put(UUID jobId, int segmentIndex, Path segment);

    void delete(URI uri);
}
