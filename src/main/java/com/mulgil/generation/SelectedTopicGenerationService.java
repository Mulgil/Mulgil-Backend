package com.mulgil.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import com.mulgil.indexing.ContentIndexingService;
import com.mulgil.job.AiProviderUsageLedger;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
class SelectedTopicGenerationService {
    private static final Duration PAYLOAD_TTL = Duration.ofHours(24);
    private final SelectedTopicRetrievalService retrieval;
    private final GenerationInputCompiler compiler;
    private final GenerationOutputValidator validator;
    private final ObjectProvider<GenerationModelPort> models;
    private final MulgilProperties properties;
    private final AiProviderUsageLedger usage;
    private final JobQueue jobs;
    private final JdbcClient jdbc;
    private final SelectedTopicPayloadStore payloads;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final Clock clock;

    SelectedTopicGenerationService(SelectedTopicRetrievalService retrieval, GenerationInputCompiler compiler,
                                   GenerationOutputValidator validator,
                                   ObjectProvider<GenerationModelPort> models, MulgilProperties properties,
                                   AiProviderUsageLedger usage, JobQueue jobs, JdbcClient jdbc,
                                   SelectedTopicPayloadStore payloads,
                                   com.fasterxml.jackson.databind.ObjectMapper json, Clock clock) {
        this.retrieval = retrieval;
        this.compiler = compiler;
        this.validator = validator;
        this.models = models;
        this.properties = properties;
        this.usage = usage;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.payloads = payloads;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    JobQueue.JobAccepted enqueue(UUID ownerId, UUID sessionId, Request rawRequest) {
        if (!properties.retrieval().selectedTopicEnabled()) throw new ApiException(HttpStatus.NOT_FOUND,
                "FEATURE_NOT_AVAILABLE", "Selected-topic generation is not available.");
        if (rawRequest.topK() > properties.retrieval().maxTopK()) throw new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_TOP_K", "topK exceeds the configured limit.");
        Request request = normalize(rawRequest);
        retrieval.validateScope(ownerId, request.courseId(), sessionId,
                request.sourceIds(), request.scopeExpansion());
        String serialized = write(request);
        String payloadHash = ContentIndexingService.sha256(serialized);
        JobQueue.AiJob job = jobs.enqueue(new JobQueue.EnqueueRequest("target_generate", ownerId,
                request.courseId(), sessionId, null, null, null, null, null, 1, payloadHash,
                "vertex", properties.vertex().generationModel(), GenerationScheduler.PROMPT_VERSION));
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM selected_topic_generations WHERE job_id=:job)")
                .param("job", job.id()).query(Boolean.class).single();
        if (!exists) {
            Instant now = clock.instant();
            Instant expires = now.plus(PAYLOAD_TTL);
            String key = payloads.put(ownerId, job.id(), serialized, payloadHash, expires);
            jdbc.sql("""
                    INSERT INTO selected_topic_generations
                        (job_id,owner_id,course_id,session_id,payload_object_key,payload_hash,
                         payload_expires_at,created_at)
                    VALUES (:job,:owner,:course,:session,:key,:hash,:expires,:now)
                    """).param("job", job.id()).param("owner", ownerId).param("course", request.courseId())
                    .param("session", sessionId).param("key", key).param("hash", payloadHash)
                    .param("expires", Timestamp.from(expires)).param("now", Timestamp.from(now)).update();
        }
        return new JobQueue.JobAccepted(job.id(), job.status());
    }

