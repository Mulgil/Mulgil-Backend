package com.mulgil.resource;

import com.mulgil.common.error.ApiException;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
class ResourceFinalizationService {
    private final CloudStoragePort storage;
    private final ResourceContentProbe probe;

    ResourceFinalizationService(CloudStoragePort storage, ResourceContentProbe probe) {
        this.storage = storage;
        this.probe = probe;
    }

    ResourceContentProbe.PdfInspection finalizePdf(ResourceDescriptor resource, String checksum) {
        validateMetadata(resource, checksum);
        ResourceContentProbe.PdfInspection inspection = probe.inspectPdf(resource.objectKey());
        validateChecksum(checksum, inspection.checksumSha256());
        return inspection;
    }

    ResourceContentProbe.AudioInspection finalizeAudio(ResourceDescriptor resource, String checksum) {
        validateMetadata(resource, checksum);
        ResourceContentProbe.AudioInspection inspection = probe.inspectAudio(resource.objectKey());
        validateChecksum(checksum, inspection.checksumSha256());
        return inspection;
    }

    private void validateMetadata(ResourceDescriptor resource, String checksum) {
        CloudStoragePort.StoredObjectMetadata metadata = storage.metadata(resource.objectKey());
        if (metadata == null) throw validation("upload");
        if (!resource.mimeType().equals(metadata.contentType())) throw unsupported();
        if (resource.byteSize() != metadata.contentLength()) throw validation("byteSize");
        if (metadata.checksum() != null && !metadata.checksum().equalsIgnoreCase(checksum)) {
            throw validation("checksumSha256");
        }
    }

    private static void validateChecksum(String expected, String actual) {
        if (actual == null || !expected.equalsIgnoreCase(actual)) throw validation("checksumSha256");
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type.");
    }

    private static ApiException validation(String field) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "Uploaded content validation failed.", Map.of("field", field));
    }

    record ResourceDescriptor(String objectKey, String mimeType, long byteSize) {}
}
