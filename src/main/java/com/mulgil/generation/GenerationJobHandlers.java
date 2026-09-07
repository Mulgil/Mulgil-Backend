package com.mulgil.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.job.AiProviderUsageLedger;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

abstract class GenerationJobHandler implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(GenerationJobHandler.class);
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;
    private final GenerationInputCompiler compiler;
    private final GenerationOutputValidator validator;
    private final ObjectProvider<GenerationModelPort> models;
    private final MulgilProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final AiProviderUsageLedger usage;
    private final JobQueue queue;
    private final MeterRegistry metrics;

    GenerationJobHandler(JdbcClient jdbc, GenerationSnapshotService snapshots, GenerationInputCompiler compiler,
                         GenerationOutputValidator validator,
                         ObjectProvider<GenerationModelPort> models, MulgilProperties properties,
                         AiProviderUsageLedger usage, JobQueue queue, MeterRegistry metrics,
                         ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.compiler = compiler;
        this.validator = validator;
        this.models = models;
        this.properties = properties;
        this.usage = usage;
        this.queue = queue;
        this.metrics = metrics;
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
        GenerationModelPort.GenerationResult result;
        GenerationInputCompiler.CompiledInput input = compiler.compile(snapshot);
        GenerationModelPort.GenerationRequest request = new GenerationModelPort.GenerationRequest(
                input, schema(), artifact(job), () -> queue.updateProgress(job, "generating"),
                job.ownerId(), snapshot.snapshotHash());
        try {
            if (input.text().codePoints().count() > properties.generation().inputSoftTokenLimit()) {
                GenerationModelPort.TokenCount tokens = usage.observeGenerationTokenCount(job,
                        properties.vertex().generationModel(), () -> model.countTokens(request));
                if (tokens.inputTokens() > tokens.contextTokenLimit()) {
                    throw new JobExecutionException("GENERATION_INPUT_TOO_LARGE",
                            "Generation input exceeds the supported context.", false);
                }
            }
            result = usage.observeGeneration(job, properties.vertex().generationModel(),
                    input.text().codePoints().count(), () -> model.generate(request));
        } catch (GenerationModelPort.GenerationModelException exception) {
            logProviderFailure(job, artifact(job), exception.code(),
                    exception.result() == null ? null : exception.result().finishReason());
            throw new JobExecutionException(exception.code(), "Generation provider failed.", exception.retryable());
        } catch (RuntimeException exception) {
            logProviderFailure(job, artifact(job), "PROVIDER_UNAVAILABLE", null);
            throw new JobExecutionException("PROVIDER_UNAVAILABLE", "Generation provider failed.", true);
        }
        queue.updateProgress(job, "validating");
        GenerationOutputValidator.Output output;
        try {
            output = validator.parse(result.rawJson(), input, artifact(job));
        } catch (JobExecutionException exception) {
            metrics.counter("mulgil.generation.validation.failures",
                    "model", properties.vertex().generationModel(), "artifact", artifact(job).metricValue(),
                    "result", "rejected", "cache", Boolean.toString(cacheHit(result))).increment();
            logOutputRejected(job, artifact(job), exception);
            throw exception;
        }
        queue.updateProgress(job, "publishing");
        return () -> publish(job, output);
    }

    private static GenerationModelPort.Artifact artifact(JobQueue.ClaimedJob job) {
        return switch (job.type()) {
            case "preview_generate", "review_generate", "exam_summary_generate" ->
                    GenerationModelPort.Artifact.SUMMARY;
            case "preview_mindmap_generate", "review_mindmap_generate" ->
                    GenerationModelPort.Artifact.MINDMAP;
            case "preview_quiz_generate", "review_quiz_generate", "exam_quiz_generate" ->
                    GenerationModelPort.Artifact.QUIZ;
            default -> throw new IllegalArgumentException("Unsupported generation job type.");
        };
    }

    private static boolean cacheHit(GenerationModelPort.GenerationResult result) {
        return result.usage() != null && result.usage().cachedContentTokenCount() != null
                && result.usage().cachedContentTokenCount() > 0;
    }

    private static void logProviderFailure(JobQueue.ClaimedJob job, GenerationModelPort.Artifact artifact,
                                           String errorCode, String finishReason) {
        log.atWarn().addKeyValue("event", "generation.provider.failed")
                .addKeyValue("jobId", job.id()).addKeyValue("operation", job.type())
                .addKeyValue("artifact", artifact.metricValue()).addKeyValue("errorCode", errorCode)
                .addKeyValue("finishReason", finishReason).log("generation provider failure handled");
    }

    static void logOutputRejected(JobQueue.ClaimedJob job, GenerationModelPort.Artifact artifact,
                                  JobExecutionException exception) {
        var event = log.atWarn().addKeyValue("event", "generation.output.rejected")
                .addKeyValue("jobId", job.id()).addKeyValue("operation", job.type())
                .addKeyValue("artifact", artifact.metricValue()).addKeyValue("errorCode", exception.code());
        JobHandler.ValidationDetails details = exception.validationDetails();
        if (details != null) {
            event.addKeyValue("rule", details.rule()).addKeyValue("path", details.path());
            if (details.expectedCount() != null) event.addKeyValue("expectedCount", details.expectedCount());
            if (details.actualCount() != null) event.addKeyValue("actualCount", details.actualCount());
        }
        event.log("generation output rejected");
    }

    private GenerationSnapshotService.Snapshot load(JobQueue.ClaimedJob job) {
        return switch (job.type()) {
            case "preview_generate", "preview_mindmap_generate", "preview_quiz_generate" ->
                    snapshots.session(job.ownerId(), job.courseId(), job.sessionId(), "preview");
            case "review_generate", "review_mindmap_generate", "review_quiz_generate" ->
                    snapshots.session(job.ownerId(), job.courseId(), job.sessionId(), "review");
            case "exam_summary_generate" -> snapshots.exam(job.ownerId(), job.examId(), false);
            case "exam_quiz_generate" -> snapshots.exam(job.ownerId(), job.examId(), true);
            default -> throw new IllegalArgumentException("Unsupported generation job type.");
        };
    }

    private String schema() {
        return GenerationScheduler.PROMPT_VERSION;
    }

    private void publish(JobQueue.ClaimedJob job, GenerationOutputValidator.Output output) {
        GenerationSnapshotService.Snapshot current = load(job);
        if (current == null || !current.ready() || !current.snapshotHash().equals(job.sourceHash())) {
            throw new IllegalStateException("Generation input changed before publication.");
        }
        Timestamp now = Timestamp.from(clock.instant());
        String model = properties.vertex().generationModel();
        String refs = json(output.sourceReferences());
        GenerationModelPort.Artifact artifact = artifact(job);
        if (artifact == GenerationModelPort.Artifact.QUIZ) {
            replaceQuestions(job, output, job.examId() == null ? "practice" : "past_exam_based", model, now);
            return;
        }
        if (artifact == GenerationModelPort.Artifact.MINDMAP) {
            replaceMindmap(job, output, model, refs, now);
            return;
        }
        String summaryType = job.examId() == null ? phase(job.type()) : "exam";
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
    }

    private static String phase(String type) {
        return type.startsWith("preview_") ? "preview" : "review";
    }

    private void replaceMindmap(JobQueue.ClaimedJob job, GenerationOutputValidator.Output output,
                                String model, String refs, Timestamp now) {
        jdbc.sql("""
                UPDATE mindmaps AS mindmap SET status='outdated',updated_at=:now
                FROM summaries AS summary
                WHERE mindmap.owner_id=:owner AND mindmap.session_id=:session AND mindmap.status='succeeded'
                  AND summary.owner_id=mindmap.owner_id AND summary.session_id=mindmap.session_id
                  AND summary.input_version=mindmap.input_version AND summary.summary_type=:type
                """).param("now", now).param("owner", job.ownerId()).param("session", job.sessionId())
                .param("type", phase(job.type())).update();
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
    PreviewGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                GenerationOutputValidator v,
                                ObjectProvider<GenerationModelPort> m, MulgilProperties p,
                                AiProviderUsageLedger u, JobQueue q, MeterRegistry metrics,
                                ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "preview_generate"; }
}

