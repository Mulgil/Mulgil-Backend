package com.mulgil.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.mulgil.common.config.MulgilProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Profile("!test & !smoke")
final class GcsCloudStorageAdapter implements CloudStoragePort {
    private final Storage storage = StorageOptions.getDefaultInstance().getService();
    private final String bucket;

    GcsCloudStorageAdapter(MulgilProperties properties) {
        bucket = properties.gcs().bucket();
    }

    @Override
    public URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt) {
        long seconds = Math.max(1, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        BlobInfo blob = BlobInfo.newBuilder(bucket, objectKey).setContentType(contentType).build();
        return URI.create(storage.signUrl(blob, seconds, TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.PUT),
                Storage.SignUrlOption.withV4Signature(),
                Storage.SignUrlOption.withContentType(),
                Storage.SignUrlOption.withExtHeaders(Map.of("Content-Length", Long.toString(contentLength))))
                .toString());
    }

    @Override
    public URI createDownloadUrl(String objectKey, Instant expiresAt) {
        long seconds = Math.max(1, expiresAt.getEpochSecond() - Instant.now().getEpochSecond());
        return URI.create(storage.signUrl(BlobInfo.newBuilder(bucket, objectKey).build(), seconds, TimeUnit.SECONDS,
                Storage.SignUrlOption.httpMethod(com.google.cloud.storage.HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature()).toString());
    }

    @Override
    public StoredObjectMetadata metadata(String objectKey) {
        Blob blob = storage.get(bucket, objectKey);
        if (blob == null) return null;
        String checksum = blob.getMetadata() == null ? null : blob.getMetadata().get("sha256");
        return new StoredObjectMetadata(blob.getContentType(), blob.getSize(), checksum);
    }

    @Override
    public byte[] read(String objectKey) {
        Blob blob = storage.get(bucket, objectKey);
        return blob == null ? null : blob.getContent();
    }
}
