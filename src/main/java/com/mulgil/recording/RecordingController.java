package com.mulgil.recording;

import com.mulgil.common.security.CurrentUser;
import com.mulgil.job.JobQueue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/recordings")
final class RecordingController {
    private final RecordingMappingService mappings;

    RecordingController(RecordingMappingService mappings) {
        this.mappings = mappings;
    }

    @PostMapping("/{recordingId}/confirm-mapping")
    ResponseEntity<JobQueue.JobAccepted> confirm(
            @PathVariable UUID recordingId,
            @Valid @RequestBody MappingRequest request
    ) {
        return ResponseEntity.accepted().body(mappings.confirm(CurrentUser.id(), recordingId, request.sessionId()));
    }

    record MappingRequest(@NotNull UUID sessionId) {}
}
