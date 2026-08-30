package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JobQueue {
    private static final Set<String> RETRYABLE_ERRORS = Set.of(
            "PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMIT", "PROVIDER_UNAVAILABLE", "LEASE_EXPIRED");
    private static final String PDF_PROVIDER = "pdfbox";
    private static final String PDF_MODEL = "pdfbox-3";
    private static final String NO_PROMPT = "none";

    private final JdbcClient jdbc;
    private final MulgilProperties properties;
    private final AiJobAdmissionGuard admission;
    private final Clock clock;
    private final List<JobCompletionListener> listeners;

    JobQueue(JdbcClient jdbc, MulgilProperties properties, AiJobAdmissionGuard admission,
             Clock clock, List<JobCompletionListener> listeners) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.admission = admission;
        this.clock = clock;
        this.listeners = List.copyOf(listeners);
    }

    @Transactional
    public JobAccepted enqueuePdfMaterial(UUID ownerId, UUID materialId) {
        EnqueueRequest request = jdbc.sql("""
                        SELECT owner_id, course_id, session_id, id, version, checksum
                        FROM materials
                        WHERE owner_id = :ownerId AND id = :id AND status = 'uploaded'
                        """)
                .param("ownerId", ownerId).param("id", materialId)
                .query((row, ignored) -> EnqueueRequest.material("pdf_extract", row.getObject("owner_id", UUID.class),
                        row.getObject("course_id", UUID.class), row.getObject("session_id", UUID.class),
                        row.getObject("id", UUID.class), row.getInt("version"), row.getString("checksum"),
                        PDF_PROVIDER, PDF_MODEL, NO_PROMPT))
                .optional().orElseThrow(JobQueue::notFound);
        AiJob job = enqueue(request);
        return new JobAccepted(job.id(), job.status());
    }

    @Transactional
    public List<AiJob> enqueuePdfExamResource(UUID ownerId, UUID resourceId) {
        List<EnqueueRequest> requests = jdbc.sql("""
                        SELECT r.owner_id, r.course_id, member.session_id, r.id, r.checksum
                        FROM exam_resources r
                        JOIN exam_session_members member
                          ON member.exam_id = r.exam_id AND member.owner_id = r.owner_id
                         AND member.course_id = r.course_id
                        WHERE r.owner_id = :ownerId AND r.id = :id AND r.status = 'uploaded'
                        ORDER BY member.session_id
                        """)
                .param("ownerId", ownerId).param("id", resourceId)
                .query((row, ignored) -> EnqueueRequest.examResource("pdf_extract",
                        row.getObject("owner_id", UUID.class), row.getObject("course_id", UUID.class),
                        row.getObject("session_id", UUID.class), row.getObject("id", UUID.class), 1,
                        row.getString("checksum"), PDF_PROVIDER, PDF_MODEL, NO_PROMPT)).list();
        if (requests.isEmpty()) throw notFound();
        return requests.stream().map(this::enqueue).toList();
    }

    @Transactional
    public AiJob enqueue(EnqueueRequest request) {
        String key = idempotencyKey(request);
        admission.admit(request.ownerId(), request.type(), key);
        Instant now = clock.instant();
        return jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id, owner_id, course_id, session_id, job_type, status, input_version,
                             idempotency_key, attempt_count, max_attempts, material_id, exam_resource_id,
                             note_id, recording_id, exam_id, source_hash, created_at)
                        VALUES
                            (:id, :ownerId, :courseId, :sessionId, :type, 'queued', :version,
                             :key, 0, :maxAttempts, :materialId, :examResourceId,
                             :noteId, :recordingId, :examId, :sourceHash, :now)
                        ON CONFLICT (idempotency_key) DO UPDATE
                            SET status = CASE
                                    WHEN ai_jobs.status = 'failed'
                                     AND ai_jobs.attempt_count < ai_jobs.max_attempts
                                     AND ai_jobs.error_code IN
                                         ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE','LEASE_EXPIRED')
                                    THEN 'queued' ELSE ai_jobs.status END,
                                error_code = CASE
                                    WHEN ai_jobs.status = 'failed'
                                     AND ai_jobs.attempt_count < ai_jobs.max_attempts
                                     AND ai_jobs.error_code IN
                                         ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE','LEASE_EXPIRED')
                                    THEN NULL ELSE ai_jobs.error_code END,
                                error_message = CASE
                                    WHEN ai_jobs.status = 'failed'
                                     AND ai_jobs.attempt_count < ai_jobs.max_attempts
                                     AND ai_jobs.error_code IN
                                         ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE','LEASE_EXPIRED')
                                    THEN NULL ELSE ai_jobs.error_message END,
                                finished_at = CASE
                                    WHEN ai_jobs.status = 'failed'
                                     AND ai_jobs.attempt_count < ai_jobs.max_attempts
                                     AND ai_jobs.error_code IN
                                         ('PROVIDER_TIMEOUT','PROVIDER_RATE_LIMIT','PROVIDER_UNAVAILABLE','LEASE_EXPIRED')
                                    THEN NULL ELSE ai_jobs.finished_at END
                        RETURNING *
                        """)
                .param("id", UUID.randomUUID()).param("ownerId", request.ownerId())
                .param("courseId", request.courseId()).param("sessionId", request.sessionId())
                .param("type", request.type()).param("version", request.inputVersion()).param("key", key)
                .param("maxAttempts", properties.jobs().maxRetry() + 1).param("materialId", request.materialId())
                .param("examResourceId", request.examResourceId()).param("noteId", request.noteId())
                .param("recordingId", request.recordingId()).param("examId", request.examId())
                .param("sourceHash", request.sourceHash()).param("now", Timestamp.from(now))
                .query((row, ignored) -> job(row)).single();
    }

    @Transactional
    public ClaimedJob claim(String workerId, Set<String> supportedTypes) {
        if (supportedTypes.isEmpty()) return null;
        recoverExpired();
        Instant now = clock.instant();
        return jdbc.sql("""
                        WITH selected AS (
                            SELECT id FROM ai_jobs
                            WHERE status = 'queued' AND attempt_count < max_attempts
                              AND job_type IN (:types)
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED LIMIT 1
                        )
                        UPDATE ai_jobs job SET status = 'running', attempt_count = attempt_count + 1,
                            claimed_by = :workerId, last_heartbeat_at = :now,
                            lease_expires_at = :lease, started_at = COALESCE(started_at, :now),
                            error_code = NULL, error_message = NULL, finished_at = NULL
                        FROM selected WHERE job.id = selected.id
                        RETURNING job.*
                        """)
                .param("types", supportedTypes).param("workerId", workerId).param("now", Timestamp.from(now))
                .param("lease", Timestamp.from(now.plusSeconds(properties.jobs().leaseSeconds())))
                .query((row, ignored) -> claimed(row)).optional().orElse(null);
    }

    public boolean heartbeat(UUID jobId, String workerId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        UPDATE ai_jobs SET last_heartbeat_at = :now, lease_expires_at = :lease
                        WHERE id = :id AND status = 'running' AND claimed_by = :workerId
                          AND lease_expires_at > :now
                        """)
                .param("now", Timestamp.from(now))
                .param("lease", Timestamp.from(now.plusSeconds(properties.jobs().leaseSeconds())))
                .param("id", jobId).param("workerId", workerId).update() == 1;
    }

    @Transactional
    public boolean complete(ClaimedJob claimed, JobHandler.JobPublication publication) {
        AiJob current = lockRunning(claimed.id(), claimed.claimedBy());
        if (current == null) return false;
        if (!sourceIsCurrent(current)) {
            markOutdated(current.id(), claimed.claimedBy());
            return false;
        }
        publication.publish();
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        UPDATE ai_jobs SET status = 'succeeded', claimed_by = NULL, last_heartbeat_at = NULL,
                            lease_expires_at = NULL, finished_at = :now
                        WHERE id = :id AND status = 'running' AND claimed_by = :workerId
                        """).param("now", Timestamp.from(now)).param("id", current.id())
                .param("workerId", claimed.claimedBy()).update();
        if (updated == 1) notifyAfterCommit(event(current));
        return updated == 1;
    }

    public void fail(ClaimedJob claimed, String code, String message, boolean retryable) {
        String publicCode = retryable ? normalizeRetryableCode(code) : code;
        jdbc.sql("""
                        UPDATE ai_jobs SET status = 'failed', claimed_by = NULL, last_heartbeat_at = NULL,
                            lease_expires_at = NULL, error_code = :code, error_message = :message,
                            finished_at = :now
                        WHERE id = :id AND status = 'running' AND claimed_by = :workerId
                        """).param("code", publicCode).param("message", safeMessage(message))
                .param("now", Timestamp.from(clock.instant())).param("id", claimed.id())
                .param("workerId", claimed.claimedBy()).update();
    }

    public AiJob retry(UUID ownerId, UUID jobId) {
        AiJob result = jdbc.sql("""
                        UPDATE ai_jobs SET status = 'queued', error_code = NULL, error_message = NULL,
                            finished_at = NULL
                        WHERE owner_id = :ownerId AND id = :id AND status = 'failed'
                          AND attempt_count < max_attempts AND error_code IN (:errors)
                        RETURNING *
                        """).param("ownerId", ownerId).param("id", jobId).param("errors", RETRYABLE_ERRORS)
                .query((row, ignored) -> job(row)).optional().orElse(null);
        if (result != null) return result;
        if (find(ownerId, jobId) == null) throw notFound();
        throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_RETRYABLE", "Job is not retryable.");
    }

    public AiJob get(UUID ownerId, UUID jobId) {
        AiJob job = find(ownerId, jobId);
        if (job == null) throw notFound();
        return job;
    }

    public List<AiJob> list(UUID ownerId, UUID sessionId) {
        boolean owns = jdbc.sql("SELECT EXISTS(SELECT 1 FROM class_sessions WHERE owner_id=:owner AND id=:id)")
                .param("owner", ownerId).param("id", sessionId).query(Boolean.class).single();
        if (!owns) throw notFound();
        return jdbc.sql("SELECT * FROM ai_jobs WHERE owner_id=:owner AND session_id=:session ORDER BY created_at,id")
                .param("owner", ownerId).param("session", sessionId)
                .query((row, ignored) -> job(row)).list();
    }

    private void recoverExpired() {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE ai_jobs SET status = 'queued', claimed_by = NULL, last_heartbeat_at = NULL,
                    lease_expires_at = NULL, error_code = 'LEASE_EXPIRED',
                    error_message = 'Worker lease expired.'
                WHERE status = 'running' AND lease_expires_at <= :now AND attempt_count < max_attempts
                """).param("now", Timestamp.from(now)).update();
        jdbc.sql("""
                UPDATE ai_jobs SET status = 'failed', claimed_by = NULL, last_heartbeat_at = NULL,
                    lease_expires_at = NULL, error_code = 'LEASE_EXPIRED',
                    error_message = 'Worker lease expired.', finished_at = :now
                WHERE status = 'running' AND lease_expires_at <= :now AND attempt_count >= max_attempts
                """).param("now", Timestamp.from(now)).update();
    }

    private AiJob lockRunning(UUID id, String workerId) {
        return jdbc.sql("""
                        SELECT * FROM ai_jobs
                        WHERE id=:id AND status='running' AND claimed_by=:worker AND lease_expires_at > :now
                        FOR UPDATE
                        """).param("id", id).param("worker", workerId)
                .param("now", Timestamp.from(clock.instant()))
                .query((row, ignored) -> job(row)).optional().orElse(null);
    }

    private boolean sourceIsCurrent(AiJob job) {
        if (job.materialId() != null) {
            return jdbc.sql("""
                    SELECT id FROM materials WHERE id=:id AND owner_id=:owner
                        AND course_id=:course AND session_id=:session AND version=:version
                        AND checksum=:hash AND status='uploaded' FOR SHARE
                    """).param("id", job.materialId()).param("owner", job.ownerId())
                    .param("course", job.courseId()).param("session", job.sessionId())
                    .param("version", job.inputVersion()).param("hash", job.sourceHash())
                    .query(UUID.class).optional().isPresent();
        }
        if (job.examResourceId() != null) {
            return jdbc.sql("""
                    SELECT resource.id FROM exam_resources resource
                        JOIN exam_session_members member ON member.exam_id=resource.exam_id
                         AND member.owner_id=resource.owner_id AND member.course_id=resource.course_id
                        WHERE resource.id=:id AND resource.owner_id=:owner AND resource.course_id=:course
                          AND member.session_id=:session AND resource.checksum=:hash AND resource.status='uploaded'
                        FOR SHARE OF resource, member
                    """).param("id", job.examResourceId()).param("owner", job.ownerId())
                    .param("course", job.courseId()).param("session", job.sessionId())
                    .param("hash", job.sourceHash()).query(UUID.class).optional().isPresent();
        }
        return true;
    }

    private void markOutdated(UUID id, String workerId) {
        jdbc.sql("""
                UPDATE ai_jobs SET status='outdated', claimed_by=NULL, last_heartbeat_at=NULL,
                    lease_expires_at=NULL, error_code='STALE_INPUT', error_message='Job input is outdated.',
                    finished_at=:now WHERE id=:id AND claimed_by=:worker
                """).param("now", Timestamp.from(clock.instant())).param("id", id).param("worker", workerId).update();
    }

    private AiJob find(UUID ownerId, UUID jobId) {
        return jdbc.sql("SELECT * FROM ai_jobs WHERE owner_id=:owner AND id=:id")
                .param("owner", ownerId).param("id", jobId)
                .query((row, ignored) -> job(row)).optional().orElse(null);
    }

    private void notifyAfterCommit(CompletionEvent event) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                listeners.forEach(listener -> listener.onCompleted(event));
            }
        });
    }

    private static CompletionEvent event(AiJob job) {
        return new CompletionEvent(job.id(), job.type(), job.ownerId(), job.courseId(), job.sessionId(),
                job.materialId(), job.examResourceId(), job.noteId(), job.recordingId(), job.examId(),
                job.inputVersion(), job.sourceHash());
    }

    private static String idempotencyKey(EnqueueRequest request) {
        String resource = request.materialId() != null ? request.materialId().toString()
                : request.examResourceId() != null ? request.examResourceId().toString()
                : request.noteId() != null ? request.noteId().toString()
                : request.recordingId() != null ? request.recordingId().toString()
                : request.examId() != null ? request.examId().toString()
                : request.sessionId() + ":" + request.sourceHash();
        String canonical = String.join("\u001f", request.type(), resource, request.sessionId().toString(),
                Integer.toString(request.inputVersion()), request.sourceHash(), request.provider(),
                request.model(), request.promptVersion());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String normalizeRetryableCode(String code) {
        return RETRYABLE_ERRORS.contains(code) ? code : "PROVIDER_UNAVAILABLE";
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "Job failed.";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Job not found.");
    }

    private static AiJob job(ResultSet row) throws SQLException {
        return new AiJob(row.getObject("id", UUID.class), row.getString("job_type"), row.getString("status"),
                row.getObject("owner_id", UUID.class), row.getObject("course_id", UUID.class),
                row.getObject("session_id", UUID.class), row.getObject("material_id", UUID.class),
                row.getObject("exam_resource_id", UUID.class), row.getObject("note_id", UUID.class),
                row.getObject("recording_id", UUID.class), row.getObject("exam_id", UUID.class),
                row.getInt("input_version"), row.getString("source_hash"), row.getInt("attempt_count"),
                row.getInt("max_attempts"), row.getString("error_code"), instant(row, "created_at"),
                nullableInstant(row, "finished_at"));
    }

    private static ClaimedJob claimed(ResultSet row) throws SQLException {
        AiJob job = job(row);
        return new ClaimedJob(job.id(), job.type(), job.ownerId(), job.courseId(), job.sessionId(),
                job.materialId(), job.examResourceId(), job.noteId(), job.recordingId(), job.examId(),
                job.inputVersion(), job.sourceHash(), job.attemptCount(), row.getString("claimed_by"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record JobAccepted(UUID jobId, String status) {}
    public record AiJob(UUID id, String type, String status, UUID ownerId, UUID courseId, UUID sessionId,
                        UUID materialId, UUID examResourceId, UUID noteId, UUID recordingId, UUID examId,
                        int inputVersion, String sourceHash, int attemptCount, int maxAttempts,
                        String errorCode, Instant createdAt, Instant finishedAt) {}
    public record ClaimedJob(UUID id, String type, UUID ownerId, UUID courseId, UUID sessionId,
                             UUID materialId, UUID examResourceId, UUID noteId, UUID recordingId, UUID examId,
                             int inputVersion, String sourceHash, int attemptCount, String claimedBy) {}
    public record CompletionEvent(UUID jobId, String type, UUID ownerId, UUID courseId, UUID sessionId,
                                  UUID materialId, UUID examResourceId, UUID noteId, UUID recordingId, UUID examId,
                                  int inputVersion, String sourceHash) {}

    public record EnqueueRequest(String type, UUID ownerId, UUID courseId, UUID sessionId,
                                 UUID materialId, UUID examResourceId, UUID noteId, UUID recordingId, UUID examId,
                                 int inputVersion, String sourceHash, String provider, String model,
                                 String promptVersion) {
        public static EnqueueRequest material(String type, UUID ownerId, UUID courseId, UUID sessionId,
                                              UUID materialId, int inputVersion, String sourceHash,
                                              String provider, String model, String promptVersion) {
            return new EnqueueRequest(type, ownerId, courseId, sessionId, materialId, null, null, null, null,
                    inputVersion, sourceHash, provider, model, promptVersion);
        }

        public static EnqueueRequest examResource(String type, UUID ownerId, UUID courseId, UUID sessionId,
                                                  UUID resourceId, int inputVersion, String sourceHash,
                                                  String provider, String model, String promptVersion) {
            return new EnqueueRequest(type, ownerId, courseId, sessionId, null, resourceId, null, null, null,
                    inputVersion, sourceHash, provider, model, promptVersion);
        }
    }
}
