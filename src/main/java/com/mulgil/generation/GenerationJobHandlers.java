package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

abstract class GenerationJobHandler implements JobHandler {
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;
    private final GenerationOutputValidator validator;
    private final ObjectProvider<GenerationModelPort> models;
    private final MulgilProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    GenerationJobHandler(JdbcClient jdbc, GenerationSnapshotService snapshots, GenerationOutputValidator validator,
                         ObjectProvider<GenerationModelPort> models, MulgilProperties properties,
                         ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.validator = validator;
        this.models = models;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        GenerationSnapshotService.Snapshot snapshot = load(job);
        if (snapshot == null || !snapshot.ready() || !snapshot.snapshotHash().equals(job.sourceHash())) {
            throw new JobExecutionException("STALE_INPUT", "Generation input is no longer current.", false);
        }
        GenerationModelPort model = models.getIfAvailable();
        if (model == null) throw new JobExecutionException(
                "PROVIDER_UNAVAILABLE", "Generation provider unavailable.", true);
        String raw;
        try {
            raw = model.generateJson(prompt(snapshot), schema());
        } catch (RuntimeException exception) {
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Generation provider failed.", true);
        }
        boolean session = job.examId() == null;
        boolean quiz = job.type().equals("exam_quiz_generate");
        GenerationOutputValidator.Output output = validator.parse(raw, snapshot, session, quiz);
        return () -> publish(job, output);
    }

    private GenerationSnapshotService.Snapshot load(JobQueue.ClaimedJob job) {
        return switch (job.type()) {
            case "preview_generate" -> snapshots.session(job.ownerId(), job.courseId(), job.sessionId(), "preview");
            case "review_generate" -> snapshots.session(job.ownerId(), job.courseId(), job.sessionId(), "review");
            case "exam_summary_generate" -> snapshots.exam(job.ownerId(), job.examId(), false);
            case "exam_quiz_generate" -> snapshots.exam(job.ownerId(), job.examId(), true);
            default -> throw new IllegalArgumentException("Unsupported generation job type.");
        };
    }

    private String prompt(GenerationSnapshotService.Snapshot snapshot) {
        ObjectNode root = json.createObjectNode().put("phase", snapshot.phase());
        ArrayNode values = root.putArray("sources");
        snapshot.sources().forEach(source -> values.addObject().put("text", source.text())
                .set("sourceRef", source.sourceReference()));
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String schema() {
        return "source-grounded-generation-v1";
    }

    private void publish(JobQueue.ClaimedJob job, GenerationOutputValidator.Output output) {
        GenerationSnapshotService.Snapshot current = load(job);
        if (current == null || !current.ready() || !current.snapshotHash().equals(job.sourceHash())) {
            throw new IllegalStateException("Generation input changed before publication.");
        }
        Timestamp now = Timestamp.from(clock.instant());
        String model = properties.vertex().generationModel();
        String refs = json(output.sourceReferences());
        if (job.type().equals("exam_quiz_generate")) {
            replaceQuestions(job, output, "past_exam_based", model, now);
            return;
        }
        String summaryType = job.examId() == null
                ? job.type().substring(0, job.type().indexOf('_')) : "exam";
        jdbc.sql("""
                UPDATE summaries SET status='outdated',updated_at=:now
                WHERE owner_id=:owner AND status='succeeded' AND summary_type=:type
                  AND ((CAST(:exam AS uuid) IS NULL AND session_id=:session) OR exam_id=CAST(:exam AS uuid))
                """).param("now", now).param("owner", job.ownerId()).param("type", summaryType)
                .param("exam", job.examId()).param("session", job.sessionId()).update();
        jdbc.sql("""
                INSERT INTO summaries
                    (id,owner_id,course_id,session_id,exam_id,summary_type,input_version,content_json,
                     source_refs,status,model_id,prompt_version,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,:exam,:type,:version,CAST(:content AS jsonb),
                        CAST(:refs AS jsonb),'succeeded',:model,:prompt,:now,:now)
                """).param("id", UUID.randomUUID()).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.examId() == null ? job.sessionId() : null).param("exam", job.examId())
                .param("type", summaryType).param("version", job.inputVersion())
                .param("content", json(output.summary())).param("refs", refs).param("model", model)
                .param("prompt", GenerationScheduler.PROMPT_VERSION).param("now", now).update();
        if (job.examId() == null) {
            replaceMindmap(job, output, model, refs, now);
            replaceQuestions(job, output, "practice", model, now);
        }
    }

