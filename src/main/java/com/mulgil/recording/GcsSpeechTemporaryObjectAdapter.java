package com.mulgil.recording;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
@Profile("!test & !smoke")
final class GcsSpeechTemporaryObjectAdapter implements SpeechTemporaryObjectPort {
    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final String bucket;

    GcsSpeechTemporaryObjectAdapter(MulgilProperties properties) {
        this.bucket = properties.gcs().bucket();
    }

    @Override
    public URI put(UUID jobId, int segmentIndex, Path segment) {
        String key = "temporary/stt/" + jobId + "/" + segmentIndex + ".m4a";
        try {
            if (storage.get(bucket, key) == null) {
                storage.create(BlobInfo.newBuilder(bucket, key).setContentType("audio/mp4").build(),
                        Files.readAllBytes(segment));
            }
            return URI.create("gs://" + bucket + "/" + key);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not upload temporary speech object.", exception);
        }
    }

    @Override
    public void delete(URI uri) {
        String key = uri.getPath().substring(1);
        storage.delete(BlobId.of(uri.getHost(), key));
    }
}