    Result generate(JobQueue.ClaimedJob job) throws JobHandler.JobExecutionException {
        StoredPayload stored = jdbc.sql("""
                SELECT payload_object_key,payload_hash,payload_expires_at
                FROM selected_topic_generations
                WHERE job_id=:job AND owner_id=:owner AND course_id=:course AND session_id=:session
                """).param("job", job.id()).param("owner", job.ownerId()).param("course", job.courseId())
                .param("session", job.sessionId())
                .query((row, ignored) -> new StoredPayload(row.getString("payload_object_key"),
                        row.getString("payload_hash"), row.getTimestamp("payload_expires_at").toInstant()))
                .optional().orElseThrow(() -> new JobHandler.JobExecutionException(
                        "TARGET_PAYLOAD_MISSING", "Target-generation payload is unavailable.", false));
        if (!stored.expiresAt().isAfter(clock.instant())) {
            throw new JobHandler.JobExecutionException(
                    "TARGET_PAYLOAD_EXPIRED", "Target-generation payload expired.", false);
        }
        Request request;
        try {
            request = json.readValue(payloads.read(stored.objectKey(), stored.hash()), Request.class);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new JobHandler.JobExecutionException(
                    "TARGET_PAYLOAD_UNAVAILABLE", "Target-generation payload is unavailable.", true);
        }
        if (!request.courseId().equals(job.courseId())) {
            throw new JobHandler.JobExecutionException(
                    "TARGET_PAYLOAD_INVALID", "Target-generation payload is invalid.", false);
        }
        String query = request.query();
        SelectedTopicRetrievalService.Retrieval found;
        try {
            found = retrieval.retrieve(job.ownerId(), request.courseId(), job.sessionId(), query, request.intent(),
                    request.topK(), request.sourceIds(), request.scopeExpansion());
        } catch (ApiException exception) {
            throw new JobHandler.JobExecutionException(exception.code(), exception.getMessage(),
                    "PROVIDER_UNAVAILABLE".equals(exception.code()));
        } catch (RuntimeException exception) {
            throw new JobHandler.JobExecutionException(
                    "PROVIDER_UNAVAILABLE", "Target retrieval failed.", true);
        }
        jdbc.sql("""
                UPDATE selected_topic_generations SET selected_chunk_ids=CAST(:chunks AS uuid[])
                WHERE job_id=:job AND owner_id=:owner
                """).param("chunks", found.chunks().stream().map(
                        SelectedTopicRetrievalService.SelectedChunk::chunkId).toArray(UUID[]::new))
                .param("job", job.id()).param("owner", job.ownerId()).update();
        GenerationInputCompiler.CompiledInput input = compiler.compileSelected(
                query, request.intent().value, found.chunks());
        GenerationModelPort model = models.getIfAvailable();
        if (model == null) throw new JobHandler.JobExecutionException(
                "PROVIDER_UNAVAILABLE", "Generation provider unavailable.", true);
        GenerationModelPort.GenerationResult generated;
        try {
            var generation = new GenerationModelPort.GenerationRequest(input, "source-grounded-v2",
                    request.intent().artifact, () -> jobs.updateProgress(job, "generating"),
                    job.ownerId(), ContentIndexingService.sha256(input.text()));
            if (input.text().codePoints().count() > properties.generation().inputSoftTokenLimit()) {
                GenerationModelPort.TokenCount tokens = usage.observeGenerationTokenCount(job,
                        properties.vertex().generationModel(), () -> model.countTokens(generation));
                if (tokens.inputTokens() > tokens.contextTokenLimit()) {
                    throw new JobHandler.JobExecutionException("GENERATION_INPUT_TOO_LARGE",
                            "Generation input exceeds the supported context.", false);
                }
            }
            generated = usage.observeGeneration(job, properties.vertex().generationModel(),
                    input.text().codePoints().count(), () -> model.generate(generation));
            jobs.updateProgress(job, "validating");
            GenerationOutputValidator.Output output = validator.parse(
                    generated.rawJson(), input, request.intent().artifact);
            SelectedTopicOutputPrivacy.rejectEcho(output, query, found.chunks());
            Result result = new Result(request.intent() == Intent.SUMMARY ? output.summary() : null,
                    request.intent() == Intent.MINDMAP ? output.mindmapNodes() : null,
                    request.intent() == Intent.MINDMAP ? output.mindmapEdges() : null,
                    request.intent() == Intent.QUIZ ? output.questions() : null,
                    output.sourceReferences(), found.chunks().size());
            jobs.updateProgress(job, "publishing");
            return result;
        } catch (GenerationModelPort.GenerationModelException exception) {
            throw new JobHandler.JobExecutionException(
                    exception.code(), "Generation provider failed.", exception.retryable());
        } catch (RuntimeException exception) {
            throw new JobHandler.JobExecutionException(
                    "PROVIDER_UNAVAILABLE", "Generation provider failed.", true);
        }
    }

