package com.mulgil.domain;

import com.mulgil.common.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
class LearningDomainService {
    private final LearningDomainRepository repository;
    private final Clock clock;

    LearningDomainService(LearningDomainRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    LearningDomainRepository.Course createCourse(UUID ownerId, CourseInput request) {
        return repository.createCourse(ownerId, UUID.randomUUID(), request.name(), request.instructor(),
                request.term(), clock.instant());
    }

    List<LearningDomainRepository.Course> listCourses(UUID ownerId) {
        return repository.listCourses(ownerId);
    }

    LearningDomainRepository.Course updateCourse(UUID ownerId, UUID courseId, CourseInput request) {
        LearningDomainRepository.Course course = repository.updateCourse(
                ownerId, courseId, request.name(), request.instructor(), request.term(), clock.instant());
        if (course == null) throw notFound();
        return course;
    }

    void deleteCourse(UUID ownerId, UUID courseId) {
        if (repository.softDeleteCourse(ownerId, courseId, clock.instant()) == 0) throw notFound();
    }

    LearningDomainRepository.TimetableSlot createSlot(UUID ownerId, SlotInput request) {
        validateSlot(request);
        LearningDomainRepository.TimetableSlot slot = repository.createSlot(
                ownerId, UUID.randomUUID(), request.toWrite(), clock.instant());
        if (slot == null) throw notFound();
        return slot;
    }

    List<LearningDomainRepository.TimetableSlot> listSlots(UUID ownerId, UUID courseId) {
        return repository.listSlots(ownerId, courseId);
    }

    LearningDomainRepository.TimetableSlot updateSlot(UUID ownerId, UUID slotId, SlotInput request) {
        validateSlot(request);
        LearningDomainRepository.TimetableSlot slot = repository.updateSlot(
                ownerId, slotId, request.toWrite(), clock.instant());
        if (slot == null) throw notFound();
        return slot;
    }

    void deleteSlot(UUID ownerId, UUID slotId) {
        if (repository.deleteSlot(ownerId, slotId) == 0) throw notFound();
    }

    LearningDomainRepository.ClassSession createSession(UUID ownerId, UUID courseId, SessionInput request) {
        validateSessionTimes(request);
        try {
            LearningDomainRepository.ClassSession session = repository.createSession(
                    ownerId, courseId, UUID.randomUUID(), request.toWrite(), clock.instant());
            if (session == null) throw notFound();
            return session;
        } catch (DataIntegrityViolationException exception) {
            throw validation("sessionNumber");
        }
    }

    List<LearningDomainRepository.ClassSession> listSessions(UUID ownerId, UUID courseId) {
        requireCourse(ownerId, courseId);
        return repository.listSessions(ownerId, courseId);
    }

    LearningDomainRepository.ClassSession getSession(UUID ownerId, UUID sessionId) {
        return repository.getSession(ownerId, sessionId).orElseThrow(LearningDomainService::notFound);
    }

    @Transactional
    LearningDomainRepository.Exam createExam(UUID ownerId, UUID courseId, ExamInput request) {
        requireCourse(ownerId, courseId);
        if (new HashSet<>(request.sessionIds()).size() != request.sessionIds().size()) {
            throw validation("sessionIds");
        }
        if (repository.countSessionsInScope(ownerId, courseId, request.sessionIds()) != request.sessionIds().size()) {
            throw validation("sessionIds");
        }
        return repository.createExam(ownerId, courseId, UUID.randomUUID(), request.toWrite(), clock.instant());
    }

    List<LearningDomainRepository.Exam> listExams(UUID ownerId, UUID courseId) {
        requireCourse(ownerId, courseId);
        return repository.listExams(ownerId, courseId);
    }

    private void requireCourse(UUID ownerId, UUID courseId) {
        if (!repository.ownsCourse(ownerId, courseId)) throw notFound();
    }

    private static void validateSlot(SlotInput request) {
        if (!request.startTime().isBefore(request.endTime())) throw validation("startTime");
        try {
            ZoneId.of(request.timezone());
        } catch (DateTimeException exception) {
            throw validation("timezone");
        }
    }

    private static void validateSessionTimes(SessionInput request) {
        if (request.startsAt() != null && request.endsAt() != null
                && !request.startsAt().isBefore(request.endsAt())) {
            throw validation("startsAt");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found.");
    }

    private static ApiException validation(String field) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "Request validation failed.", java.util.Map.of("field", field));
    }

    record CourseInput(String name, String instructor, String term) {}
    record SlotInput(UUID courseId, int weekday, java.time.LocalTime startTime,
                     java.time.LocalTime endTime, String timezone) {
        LearningDomainRepository.SlotWrite toWrite() {
            return new LearningDomainRepository.SlotWrite(courseId, weekday, startTime, endTime, timezone);
        }
    }
    record SessionInput(int sessionNumber, String title, java.time.LocalDate sessionDate,
                        java.time.Instant startsAt, java.time.Instant endsAt) {
        LearningDomainRepository.SessionWrite toWrite() {
            return new LearningDomainRepository.SessionWrite(sessionNumber, title, sessionDate, startsAt, endsAt);
        }
    }
    record ExamInput(String title, java.time.Instant examAt, List<UUID> sessionIds) {
        LearningDomainRepository.ExamWrite toWrite() {
            return new LearningDomainRepository.ExamWrite(title, examAt, List.copyOf(sessionIds));
        }
    }
}
