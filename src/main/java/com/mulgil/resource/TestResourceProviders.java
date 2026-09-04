package com.mulgil.resource;

import com.mulgil.storage.CloudStoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;

@Component
@Profile({"test", "smoke"})
final class TestCloudStorageAdapter implements CloudStoragePort {
    @Override
    public URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt) {
        return URI.create("https://storage.invalid/upload/" + resourceId(objectKey));
    }

    @Override
    public URI createDownloadUrl(String objectKey, Instant expiresAt) {
        return URI.create("https://storage.invalid/download/" + resourceId(objectKey));
    }

    @Override
    public StoredObjectMetadata metadata(String objectKey) {
        return null;
    }

    @Override
    public void delete(String objectKey) {}

    @Override
    public byte[] read(String objectKey) {
        return null;
    }

    private static String resourceId(String objectKey) {
        String[] parts = objectKey.split("/");
        return parts[parts.length - 2];
    }
}

@Component
@Profile({"test", "smoke"})
final class TestResourceContentProbe implements ResourceContentProbe {
    @Override
    public PdfInspection inspectPdf(String objectKey) {
        throw new IllegalStateException("Test PDF inspection was not configured.");
    }

    @Override
    public AudioInspection inspectAudio(String objectKey) {
        throw new IllegalStateException("Test audio inspection was not configured.");
    }
}
