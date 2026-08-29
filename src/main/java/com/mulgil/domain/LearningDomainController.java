package com.mulgil.domain;

import com.mulgil.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
final class LearningDomainController {
    private final LearningDomainService service;

    LearningDomainController(LearningDomainService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    List<LearningDomainRepository.Course> listCourses() {
        return service.listCourses(CurrentUser.id());
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    LearningDomainRepository.Course createCourse(@Valid @RequestBody CourseCreateRequest request) {
        return service.createCourse(CurrentUser.id(), request.toInput());
    }

    @GetMapping("/timetable/slots")
    List<LearningDomainRepository.TimetableSlot> listSlots(@RequestParam(required = false) UUID courseId) {
        return service.listSlots(CurrentUser.id(), courseId);
    }

    @PostMapping("/timetable/slots")
    @ResponseStatus(HttpStatus.CREATED)
    LearningDomainRepository.TimetableSlot createSlot(@Valid @RequestBody TimetableSlotWriteRequest request) {
        return service.createSlot(CurrentUser.id(), request.toInput());
    }

    @PatchMapping("/timetable/slots/{slotId}")
    LearningDomainRepository.TimetableSlot updateSlot(
            @PathVariable UUID slotId,
            @Valid @RequestBody TimetableSlotWriteRequest request
    ) {
        return service.updateSlot(CurrentUser.id(), slotId, request.toInput());
    }

    @DeleteMapping("/timetable/slots/{slotId}")
    ResponseEntity<Void> deleteSlot(@PathVariable UUID slotId) {
        service.deleteSlot(CurrentUser.id(), slotId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/courses/{courseId}/sessions")
    List<LearningDomainRepository.ClassSession> listSessions(@PathVariable UUID courseId) {
        return service.listSessions(CurrentUser.id(), courseId);
    }

    @PostMapping("/courses/{courseId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    LearningDomainRepository.ClassSession createSession(
            @PathVariable UUID courseId,
            @Valid @RequestBody ClassSessionCreateRequest request
    ) {
        return service.createSession(CurrentUser.id(), courseId, request.toInput());
    }

    @GetMapping("/sessions/{sessionId}")
    LearningDomainRepository.ClassSession getSession(@PathVariable UUID sessionId) {
        return service.getSession(CurrentUser.id(), sessionId);
    }

    @GetMapping("/courses/{courseId}/exams")
    List<LearningDomainRepository.Exam> listExams(@PathVariable UUID courseId) {
        return service.listExams(CurrentUser.id(), courseId);
    }

    @PostMapping("/courses/{courseId}/exams")
    @ResponseStatus(HttpStatus.CREATED)
    LearningDomainRepository.Exam createExam(
            @PathVariable UUID courseId,
            @Valid @RequestBody ExamCreateRequest request
    ) {
        return service.createExam(CurrentUser.id(), courseId, request.toInput());
    }

    record CourseCreateRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String instructor,
            @Size(max = 50) String term
    ) {
        LearningDomainService.CourseInput toInput() {
            return new LearningDomainService.CourseInput(name, instructor, term);
        }
    }

    record TimetableSlotWriteRequest(
            @NotNull UUID courseId,
            @Min(1) @Max(7) int weekday,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotBlank String timezone
    ) {
        LearningDomainService.SlotInput toInput() {
            return new LearningDomainService.SlotInput(courseId, weekday, startTime, endTime, timezone);
        }
    }

    record ClassSessionCreateRequest(
            @Min(1) int sessionNumber,
            @NotBlank @Size(max = 200) String title,
            @NotNull LocalDate sessionDate,
            Instant startsAt,
            Instant endsAt
    ) {
        LearningDomainService.SessionInput toInput() {
            return new LearningDomainService.SessionInput(sessionNumber, title, sessionDate, startsAt, endsAt);
        }
    }

    record ExamCreateRequest(
            @NotBlank @Size(max = 200) String title,
            @NotNull Instant examAt,
            @NotEmpty List<@NotNull UUID> sessionIds
    ) {
        LearningDomainService.ExamInput toInput() {
            return new LearningDomainService.ExamInput(title, examAt, sessionIds);
        }
    }
}
