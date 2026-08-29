package com.mulgil.resource;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.job.JobQueue;
import com.mulgil.storage.CloudStoragePort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class ResourceUploadService {
    private static final String PDF_MIME = "application/pdf";

    private final ResourceRepository repository;
    private final ResourceFinalizationService finalization;
    private final CloudStoragePort storage;
    private final MulgilProperties properties;
    private final Clock clock;
    private final JobQueue jobs;

    ResourceUploadService(ResourceRepository repository, ResourceFinalizationService finalization,
                          CloudStoragePort storage, MulgilProperties properties, Clock clock, JobQueue jobs) {
        this.repository = repository;
        this.finalization = finalization;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
        this.jobs = jobs;
    }

    @Transactional
    UploadUrl issueMaterialUpload(UUID ownerId, UUID sessionId, MaterialUpload request) {
        validatePdf(request.mimeType(), request.byteSize());
        ResourceRepository.SessionScope session = repository.lockSession(ownerId, sessionId)
                .orElseThrow(ResourceUploadService::notFound);
        if (repository.materialCount(ownerId, sessionId) >= properties.uploads().maxPdfsPerSession()) {
            throw limit("files");
        }
        UUID id = UUID.randomUUID();
        String key = "owner/%s/session/%s/material/%s/source.pdf".formatted(ownerId, sessionId, id);
        ResourceRepository.Material material = repository.createMaterial(ownerId, session, id,
                new ResourceRepository.MaterialWrite(request.filename(), request.mimeType(), request.byteSize(),
                        request.sourcePhase(), key), clock.instant());
        return uploadUrl(material.id(), material.objectKey(), material.mimeType(), material.byteSize());
    }

    @Transactional
    JobQueue.JobAccepted finalizeMaterial(UUID ownerId, UUID materialId, String checksum) {
        ResourceRepository.Material material = repository.material(ownerId, materialId)
                .orElseThrow(ResourceUploadService::notFound);
        ResourceContentProbe.PdfInspection inspection = finalization.finalizePdf(
                descriptor(material.objectKey(), material.mimeType(), material.byteSize()), checksum);
        validatePages(inspection.pageCount());
        repository.finalizeMaterial(ownerId, materialId, inspection.pageCount(), checksum.toLowerCase(), clock.instant());
        return jobs.enqueuePdfMaterial(ownerId, materialId);
    }

    List<Material> materials(UUID ownerId, UUID sessionId) {
        if (!repository.ownsSession(ownerId, sessionId)) throw notFound();
        return repository.materials(ownerId, sessionId).stream().map(ResourceUploadService::material).toList();
    }

    DownloadUrl materialDownload(UUID ownerId, UUID materialId) {
        ResourceRepository.Material material = repository.material(ownerId, materialId)
                .filter(value -> value.status().equals("uploaded"))
                .orElseThrow(ResourceUploadService::notFound);
        Instant expiry = expiresAt();
        return new DownloadUrl(storage.createDownloadUrl(material.objectKey(), expiry), expiry);
    }

    UploadUrl issueExamResourceUpload(UUID ownerId, UUID examId, PdfUpload request) {
        validatePdf(request.mimeType(), request.byteSize());
        UUID id = UUID.randomUUID();
        String key = "owner/%s/exam/%s/resource/%s/source.pdf".formatted(ownerId, examId, id);
        ResourceRepository.ExamResource resource = repository.createExamResource(ownerId, id,
                new ResourceRepository.ExamResourceWrite(examId, request.filename(), request.mimeType(),
                        request.byteSize(), key), clock.instant());
        if (resource == null) throw notFound();
        return uploadUrl(resource.id(), resource.objectKey(), resource.mimeType(), resource.byteSize());
    }

    @Transactional
    ExamResource finalizeExamResource(UUID ownerId, UUID resourceId, String checksum) {
        ResourceRepository.ExamResource resource = repository.examResource(ownerId, resourceId)
                .orElseThrow(ResourceUploadService::notFound);
        ResourceContentProbe.PdfInspection inspection = finalization.finalizePdf(
                descriptor(resource.objectKey(), resource.mimeType(), resource.byteSize()), checksum);
        validatePages(inspection.pageCount());
        ExamResource completed = examResource(repository.finalizeExamResource(ownerId, resourceId,
                inspection.pageCount(), checksum.toLowerCase(), clock.instant()));
        jobs.enqueuePdfExamResource(ownerId, resourceId);
        return completed;
    }

    UploadUrl issueRecordingUpload(UUID ownerId, RecordingUpload request) {
        if (!properties.uploads().recordingMimeTypes().contains(request.mimeType())) throw unsupported();
        UUID id = UUID.randomUUID();
        String key = "owner/%s/recording/%s/source.m4a".formatted(ownerId, id);
        ResourceRepository.Recording recording = repository.createRecording(ownerId, id,
                new ResourceRepository.RecordingWrite(request.filename(), request.mimeType(), request.byteSize(),
                        request.startedAt(), key), clock.instant());
        return uploadUrl(recording.id(), recording.objectKey(), recording.mimeType(), recording.byteSize());
    }

    RecordingUploadComplete finalizeRecording(UUID ownerId, UUID recordingId, String checksum) {
        ResourceRepository.Recording recording = repository.recording(ownerId, recordingId)
                .orElseThrow(ResourceUploadService::notFound);
        ResourceContentProbe.AudioInspection inspection = finalization.finalizeAudio(
                descriptor(recording.objectKey(), recording.mimeType(), recording.byteSize()), checksum);
        if (inspection.durationSeconds() > properties.uploads().maxAudioDurationSeconds()) {
            throw limit("durationSeconds");
        }
        ResourceRepository.Recording uploaded = repository.finalizeRecording(ownerId, recordingId,
                inspection.durationSeconds(), checksum.toLowerCase(), clock.instant());
        return new RecordingUploadComplete(uploaded.id(), inspection.durationSeconds(),
                candidates(ownerId, uploaded.startedAt(), inspection.durationSeconds()));
    }

    private List<CandidateSession> candidates(UUID ownerId, Instant startedAt, long durationSeconds) {
        Instant endedAt = startedAt.plusSeconds(durationSeconds);
        long durationMillis = durationSeconds * 1000;
        return repository.overlappingSessions(ownerId, startedAt, endedAt).stream()
                .map(session -> {
                    Instant overlapStart = startedAt.isAfter(session.startsAt()) ? startedAt : session.startsAt();
                    Instant overlapEnd = endedAt.isBefore(session.endsAt()) ? endedAt : session.endsAt();
                    double score = (double) Duration.between(overlapStart, overlapEnd).toMillis() / durationMillis;
                    return new CandidateSession(session.id(), session.title(), score);
                })
                .sorted(Comparator.comparingDouble(CandidateSession::overlapScore).reversed())
                .toList();
    }

    private UploadUrl uploadUrl(UUID id, String objectKey, String mimeType, long byteSize) {
        Instant expiry = expiresAt();
        URI url = storage.createUploadUrl(objectKey, mimeType, byteSize, expiry);
        return new UploadUrl(id, url, expiry,
                Map.of("Content-Type", mimeType, "Content-Length", Long.toString(byteSize)));
    }

    private Instant expiresAt() {
        return clock.instant().plusSeconds(properties.gcs().signedUrlTtlSeconds());
    }

    private void validatePdf(String mimeType, long byteSize) {
        if (!PDF_MIME.equals(mimeType)) throw unsupported();
        if (byteSize > properties.uploads().maxPdfBytes()) throw limit("byteSize");
    }

    private void validatePages(int pages) {
        if (pages < 1) throw validation("pageCount");
        if (pages > properties.uploads().maxPdfPages()) throw limit("pageCount");
    }

    private static ResourceFinalizationService.ResourceDescriptor descriptor(
            String objectKey, String mimeType, long byteSize
    ) {
        return new ResourceFinalizationService.ResourceDescriptor(objectKey, mimeType, byteSize);
    }

    private static Material material(ResourceRepository.Material value) {
        return new Material(value.id(), value.sessionId(), value.filename(), value.mimeType(), value.byteSize(),
                value.pageCount(), value.sourcePhase(), value.version(), value.status());
    }

    private static ExamResource examResource(ResourceRepository.ExamResource value) {
        return new ExamResource(value.id(), value.examId(), value.resourceType(), value.filename(), value.mimeType(),
                value.byteSize(), value.pageCount(), value.status());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found.");
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type.");
    }

    private static ApiException limit(String field) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UPLOAD_LIMIT_EXCEEDED",
                "Upload limit exceeded.", Map.of("field", field));
    }

    private static ApiException validation(String field) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "Request validation failed.", Map.of("field", field));
    }

    record MaterialUpload(String filename, String mimeType, long byteSize, String sourcePhase) {}
    record PdfUpload(String filename, String mimeType, long byteSize) {}
    record RecordingUpload(String filename, String mimeType, long byteSize, Instant startedAt) {}
    record UploadUrl(UUID id, URI uploadUrl, Instant expiresAt, Map<String, String> requiredHeaders) {}
    record DownloadUrl(URI downloadUrl, Instant expiresAt) {}
    record Material(UUID id, UUID sessionId, String filename, String mimeType, long byteSize, Integer pageCount,
                    String sourcePhase, int version, String status) {}
    record ExamResource(UUID id, UUID examId, String resourceType, String filename, String mimeType,
                        long byteSize, Integer pageCount, String status) {}
    record RecordingUploadComplete(UUID recordingId, long durationSeconds,
                                   List<CandidateSession> candidateSessions) {}
    record CandidateSession(UUID sessionId, String title, double overlapScore) {}
}
