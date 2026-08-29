package com.mulgil.storage;

import java.net.URI;
import java.time.Instant;

public interface CloudStoragePort {
    URI createUploadUrl(String objectKey, String contentType, long contentLength, Instant expiresAt);

    URI createDownloadUrl(String objectKey, Instant expiresAt);

    StoredObjectMetadata metadata(String objectKey);

    record StoredObjectMetadata(String contentType, long contentLength, String checksum) {}
}
