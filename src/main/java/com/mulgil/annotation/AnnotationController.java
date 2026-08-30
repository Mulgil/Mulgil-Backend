package com.mulgil.annotation;

import com.mulgil.common.security.CurrentUser;
import com.mulgil.job.JobQueue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class AnnotationController {
    private final AnnotationService annotations;
    private final HandwritingService handwriting;

    AnnotationController(AnnotationService annotations, HandwritingService handwriting) {
        this.annotations = annotations;
        this.handwriting = handwriting;
    }

    @PutMapping("/materials/{materialId}/annotations")
    AnnotationService.Document save(@PathVariable UUID materialId, @Valid @RequestBody Write request) {
        return annotations.save(CurrentUser.id(), materialId, request);
    }

    @PostMapping("/materials/{materialId}/annotations/leave")
    ResponseEntity<JobQueue.JobAccepted> leave(@PathVariable UUID materialId, @Valid @RequestBody Leave request) {
        JobQueue.JobAccepted accepted = annotations.leave(CurrentUser.id(), materialId, request.changedVersion());
        return accepted == null ? ResponseEntity.noContent().build() : ResponseEntity.accepted().body(accepted);
    }

    @PatchMapping("/handwriting-blocks/{blockId}/confirm")
    ResponseEntity<JobQueue.JobAccepted> confirm(
            @PathVariable UUID blockId,
            @Valid @RequestBody Confirm request
    ) {
        return ResponseEntity.accepted().body(handwriting.confirm(CurrentUser.id(), blockId, request.confirmedText()));
    }

    record Write(@Min(0) int expectedVersion, @NotNull List<@Valid InkStroke> inkStrokes,
                 @NotNull List<@Valid EmphasisRegion> emphasisRegions) {}

    record InkStroke(@NotNull UUID id, @Min(1) int pageNumber, @NotNull Tool tool,
                     @NotBlank String color,
                     @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double widthNorm,
                     @NotNull @Size(min = 1) List<@Valid Point> points, @NotNull @Valid Box bboxNorm) {}

    record EmphasisRegion(@NotNull UUID id, @Min(1) int pageNumber,
                          @NotNull @Valid Box bboxNorm, @Min(0) int tapCount) {}

    record Point(@DecimalMin("0") @DecimalMax("1") double x,
                 @DecimalMin("0") @DecimalMax("1") double y) {}

    record Box(@DecimalMin("0") @DecimalMax("1") double x,
               @DecimalMin("0") @DecimalMax("1") double y,
               @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double width,
               @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double height) {}

    record Leave(@Min(1) int changedVersion) {}
    record Confirm(@NotBlank String confirmedText) {}
    enum Tool { pen, highlight }
}