    void publish(JobQueue.ClaimedJob job, Result result) {
        Instant now = clock.instant();
        String key = jdbc.sql("""
                UPDATE selected_topic_generations SET result_json=CAST(:result AS jsonb),
                    selected_count=:selected,completed_at=:now
                WHERE job_id=:job AND owner_id=:owner
                RETURNING payload_object_key
                """).param("result", write(result)).param("selected", result.selectedCount())
                .param("now", Timestamp.from(now)).param("job", job.id()).param("owner", job.ownerId())
                .query(String.class).single();
        payloads.release(key);
    }

    TargetView result(UUID ownerId, UUID jobId) {
        return jdbc.sql("""
                SELECT job.id,job.status,job.error_code,job.progress_stage,job.progress_updated_at,
                       target.result_json::text
                FROM selected_topic_generations target
                JOIN ai_jobs job ON job.id=target.job_id AND job.owner_id=target.owner_id
                JOIN courses course ON course.id=job.course_id AND course.owner_id=job.owner_id
                WHERE target.owner_id=:owner AND target.job_id=:job AND course.deleted_at IS NULL
                """).param("owner", ownerId).param("job", jobId)
                .query((row, ignored) -> new TargetView(row.getObject("id", UUID.class),
                        row.getString("status"), row.getString("error_code"), row.getString("progress_stage"),
                        row.getTimestamp("progress_updated_at") == null ? null
                                : row.getTimestamp("progress_updated_at").toInstant(),
                        row.getString("result_json") == null ? null : readTree(row.getString("result_json"))))
                .optional().orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found."));
    }

    private Request normalize(Request request) {
        List<UUID> sources = request.sourceIds() == null ? null : request.sourceIds().stream()
                .distinct().sorted(Comparator.naturalOrder()).toList();
        return new Request(request.courseId(), request.query().strip(), request.intent(), request.topK(),
                sources, request.scopeExpansion());
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Target-generation metadata is invalid.", exception);
        }
    }

    private JsonNode readTree(String value) {
        try { return json.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Stored target-generation result is invalid.", exception);
        }
    }

    enum Intent {
        SUMMARY("summary", GenerationModelPort.Artifact.SUMMARY),
        MINDMAP("mindmap", GenerationModelPort.Artifact.MINDMAP),
        QUIZ("quiz", GenerationModelPort.Artifact.QUIZ);

        final String value;
        final GenerationModelPort.Artifact artifact;

        Intent(String value, GenerationModelPort.Artifact artifact) {
            this.value = value;
            this.artifact = artifact;
        }
    }

    enum ScopeExpansion { NONE, SESSION }

    record Request(@jakarta.validation.constraints.NotNull UUID courseId,
                   @jakarta.validation.constraints.NotBlank
                   @jakarta.validation.constraints.Size(max = 2000) String query,
                   @jakarta.validation.constraints.NotNull Intent intent,
                   @jakarta.validation.constraints.Min(1)
                   @jakarta.validation.constraints.Max(20) int topK,
                   List<UUID> sourceIds,
                   @jakarta.validation.constraints.NotNull ScopeExpansion scopeExpansion) {
        Request {
            if (sourceIds != null) sourceIds = List.copyOf(sourceIds);
        }
    }

    record Result(JsonNode summary, JsonNode mindmapNodes, JsonNode mindmapEdges, JsonNode questions,
                  List<JsonNode> sourceReferences, int selectedCount) {}
    record TargetView(UUID jobId, String status, String errorCode, String progressStage,
                      Instant progressUpdatedAt, JsonNode result) {}
    private record StoredPayload(String objectKey, String hash, Instant expiresAt) {}
}
