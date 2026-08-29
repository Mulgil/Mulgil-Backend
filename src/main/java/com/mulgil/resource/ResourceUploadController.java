package com.mulgil.resource;

import com.mulgil.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class ResourceUploadController {
    private final ResourceUploadService service;

    ResourceUploadController(ResourceUploadService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{sessionId}/materials/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceUploadService.UploadUrl materialUploadUrl(
            @PathVariable UUID sessionId,
            @Valid @RequestBody MaterialUploadRequest request
    ) {
        return service.issueMaterialUpload(CurrentUser.id(), sessionId, request.toInput());
    }

    @PostMapping("/materials/{materialId}/upload-complete")
    ResponseEntity<Void> materialUploadComplete(
            @PathVariable UUID materialId,
            @Valid @RequestBody UploadCompleteRequest request
    ) {
        service.finalizeMaterial(CurrentUser.id(), materialId, request.checksumSha256());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions/{sessionId}/materials")
    List<ResourceUploadService.Material> materials(@PathVariable UUID sessionId) {
        return service.materials(CurrentUser.id(), sessionId);
    }

    @GetMapping("/materials/{materialId}/download-url")
    ResourceUploadService.DownloadUrl materialDownloadUrl(@PathVariable UUID materialId) {
        return service.materialDownload(CurrentUser.id(), materialId);
    }

    @PostMapping("/exams/{examId}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceUploadService.UploadUrl examResourceUploadUrl(
            @PathVariable UUID examId,
            @Valid @RequestBody ExamResourceUploadRequest request
    ) {
        return service.issueExamResourceUpload(CurrentUser.id(), examId, request.toInput());
    }

    @PostMapping("/exam-resources/{examResourceId}/upload-complete")
    ResourceUploadService.ExamResource examResourceUploadComplete(
            @PathVariable UUID examResourceId,
            @Valid @RequestBody UploadCompleteRequest request
    ) {
        return service.finalizeExamResource(CurrentUser.id(), examResourceId, request.checksumSha256());
    }

    @PostMapping("/recordings/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    ResourceUploadService.UploadUrl recordingUploadUrl(@Valid @RequestBody RecordingUploadRequest request) {
        return service.issueRecordingUpload(CurrentUser.id(), request.toInput());
    }

    @PostMapping("/recordings/{recordingId}/upload-complete")
    ResourceUploadService.RecordingUploadComplete recordingUploadComplete(
            @PathVariable UUID recordingId,
            @Valid @RequestBody UploadCompleteRequest request
    ) {
        return service.finalizeRecording(CurrentUser.id(), recordingId, request.checksumSha256());
    }

    record MaterialUploadRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank String mimeType,
            @Min(1) long byteSize,
            @NotNull SourcePhase sourcePhase
    ) {
        ResourceUploadService.MaterialUpload toInput() {
            return new ResourceUploadService.MaterialUpload(filename, mimeType, byteSize, sourcePhase.name());
        }
    }

    record ExamResourceUploadRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank String mimeType,
            @Min(1) long byteSize
    ) {
        ResourceUploadService.PdfUpload toInput() {
            return new ResourceUploadService.PdfUpload(filename, mimeType, byteSize);
        }
    }

    record RecordingUploadRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank String mimeType,
            @Min(1) long byteSize,
            @NotNull Instant startedAt
    ) {
        ResourceUploadService.RecordingUpload toInput() {
            return new ResourceUploadService.RecordingUpload(filename, mimeType, byteSize, startedAt);
        }
    }

    record UploadCompleteRequest(
            @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}") String checksumSha256
    ) {}

    enum SourcePhase {
        preview_pdf,
        review_pdf
    }
}