@Component
final class ReviewGenerationJobHandler extends GenerationJobHandler {
    ReviewGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                               GenerationOutputValidator v,
                               ObjectProvider<GenerationModelPort> m, MulgilProperties p,
                               AiProviderUsageLedger u, JobQueue q, MeterRegistry metrics,
                               ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "review_generate"; }
}

@Component
final class PreviewMindmapGenerationJobHandler extends GenerationJobHandler {
    PreviewMindmapGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                       GenerationOutputValidator v, ObjectProvider<GenerationModelPort> m,
                                       MulgilProperties p, AiProviderUsageLedger u, JobQueue q,
                                       MeterRegistry metrics, ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "preview_mindmap_generate"; }
}

@Component
final class ReviewMindmapGenerationJobHandler extends GenerationJobHandler {
    ReviewMindmapGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                      GenerationOutputValidator v, ObjectProvider<GenerationModelPort> m,
                                      MulgilProperties p, AiProviderUsageLedger u, JobQueue q,
                                      MeterRegistry metrics, ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "review_mindmap_generate"; }
}

@Component
final class PreviewQuizGenerationJobHandler extends GenerationJobHandler {
    PreviewQuizGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                    GenerationOutputValidator v, ObjectProvider<GenerationModelPort> m,
                                    MulgilProperties p, AiProviderUsageLedger u, JobQueue q,
                                    MeterRegistry metrics, ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "preview_quiz_generate"; }
}

@Component
final class ReviewQuizGenerationJobHandler extends GenerationJobHandler {
    ReviewQuizGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                   GenerationOutputValidator v, ObjectProvider<GenerationModelPort> m,
                                   MulgilProperties p, AiProviderUsageLedger u, JobQueue q,
                                   MeterRegistry metrics, ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "review_quiz_generate"; }
}

@Component
final class ExamSummaryGenerationJobHandler extends GenerationJobHandler {
    ExamSummaryGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                    GenerationOutputValidator v,
                                    ObjectProvider<GenerationModelPort> m, MulgilProperties p,
                                    AiProviderUsageLedger u, JobQueue q, MeterRegistry metrics,
                                    ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "exam_summary_generate"; }
}

@Component
final class ExamQuizGenerationJobHandler extends GenerationJobHandler {
    ExamQuizGenerationJobHandler(JdbcClient j, GenerationSnapshotService s, GenerationInputCompiler i,
                                 GenerationOutputValidator v,
                                 ObjectProvider<GenerationModelPort> m, MulgilProperties p,
                                 AiProviderUsageLedger u, JobQueue q, MeterRegistry metrics,
                                 ObjectMapper o, Clock c) {
        super(j, s, i, v, m, p, u, q, metrics, o, c);
    }
    public String jobType() { return "exam_quiz_generate"; }
}
