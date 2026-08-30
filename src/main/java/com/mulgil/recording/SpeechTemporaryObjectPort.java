package com.mulgil.recording;

import java.net.URI;
import java.nio.file.Path;

interface SpeechTemporaryObjectPort {
    URI put(Path segment);

    void delete(URI uri);
}
