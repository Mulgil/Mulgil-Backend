package com.mulgil.note;

import com.mulgil.common.security.CurrentUser;
import com.mulgil.job.JobQueue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class NoteController {
    private final NoteService service;

    NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping("/sessions/{sessionId}/notes")
    List<NoteService.Note> list(@PathVariable UUID sessionId) {
        return service.list(CurrentUser.id(), sessionId);
    }

    @PostMapping("/sessions/{sessionId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    NoteService.Note create(@PathVariable UUID sessionId, @Valid @RequestBody NoteCreate request) {
        return service.create(CurrentUser.id(), sessionId, request.bodyMarkdown() == null ? "" : request.bodyMarkdown());
    }

    @GetMapping("/notes/{noteId}")
    NoteService.Note get(@PathVariable UUID noteId) {
        return service.get(CurrentUser.id(), noteId);
    }

    @PatchMapping("/notes/{noteId}")
    NoteService.Note patch(@PathVariable UUID noteId, @Valid @RequestBody NotePatch request) {
        return service.patch(CurrentUser.id(), noteId, request.bodyMarkdown(), request.expectedVersion());
    }

    @PostMapping("/notes/{noteId}/leave")
    ResponseEntity<JobQueue.JobAccepted> leave(@PathVariable UUID noteId, @Valid @RequestBody Leave request) {
        JobQueue.JobAccepted accepted = service.leave(CurrentUser.id(), noteId, request.changedVersion());
        return accepted == null ? ResponseEntity.noContent().build() : ResponseEntity.accepted().body(accepted);
    }

    record NoteCreate(String bodyMarkdown) {}
    record NotePatch(@NotNull String bodyMarkdown, @Min(1) int expectedVersion) {}
    record Leave(@Min(1) int changedVersion) {}
}
