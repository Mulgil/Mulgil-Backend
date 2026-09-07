package com.mulgil.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mulgil.common.error.ApiException;
import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import com.mulgil.resource.ResourceObjectDeletionScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JOB_POLL_INTERVAL_MILLIS=600000")
@Import(GenerationTestFakes.class)
class SelectedTopicGenerationIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("mulgil").withUsername("mulgil").withPassword("mulgil");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mulgil.retrieval.selected-topic-enabled", () -> true);
        registry.add("mulgil.model-benchmark.enabled", () -> true);
        registry.add("mulgil.model-benchmark.candidate-models", () -> "gemini-candidate");
    }

    @Autowired JdbcClient jdbc;
    @Autowired SelectedTopicGenerationService selectedTopics;
    @Autowired ModelBenchmarkService benchmarks;
    @Autowired FakeGenerationModel model;
    @Autowired FakeChunkEmbedding embeddings;
    @Autowired FakeCloudStorage storage;
    @Autowired JobQueue jobs;
    @Autowired List<JobHandler> handlers;
    @Autowired MulgilProperties properties;
    @Autowired SelectedTopicPayloadCleanupScheduler payloadCleanup;
    @Autowired ResourceObjectDeletionScheduler objectCleanup;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    String ownerToken;
    String foreignToken;
    UUID owner;
    UUID otherOwner;
    UUID course;
    UUID session;
    UUID firstSource;
    UUID secondSource;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("DELETE FROM users").update();
        model.valid = true;
        model.generationCalls = 0;
        model.benchmarkCalls = 0;
        model.benchmarkModel = null;
        model.providerPayloadMarker = null;
        model.outputText = null;
        model.outputFieldName = null;
        model.outputScalar = null;
        embeddings.calls = 0;
        storage.reset();
        String ownerSubject = "selected-owner-" + UUID.randomUUID();
        String foreignSubject = "selected-foreign-" + UUID.randomUUID();
        ownerToken = login(ownerSubject);
        foreignToken = login(foreignSubject);
        owner = userId(ownerSubject);
        otherOwner = userId(foreignSubject);
        course = UUID.randomUUID();
        session = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO courses VALUES (:id,:owner,'Course',NULL,NULL,:now,:now)")
                .param("id", course).param("owner", owner).param("now", now).update();
        jdbc.sql("""
                INSERT INTO class_sessions
                    (id,owner_id,course_id,session_number,title,session_date,created_at,updated_at)
                VALUES (:id,:owner,:course,1,'Session',DATE '2026-09-07',:now,:now)
                """).param("id", session).param("owner", owner).param("course", course).param("now", now).update();
        firstSource = material("first.pdf", now);
        secondSource = material("second.pdf", now);
        chunk(firstSource, 1, UUID.fromString("00000000-0000-0000-0000-000000000001"), now);
        chunk(firstSource, 2, UUID.fromString("00000000-0000-0000-0000-000000000002"), now);
        chunk(secondSource, 3, UUID.fromString("00000000-0000-0000-0000-000000000003"), now);
    }

    @Test
    void enqueuesAuthenticatedRequest_withoutProviderWorkOrRawPublicPayload() throws Exception {
        String query = "http-private-query-never-public";
        HttpResult response = send("POST", "/api/v1/sessions/" + session + "/target-generations",
                ownerToken, Map.of("courseId", course, "query", query, "intent", "SUMMARY", "topK", 2,
                        "sourceIds", List.of(firstSource), "scopeExpansion", "NONE"));

        assertThat(response.status()).as(response.body()).isEqualTo(202);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("jobId").isTextual()).isTrue();
        assertThat(body.path("status").asText()).isEqualTo("queued");
        assertThat(response.body()).doesNotContain(query, "chunk 1", "rawJson", "provider");
        assertThat(model.generationCalls).isZero();
        assertThat(embeddings.calls).isZero();
        assertThat(storage.writes).isOne();

        UUID jobId = UUID.fromString(body.path("jobId").asText());
        assertThat(runTargetJob()).isTrue();
        HttpResult result = send("GET", "/api/v1/target-generations/" + jobId, ownerToken, null);
        assertThat(result.status()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("status").asText()).isEqualTo("succeeded");
        assertThat(json.readTree(result.body()).path("result").path("selectedCount").asInt()).isEqualTo(2);
        assertThat(result.body()).doesNotContain(query, "chunk 1", "rawJson", "payload_object_key");
        assertThat(model.generationCalls).isOne();
        assertThat(embeddings.calls).isOne();
    }

    @Test
    void rejectsForeignOwnerHttpRequest_beforePayloadOrProviderWork() throws Exception {
        HttpResult response = send("POST", "/api/v1/sessions/" + session + "/target-generations",
                foreignToken, Map.of("courseId", course, "query", "foreign private query", "intent", "SUMMARY",
                        "topK", 1, "sourceIds", List.of(firstSource), "scopeExpansion", "NONE"));

        assertThat(response.status()).as(response.body()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("foreign private query", "chunk 1");
        assertThat(storage.writes).isZero();
        assertThat(embeddings.calls).isZero();
        assertThat(model.generationCalls).isZero();
    }

    @Test
    void rejectsForeignSourceHttpRequest_beforePayloadOrProviderWork() throws Exception {
        HttpResult response = send("POST", "/api/v1/sessions/" + session + "/target-generations",
                ownerToken, Map.of("courseId", course, "query", "invalid scope private query", "intent", "QUIZ",
                        "topK", 1, "sourceIds", List.of(UUID.randomUUID()), "scopeExpansion", "NONE"));

        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("invalid scope private query", "chunk 1");
        assertThat(storage.writes).isZero();
        assertThat(embeddings.calls).isZero();
        assertThat(model.generationCalls).isZero();
    }

    @Test
    void generatesOnlyFromAuthorizedSelectedChunks_andStoresNoRawQuery() {
        String query = "private-target-query-never-persist";
        String providerPayload = "raw-provider-payload-never-persist";
        model.providerPayloadMarker = providerPayload;
        var request = new SelectedTopicGenerationService.Request(course, query,
                SelectedTopicGenerationService.Intent.SUMMARY, 3, List.of(firstSource),
                SelectedTopicGenerationService.ScopeExpansion.SESSION);

        var result = generate(request);

        assertThat(result.selectedCount()).isEqualTo(3);
        assertThat(result.sourceReferences()).extracting(reference -> reference.path("materialId").asText())
                .containsExactly(firstSource.toString(), secondSource.toString(), firstSource.toString());
        assertThat(result.sourceReferences()).extracting(reference -> reference.path("pageNumber").asInt())
                .containsExactly(1, 3, 2);
        assertThat(result.summary().path("items").get(0).path("sourceRefs")).containsExactly(
                result.sourceReferences().get(0));
        assertThat(embeddings.calls).isOne();
        assertThat(jdbc.sql("""
                SELECT query_hash||':'||intent||':'||requested_k||':'||candidate_count||':'||selected_count
                FROM selected_topic_retrieval_metrics WHERE owner_id=:owner
                """).param("owner", owner).query(String.class).single())
                .matches("[0-9a-f]{64}:summary:3:3:3");
        assertNoPersistedPayload(query, "chunk 1", "chunk 2", "chunk 3", providerPayload);
        System.out.println("SELECTED_TOPIC scenario=explicit_scope_expansion observable=three_diverse_valid_citations_no_raw_query result=PASS");
    }

    @Test
    void rejectsProviderEchoOfRawQueryAndCompleteChunk_beforePersistenceOrResponse() throws Exception {
        String query = "private-query-echo-sentinel";
        String chunk = "chunk 1";
        model.outputText = query + " / " + chunk;
        UUID jobId = selectedTopics.enqueue(owner, session, request(query)).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-echo-it", Set.of("target_generate"));

        JobHandler.JobExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JobHandler.JobExecutionException.class, () -> jobs.run(claimed, targetHandler()));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        assertThat(failure.code()).isEqualTo("SENSITIVE_GENERATION_OUTPUT");
        assertThat(failure.retryable()).isFalse();
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
        assertThat(json.writeValueAsString(selectedTopics.result(owner, jobId)))
                .doesNotContain(query, chunk);
        assertNoPersistedPayload(query, chunk);
    }

    @Test
    void rejectsProviderEchoOfCompleteChunkWithoutQuery_beforePersistenceOrResponse() throws Exception {
        String query = "unrelated private query";
        String chunk = "chunk 1";
        model.outputText = "Provider prefix / " + chunk + " / provider suffix";
        UUID jobId = selectedTopics.enqueue(owner, session, request(query)).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-chunk-echo-it", Set.of("target_generate"));

        JobHandler.JobExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JobHandler.JobExecutionException.class, () -> jobs.run(claimed, targetHandler()));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        assertThat(failure.code()).isEqualTo("SENSITIVE_GENERATION_OUTPUT");
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
        assertThat(json.writeValueAsString(selectedTopics.result(owner, jobId))).doesNotContain(chunk);
        assertNoPersistedPayload(chunk);
    }

    @Test
    void rejectsProviderEchoOfRawQueryInFieldName_beforePersistenceOrResponse() throws Exception {
        String query = "private-query-field-sentinel";
        model.outputFieldName = query;
        UUID jobId = selectedTopics.enqueue(owner, session, request(query)).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-field-echo-it", Set.of("target_generate"));

        JobHandler.JobExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JobHandler.JobExecutionException.class, () -> jobs.run(claimed, targetHandler()));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        assertThat(failure.code()).isEqualTo("SENSITIVE_GENERATION_OUTPUT");
        assertThat(failure.getMessage()).doesNotContain(query);
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
        assertThat(json.writeValueAsString(selectedTopics.result(owner, jobId))).doesNotContain(query);
        assertNoPersistedPayload(query);
    }

    @Test
    void rejectsProviderEchoOfRawQueryAsNumericScalar_beforePersistenceOrResponse() throws Exception {
        String query = "314159";
        model.outputScalar = json.getNodeFactory().numberNode(314159);
        UUID jobId = selectedTopics.enqueue(owner, session, request(query)).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-numeric-echo-it", Set.of("target_generate"));

        JobHandler.JobExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JobHandler.JobExecutionException.class, () -> jobs.run(claimed, targetHandler()));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        assertThat(failure.code()).isEqualTo("SENSITIVE_GENERATION_OUTPUT");
        assertThat(failure.getMessage()).doesNotContain(query);
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
        assertThat(json.writeValueAsString(selectedTopics.result(owner, jobId))).doesNotContain(query);
        assertNoPersistedPayload(query);
    }

    @Test
    void rejectsProviderEchoOfRawQueryAsBooleanScalar_beforePersistenceOrResponse() throws Exception {
        String query = "true";
        model.outputScalar = json.getNodeFactory().booleanNode(true);
        UUID jobId = selectedTopics.enqueue(owner, session, request(query)).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-boolean-echo-it", Set.of("target_generate"));

        JobHandler.JobExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                JobHandler.JobExecutionException.class, () -> jobs.run(claimed, targetHandler()));
        jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());

        assertThat(failure.code()).isEqualTo("SENSITIVE_GENERATION_OUTPUT");
        assertThat(failure.getMessage()).doesNotContain(query);
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
        assertThat(json.writeValueAsString(selectedTopics.result(owner, jobId))).doesNotContain(query);
        assertNoPersistedPayload(query);
    }

    @Test
    void keepsSelectionInsideExplicitSourceScope_whenExpansionIsNone() {
        var result = generate(new SelectedTopicGenerationService.Request(
                course, "scoped query", SelectedTopicGenerationService.Intent.SUMMARY, 2,
                List.of(firstSource), SelectedTopicGenerationService.ScopeExpansion.NONE));

        assertThat(result.selectedCount()).isEqualTo(2);
        assertThat(result.sourceReferences()).extracting(reference -> reference.path("materialId").asText())
                .containsOnly(firstSource.toString());
        assertThat(result.sourceReferences()).extracting(reference -> reference.path("pageNumber").asInt())
                .containsExactly(1, 2);
        assertThat(result.sourceReferences()).noneMatch(reference ->
                reference.path("materialId").asText().equals(secondSource.toString()));
        assertThat(embeddings.calls).isOne();
    }

    @Test
    void rejectsCrossOwnerAndUnknownSourceScope_beforeVectorSearch() {
        assertThatThrownBy(() -> selectedTopics.enqueue(otherOwner, session,
                new SelectedTopicGenerationService.Request(course, "query",
                        SelectedTopicGenerationService.Intent.QUIZ, 2, null,
                        SelectedTopicGenerationService.ScopeExpansion.NONE)))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("SESSION_NOT_FOUND");
        assertThatThrownBy(() -> selectedTopics.enqueue(owner, session,
                new SelectedTopicGenerationService.Request(course, "query",
                        SelectedTopicGenerationService.Intent.QUIZ, 2, List.of(UUID.randomUUID()),
                        SelectedTopicGenerationService.ScopeExpansion.NONE)))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("INVALID_SOURCE_SCOPE");
        assertThat(embeddings.calls).isZero();
        assertThat(storage.writes).isZero();
    }

    @Test
    void idempotencyFingerprintCoversEveryExplicitRequestChoice() {
        var base = new SelectedTopicGenerationService.Request(course, "same query",
                SelectedTopicGenerationService.Intent.SUMMARY, 1, List.of(firstSource),
                SelectedTopicGenerationService.ScopeExpansion.NONE);
        UUID first = selectedTopics.enqueue(owner, session, base).jobId();

        assertThat(selectedTopics.enqueue(owner, session, base).jobId()).isEqualTo(first);
        assertThat(selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                course, "different query", base.intent(), base.topK(), base.sourceIds(), base.scopeExpansion())).jobId())
                .isNotEqualTo(first);
        assertThat(selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                course, base.query(), SelectedTopicGenerationService.Intent.QUIZ,
                base.topK(), base.sourceIds(), base.scopeExpansion())).jobId()).isNotEqualTo(first);
        assertThat(selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                course, base.query(), base.intent(), 2, base.sourceIds(), base.scopeExpansion())).jobId())
                .isNotEqualTo(first);
        assertThat(selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                course, base.query(), base.intent(), base.topK(), List.of(secondSource), base.scopeExpansion())).jobId())
                .isNotEqualTo(first);
        assertThat(selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                course, base.query(), base.intent(), base.topK(), base.sourceIds(),
                SelectedTopicGenerationService.ScopeExpansion.SESSION)).jobId()).isNotEqualTo(first);
        assertThat(storage.writes).isEqualTo(6);
        assertThat(embeddings.calls).isZero();
        assertThat(model.generationCalls).isZero();
    }

    @Test
    void targetGenerationConsumesDailyAdmission() {
        int limit = properties.demo().maxAiJobsPerDay();
        for (int index = 0; index < limit; index++) {
            selectedTopics.enqueue(owner, session, new SelectedTopicGenerationService.Request(
                    course, "quota query " + index, SelectedTopicGenerationService.Intent.SUMMARY,
                    1, List.of(firstSource), SelectedTopicGenerationService.ScopeExpansion.NONE));
        }

        assertThatThrownBy(() -> selectedTopics.enqueue(owner, session,
                new SelectedTopicGenerationService.Request(course, "one too many",
                        SelectedTopicGenerationService.Intent.SUMMARY, 1, List.of(firstSource),
                        SelectedTopicGenerationService.ScopeExpansion.NONE)))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).code())
                .isEqualTo("AI_DAILY_LIMIT_REACHED");
        assertThat(storage.writes).isEqualTo(limit);
        assertThat(embeddings.calls).isZero();
        assertThat(model.generationCalls).isZero();
    }

    @Test
    void successfulPublicationSchedulesAndDeletesPrivatePayload() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("success cleanup")).jobId();
        String key = payloadKey(jobId);
        int deletesBefore = storage.deletes;

        assertThat(runTargetJob()).isTrue();
        objectCleanup.cleanupDue();

        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(storage.deletes).isGreaterThan(deletesBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM resource_object_deletions WHERE object_key=:key")
                .param("key", key).query(Integer.class).single()).isZero();
    }

    @Test
    void terminalCleanupRetriesPrivatePayloadDeletion() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("terminal cleanup")).jobId();
        String key = payloadKey(jobId);
        jdbc.sql("UPDATE ai_jobs SET status='cancelled',finished_at=now() WHERE id=:job")
                .param("job", jobId).update();
        payloadCleanup.releaseTerminal();
        storage.failDelete = true;

        objectCleanup.cleanupDue();

        assertThat(jdbc.sql("SELECT attempt_count FROM resource_object_deletions WHERE object_key=:key")
                .param("key", key).query(Integer.class).single()).isOne();
        assertThat(storage.objects).containsKey(key);

        storage.failDelete = false;
        jdbc.sql("UPDATE resource_object_deletions SET not_before=created_at WHERE object_key=:key")
                .param("key", key).update();
        objectCleanup.cleanupDue();
        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void finalNonRetryableTargetFailureMakesDurableDeletionDue_withoutCallingStorage() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("final failure cleanup")).jobId();
        String key = payloadKey(jobId);
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-final-it", Set.of("target_generate"));
        int deletesBefore = storage.deletes;

        jobs.fail(claimed, "SENSITIVE_GENERATION_OUTPUT", "Generated output was rejected.", false);
        payloadCleanup.releaseTerminal();

        assertThat(jobs.get(owner, jobId).status()).isEqualTo("failed");
        assertThat(jdbc.sql("""
                SELECT status||':'||(not_before<=:now)||':'||attempt_count
                FROM resource_object_deletions WHERE object_key=:key
                """).param("now", Timestamp.from(clock.instant())).param("key", key)
                .query(String.class).single()).isEqualTo("pending:true:0");
        assertThat(storage.deletes).isEqualTo(deletesBefore);
        assertThat(storage.objects).containsKey(key);
    }

    @Test
    void exhaustedRetryableTargetFailureMakesDurableDeletionDue_withoutCallingStorage() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("exhausted failure cleanup")).jobId();
        String key = payloadKey(jobId);
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-exhausted-it", Set.of("target_generate"));
        jdbc.sql("UPDATE ai_jobs SET attempt_count=max_attempts WHERE id=:job")
                .param("job", jobId).update();
        int deletesBefore = storage.deletes;

        jobs.fail(claimed, "PROVIDER_TIMEOUT", "Provider timed out.", true);
        payloadCleanup.releaseTerminal();

        assertThat(jobs.get(owner, jobId).status()).isEqualTo("failed");
        assertThat(jdbc.sql("""
                SELECT status||':'||(not_before<=:now)||':'||attempt_count
                FROM resource_object_deletions WHERE object_key=:key
                """).param("now", Timestamp.from(clock.instant())).param("key", key)
                .query(String.class).single()).isEqualTo("pending:true:0");
        assertThat(storage.deletes).isEqualTo(deletesBefore);
        assertThat(storage.objects).containsKey(key);
    }

    @Test
    void expiryDeletesPrivatePayloadWithoutPublishingResult() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("expiry cleanup")).jobId();
        String key = payloadKey(jobId);
        jdbc.sql("""
                UPDATE selected_topic_generations SET payload_expires_at=created_at+interval '1 second'
                WHERE job_id=:job
                """)
                .param("job", jobId).update();
        jdbc.sql("UPDATE resource_object_deletions SET not_before=created_at WHERE object_key=:key")
                .param("key", key).update();

        objectCleanup.cleanupDue();

        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(jdbc.sql("SELECT result_json IS NULL FROM selected_topic_generations WHERE job_id=:job")
                .param("job", jobId).query(Boolean.class).single()).isTrue();
        assertThat(model.generationCalls).isZero();
    }

    @Test
    void rejectsPublicationWhenSelectedSourceBecomesOutdated() throws Exception {
        UUID jobId = selectedTopics.enqueue(owner, session, request("stale selected source")).jobId();
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-stale-it", Set.of("target_generate"));
        JobHandler.JobPublication publication = targetHandler().handle(claimed);
        jdbc.sql("UPDATE materials SET status='outdated' WHERE id=:source").param("source", firstSource).update();

        assertThat(jobs.complete(claimed, publication)).isFalse();
        assertThat(jobs.get(owner, jobId).status()).isEqualTo("outdated");
        assertThat(selectedTopics.result(owner, jobId).result()).isNull();
    }

    @Test
    void retriesProviderFailureWithSameJobAndPrivatePayload() {
        UUID jobId = selectedTopics.enqueue(owner, session, request("retry private query")).jobId();
        String key = payloadKey(jobId);
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-retry-it", Set.of("target_generate"));
        model.failureCode = "PROVIDER_TIMEOUT";

        try {
            jobs.run(claimed, targetHandler());
            throw new AssertionError("Expected provider failure.");
        } catch (JobHandler.JobExecutionException failure) {
            jobs.fail(claimed, failure.code(), failure.getMessage(), failure.retryable());
        }

        assertThat(jobs.get(owner, jobId).status()).isEqualTo("failed");
        assertThat(jobs.retry(owner, jobId).id()).isEqualTo(jobId);
        assertThat(storage.objects).containsKey(key);
        model.failureCode = null;
        assertThat(runTargetJob()).isTrue();
        assertThat(jobs.get(owner, jobId).status()).isEqualTo("succeeded");
    }

    @Test
    void recordsCandidateBenchmark_withoutPublishingOrChangingBaseline() throws Exception {
        GenerationSnapshotService.Source source = new GenerationSnapshotService.Source("pdf_text", firstSource,
                1, "a".repeat(64), "sanitized benchmark source",
                json.readTree("{\"sourceType\":\"pdf_text\",\"materialId\":\"" + firstSource
                        + "\",\"contentBlockId\":\"" + UUID.randomUUID() + "\",\"pageNumber\":1}"), true);
        var snapshot = new GenerationSnapshotService.Snapshot("review", owner, course, session, null,
                List.of(source), source.canonical(), "b".repeat(64), true,
                GenerationSnapshotService.Readiness.READY);

        ModelBenchmarkService.BenchmarkResult result = benchmarks.run(
                snapshot, GenerationModelPort.Artifact.SUMMARY, "gemini-candidate");

        assertThat(result.validOutput()).isTrue();
        assertThat(model.benchmarkModel).isEqualTo("gemini-candidate");
        assertThat(model.benchmarkCalls).isOne();
        assertThat(model.generationCalls).isZero();
        assertThat(jdbc.sql("SELECT model_id||':'||valid_output FROM generation_model_benchmarks")
                .query(String.class).single()).isEqualTo("gemini-candidate:true");
        assertThat(jdbc.sql("SELECT count(*) FROM summaries").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM mindmaps").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM quiz_questions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM generation_model_approvals")
                .query(Integer.class).single()).isZero();
        assertThat(benchmarks.operationallyApproved("gemini-candidate")).isFalse();
        assertNoPersistedPayload("sanitized benchmark source");
        System.out.println("MODEL_BENCHMARK scenario=allowlisted_measurement observable=ledger_only_no_publication_no_approval result=PASS");
    }

    @Test
    void v020CreatesOwnerScopedRetentionLedgers_withoutRawPayloadColumns() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public'
                  AND table_name IN ('selected_topic_retrieval_metrics','generation_model_benchmarks')
                  AND column_name='retention_expires_at' AND is_nullable='NO'
                """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public'
                  AND table_name IN ('selected_topic_retrieval_metrics','generation_model_benchmarks')
                  AND column_name IN ('raw_query','query_text','chunk_text','source_text','raw_prompt','provider_payload')
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE contype='f'
                  AND conrelid IN ('selected_topic_retrieval_metrics'::regclass,
                                   'generation_model_benchmarks'::regclass)
                  AND confrelid='users'::regclass
                """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname='public' AND indexname IN
                    ('selected_topic_retrieval_metrics_retention_idx',
                     'generation_model_benchmarks_model_created_idx',
                     'generation_model_benchmarks_retention_idx')
                """).query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid='generation_model_approvals'::regclass
                  AND contype='c' AND pg_get_constraintdef(oid) LIKE '%approved_at < expires_at%'
                """).query(Integer.class).single()).isOne();
    }

    @Test
    void v021StoresOnlyScopedTemporaryPayloadMetadataAndSafeResult() {
        assertThat(jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='public' AND table_name='selected_topic_generations'
                ORDER BY ordinal_position
                """).query(String.class).list()).containsExactly(
                "job_id", "owner_id", "course_id", "session_id", "payload_object_key", "payload_hash",
                "payload_expires_at", "selected_chunk_ids", "result_json", "selected_count",
                "created_at", "completed_at");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema='public' AND table_name='selected_topic_generations'
                  AND column_name IN ('raw_query','query_text','chunk_text','source_text','raw_prompt',
                                      'provider_payload','payload_text')
                """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid='selected_topic_generations'::regclass AND contype='f'
                  AND confrelid IN ('users'::regclass,'class_sessions'::regclass,'ai_jobs'::regclass)
                """).query(Integer.class).single()).isEqualTo(3);
    }

    private void assertNoPersistedPayload(String... rawValues) {
        for (String raw : rawValues) {
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM (
                        SELECT row_to_json(metric)::text AS payload FROM selected_topic_retrieval_metrics metric
                        UNION ALL
                        SELECT row_to_json(benchmark)::text FROM generation_model_benchmarks benchmark
                        UNION ALL
                        SELECT row_to_json(usage)::text FROM ai_provider_usage usage
                        UNION ALL
                        SELECT row_to_json(job)::text FROM ai_jobs job
                        UNION ALL
                        SELECT row_to_json(target)::text FROM selected_topic_generations target
                        UNION ALL
                        SELECT row_to_json(deletion)::text FROM resource_object_deletions deletion
                    ) persisted WHERE payload LIKE :raw
                    """).param("raw", "%" + raw + "%").query(Integer.class).single()).isZero();
        }
    }

    private SelectedTopicGenerationService.Result generate(SelectedTopicGenerationService.Request request) {
        UUID jobId = selectedTopics.enqueue(owner, session, request).jobId();
        assertThat(runTargetJob()).isTrue();
        JsonNode result = selectedTopics.result(owner, jobId).result();
        try {
            return json.treeToValue(result, SelectedTopicGenerationService.Result.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SelectedTopicGenerationService.Request request(String query) {
        return new SelectedTopicGenerationService.Request(course, query,
                SelectedTopicGenerationService.Intent.SUMMARY, 1, List.of(firstSource),
                SelectedTopicGenerationService.ScopeExpansion.NONE);
    }

    private String payloadKey(UUID jobId) {
        return jdbc.sql("SELECT payload_object_key FROM selected_topic_generations WHERE job_id=:job")
                .param("job", jobId).query(String.class).single();
    }

    private boolean runTargetJob() {
        JobQueue.ClaimedJob claimed = jobs.claim("selected-topic-it", Set.of("target_generate"));
        assertThat(claimed).isNotNull();
        try {
            return jobs.run(claimed, targetHandler());
        } catch (JobHandler.JobExecutionException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JobHandler targetHandler() {
        return handlers.stream().filter(candidate -> candidate.jobType().equals("target_generate"))
                .findFirst().orElseThrow();
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users VALUES (:id,'google',:subject,:email,'Owner',now())")
                .param("id", id).param("subject", id.toString()).param("email", id + "@example.com").update();
        return id;
    }

    private UUID userId(String subject) {
        return jdbc.sql("SELECT id FROM users WHERE provider_subject=:subject")
                .param("subject", subject).query(UUID.class).single();
    }

    private String login(String subject) throws Exception {
        String fake = String.join("|", "fake", "https://accounts.google.com", "test-google-client", subject,
                subject + "@example.com", "Student", Long.toString(Instant.now().plusSeconds(300).getEpochSecond()));
        HttpResult response = send("POST", "/api/v1/auth/oauth/google", null, Map.of("idToken", fake));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("accessToken").asText();
    }

    private HttpResult send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(request.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    record HttpResult(int status, String body) {}

    private UUID material(String filename, Timestamp now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO materials
                    (id,owner_id,course_id,session_id,source_phase,object_key,original_filename,mime_type,
                     byte_size,page_count,checksum,version,status,created_at,updated_at)
                VALUES (:id,:owner,:course,:session,'review_pdf',:key,:name,'application/pdf',10,1,
                        :hash,1,'succeeded',:now,:now)
                """).param("id", id).param("owner", owner).param("course", course).param("session", session)
                .param("key", "test/" + id).param("name", filename).param("hash", "a".repeat(64))
                .param("now", now).update();
        return id;
    }

    private void chunk(UUID material, int number, UUID chunkId, Timestamp now) {
        UUID page = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO document_pages
                    (id,owner_id,course_id,session_id,material_id,page_number,text_content,text_hash,
                     extraction_method,created_at)
                VALUES (:id,:owner,:course,:session,:material,:page,'page',:hash,'pdf_text',:now)
                """).param("id", page).param("owner", owner).param("course", course).param("session", session)
                .param("material", material).param("page", number).param("hash", "b".repeat(64)).param("now", now).update();
        jdbc.sql("""
                INSERT INTO content_blocks
                    (id,owner_id,course_id,session_id,material_id,page_id,block_type,text_content,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:material,:page,'text',:text,:hash,:now)
                """).param("id", block).param("owner", owner).param("course", course).param("session", session)
                .param("material", material).param("page", page).param("text", "chunk " + number)
                .param("hash", "c".repeat(64)).param("now", now).update();
        String vector = "[" + String.join(",", java.util.Collections.nCopies(768, "0.1")) + "]";
        jdbc.sql("""
                INSERT INTO chunks
                    (id,owner_id,course_id,session_id,content_block_id,chunk_index,text_content,source_ref,
                     embedding,embedding_model,source_hash,created_at)
                VALUES (:id,:owner,:course,:session,:block,0,:text,CAST(:ref AS jsonb),CAST(:embedding AS vector),
                        'fake-embedding',:hash,:now)
                """).param("id", chunkId).param("owner", owner).param("course", course).param("session", session)
                .param("block", block).param("text", "chunk " + number).param("ref", """
                        {"sourceType":"pdf_text","materialId":"%s","contentBlockId":"%s",
                         "pageNumber":%d,"inputVersion":1}
                        """.formatted(material, block, number)).param("embedding", vector)
                .param("hash", "d".repeat(64)).param("now", now).update();
    }
}
