package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.error.ApiException;
import com.mulgil.job.JobQueue;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
final class GenerationService {
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;
    private final GenerationScheduler scheduler;
    private final ObjectMapper json;

    GenerationService(JdbcClient jdbc, GenerationSnapshotService snapshots,
                      GenerationScheduler scheduler, ObjectMapper json) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.scheduler = scheduler;
        this.json = json;
    }

    SessionGeneration summary(UUID ownerId, UUID sessionId, String type) {
        if (!type.equals("preview") && !type.equals("review")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Summary type must be preview or review.");
        }
        Scope scope = sessionScope(ownerId, sessionId);
        StoredSummary summary = jdbc.sql("""
                        SELECT id,input_version,content_json::text FROM summaries
                        WHERE owner_id=:owner AND session_id=:session AND summary_type=:type AND status='succeeded'
                        ORDER BY input_version DESC,updated_at DESC LIMIT 1
                        """).param("owner", ownerId).param("session", sessionId).param("type", type)
                .query((row, ignored) -> new StoredSummary(row.getObject("id", UUID.class),
                        row.getInt("input_version"), parse(row.getString("content_json")))).optional().orElse(null);
        if (summary == null) {
            GenerationSnapshotService.Snapshot snapshot = snapshots.session(
                    ownerId, scope.courseId(), sessionId, type);
            if (!snapshot.ready()) throw insufficient();
            throw new ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND",
                    "Generated session content is not available yet.");
        }
        StoredMindmap mindmap = jdbc.sql("""
                        SELECT id,nodes_json::text,edges_json::text FROM mindmaps
                        WHERE owner_id=:owner AND session_id=:session AND input_version=:version
                          AND status='succeeded'
                        """).param("owner", ownerId).param("session", sessionId)
                .param("version", summary.inputVersion())
                .query((row, ignored) -> new StoredMindmap(row.getObject("id", UUID.class),
                        parse(row.getString("nodes_json")), parse(row.getString("edges_json"))))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND",
                        "Generated session content is incomplete."));
        return new SessionGeneration(new SummaryView(summary.id(), type, summary.inputVersion(),
                summary.content().path("items"), summary.content().path("tables")),
                new MindmapView(mindmap.id(), summary.inputVersion(), mindmap.nodes(), mindmap.edges()));
    }

    ExamGeneration examSummary(UUID ownerId, UUID examId) {
        Scope scope = jdbc.sql("""
                        SELECT course_id FROM exams WHERE owner_id=:owner AND id=:exam
                        """).param("owner", ownerId).param("exam", examId)
                .query((row, ignored) -> new Scope(row.getObject("course_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND", "Exam not found."));
        StoredSummary summary = jdbc.sql("""
                        SELECT s.id,s.input_version,s.content_json::text
                        FROM summaries s
                        JOIN exams e ON e.owner_id=s.owner_id AND e.course_id=s.course_id AND e.id=s.exam_id
                        WHERE s.owner_id=:owner AND s.course_id=:course AND s.exam_id=:exam
                          AND s.summary_type='exam' AND s.status='succeeded'
                        ORDER BY s.input_version DESC,s.updated_at DESC LIMIT 1
                        """).param("owner", ownerId).param("course", scope.courseId()).param("exam", examId)
                .query((row, ignored) -> new StoredSummary(row.getObject("id", UUID.class),
                        row.getInt("input_version"), parse(row.getString("content_json"))))
                .optional().orElse(null);
        if (summary == null) {
            GenerationSnapshotService.Snapshot snapshot = snapshots.exam(ownerId, examId, false);
            if (snapshot == null || !snapshot.ready()) throw insufficient();
            throw new ApiException(HttpStatus.NOT_FOUND, "GENERATION_NOT_FOUND",
                    "Generated exam content is not available yet.");
        }
        return new ExamGeneration(summary.id(), "exam", summary.inputVersion(),
                summary.content().path("items"), summary.content().path("tables"));
    }

    JobQueue.JobAccepted generateExam(UUID ownerId, UUID examId, boolean predicted) {
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM exams WHERE owner_id=:owner AND id=:exam)")
                .param("owner", ownerId).param("exam", examId).query(Boolean.class).single();
        if (!exists) throw new ApiException(HttpStatus.NOT_FOUND, "EXAM_NOT_FOUND", "Exam not found.");
        JobQueue.JobAccepted accepted = scheduler.scheduleExam(ownerId, examId, predicted);
        if (accepted == null) throw insufficient();
        return accepted;
    }

    private Scope sessionScope(UUID ownerId, UUID sessionId) {
        return jdbc.sql("SELECT course_id FROM class_sessions WHERE owner_id=:owner AND id=:session")
                .param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> new Scope(row.getObject("course_id", UUID.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "SESSION_NOT_FOUND", "Session not found."));
    }

    private JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored generation JSON is invalid.", exception);
        }
    }

    private static ApiException insufficient() {
        return new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_SOURCE_DATA",
                "All required current sources must be indexed before generation.");
    }

    record SessionGeneration(SummaryView summary, MindmapView mindmap) {}
    record SummaryView(UUID id, String type, int inputVersion, JsonNode items, JsonNode tables) {}
    @Schema(description = "Generated exam summary artifact")
    record ExamGeneration(UUID id,
                          @Schema(allowableValues = "exam") String type,
                          int inputVersion,
                          JsonNode items,
                          JsonNode tables) {}
    record MindmapView(UUID id, int inputVersion, JsonNode nodes, JsonNode edges) {}
    private record Scope(UUID courseId) {}
    private record StoredSummary(UUID id, int inputVersion, JsonNode content) {}
    private record StoredMindmap(UUID id, JsonNode nodes, JsonNode edges) {}
}