    private void replaceMindmap(JobQueue.ClaimedJob job, GenerationOutputValidator.Output output,
                                String model, String refs, Timestamp now) {
        jdbc.sql("UPDATE mindmaps SET status='outdated',updated_at=:now WHERE owner_id=:owner "
                        + "AND session_id=:session AND status='succeeded'")
                .param("now", now).param("owner", job.ownerId()).param("session", job.sessionId()).update();
        jdbc.sql("""
                INSERT INTO mindmaps
                    (id,owner_id,course_id,session_id,input_version,nodes_json,edges_json,source_refs,
                     status,model_id,prompt_version,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,:version,CAST(:nodes AS jsonb),CAST(:edges AS jsonb),
                        CAST(:refs AS jsonb),'succeeded',:model,:prompt,:now,:now)
                """).param("id", UUID.randomUUID()).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId()).param("version", job.inputVersion())
                .param("nodes", json(output.mindmapNodes())).param("edges", json(output.mindmapEdges()))
                .param("refs", refs).param("model", model).param("prompt", GenerationScheduler.PROMPT_VERSION)
                .param("now", now).update();
    }

    private void replaceQuestions(JobQueue.ClaimedJob job, GenerationOutputValidator.Output output,
                                  String scope, String model, Timestamp now) {
        jdbc.sql("""
                UPDATE quiz_questions SET status='outdated' WHERE owner_id=:owner AND status='succeeded'
                  AND ((CAST(:exam AS uuid) IS NULL AND session_id=:session AND quiz_scope='practice')
                       OR exam_id=CAST(:exam AS uuid))
                """).param("owner", job.ownerId()).param("exam", job.examId())
                .param("session", job.sessionId()).update();
        int index = 0;
        for (var question : output.questions()) {
            jdbc.sql("""
                    INSERT INTO quiz_questions
                        (id,owner_id,course_id,session_id,exam_id,quiz_scope,question_type,input_version,
                         question_json,answer_json,explanation_json,status,model_id,prompt_version,created_at)
                    VALUES (:id,:owner,:course,:session,:exam,:scope,:type,:version,CAST(:question AS jsonb),
                            CAST(:answer AS jsonb),CAST(:explanation AS jsonb),'succeeded',:model,:prompt,:now)
                    """).param("id", UUID.nameUUIDFromBytes(
                            (job.id() + ":" + index++).getBytes(StandardCharsets.UTF_8)))
                    .param("owner", job.ownerId()).param("course", job.courseId())
                    .param("session", job.examId() == null ? job.sessionId() : null).param("exam", job.examId())
                    .param("scope", scope).param("type", question.path("type").asText())
                    .param("version", job.inputVersion()).param("question", json(question.path("question")))
                    .param("answer", json(question.path("answer")))
                    .param("explanation", json(question.path("explanation"))).param("model", model)
                    .param("prompt", GenerationScheduler.PROMPT_VERSION).param("now", now).update();
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

@Component
final class PreviewGenerationJobHandler extends GenerationJobHandler {
    PreviewGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationOutputValidator v,
                                ObjectProvider<GenerationModelPort> m, MulgilProperties p, ObjectMapper o, Clock c) {
        super(j, s, v, m, p, o, c);
    }
    public String jobType() { return "preview_generate"; }
}

@Component
final class ReviewGenerationJobHandler extends GenerationJobHandler {
    ReviewGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationOutputValidator v,
                               ObjectProvider<GenerationModelPort> m, MulgilProperties p, ObjectMapper o, Clock c) {
        super(j, s, v, m, p, o, c);
    }
    public String jobType() { return "review_generate"; }
}

@Component
final class ExamSummaryGenerationJobHandler extends GenerationJobHandler {
    ExamSummaryGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationOutputValidator v,
                                    ObjectProvider<GenerationModelPort> m, MulgilProperties p, ObjectMapper o, Clock c) {
        super(j, s, v, m, p, o, c);
    }
    public String jobType() { return "exam_summary_generate"; }
}

@Component
final class ExamQuizGenerationJobHandler extends GenerationJobHandler {
    ExamQuizGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationOutputValidator v,
                                 ObjectProvider<GenerationModelPort> m, MulgilProperties p, ObjectMapper o, Clock c) {
        super(j, s, v, m, p, o, c);
    }
    public String jobType() { return "exam_quiz_generate"; }
}
