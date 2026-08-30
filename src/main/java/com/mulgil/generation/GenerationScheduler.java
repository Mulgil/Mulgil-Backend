package com.mulgil.generation;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobQueue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

@Component
final class GenerationScheduler implements JobCompletionListener {
    static final String PROMPT_VERSION = "source-grounded-v1";
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;
    private final MulgilProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    GenerationScheduler(JdbcClient jdbc, GenerationSnapshotService snapshots, MulgilProperties properties,
                        TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void onCompleted(JobQueue.CompletionEvent event) {
        if (!event.type().equals("chunk_embed")) return;
        scheduleSession(event, "preview");
        scheduleSession(event, "review");
    }

    JobQueue.JobAccepted scheduleExam(UUID ownerId, UUID examId, boolean predicted) {
        return transactions.execute(status -> {
            GenerationSnapshotService.Snapshot snapshot = snapshots.exam(ownerId, examId, predicted);
            if (snapshot == null) return null;
            lockSession(snapshot.ownerId(), snapshot.sessionId());
            snapshot = snapshots.exam(ownerId, examId, predicted);
            if (!snapshot.ready()) return null;
            String type = predicted ? "exam_quiz_generate" : "exam_summary_generate";
            return enqueue(snapshot, type);
        });
    }

    private void scheduleSession(JobQueue.CompletionEvent event, String phase) {
        transactions.executeWithoutResult(status -> {
            lockSession(event.ownerId(), event.sessionId());
            GenerationSnapshotService.Snapshot snapshot = snapshots.session(
                    event.ownerId(), event.courseId(), event.sessionId(), phase);
            if (snapshot.ready()) enqueue(snapshot, phase + "_generate");
        });
    }

    private void lockSession(UUID ownerId, UUID sessionId) {
        jdbc.sql("SELECT id FROM class_sessions WHERE owner_id=:owner AND id=:session FOR UPDATE")
                .param("owner", ownerId).param("session", sessionId).query(UUID.class).optional();
    }

    private JobQueue.JobAccepted enqueue(GenerationSnapshotService.Snapshot snapshot, String type) {
        String model = properties.vertex().generationModel();
        String key = com.mulgil.indexing.ContentIndexingService.sha256(String.join("\u001f",
                snapshot.phase(), snapshot.ownerId().toString(), snapshot.courseId().toString(),
                snapshot.sessionId().toString(), snapshot.canonical(), model, PROMPT_VERSION));
        int version = jdbc.sql("""
                        SELECT COALESCE(max(input_version),0)+1 FROM ai_jobs
                        WHERE owner_id=:owner AND job_type IN (:types)
                          AND ((CAST(:exam AS uuid) IS NULL AND session_id=:session) OR exam_id=CAST(:exam AS uuid))
                """).param("owner", snapshot.ownerId()).param("types", snapshot.examId() == null
                        ? java.util.List.of("preview_generate", "review_generate")
                        : java.util.List.of("exam_summary_generate", "exam_quiz_generate"))
                .param("exam", snapshot.examId()).param("session", snapshot.sessionId())
                .query(Integer.class).single();
        return jdbc.sql("""
                        INSERT INTO ai_jobs
                            (id,owner_id,course_id,session_id,job_type,status,input_version,idempotency_key,
                             attempt_count,max_attempts,exam_id,source_hash,created_at)
                        VALUES (:id,:owner,:course,:session,:type,'queued',:version,:key,0,:maxAttempts,
                                :exam,:hash,:now)
                        ON CONFLICT (idempotency_key) DO UPDATE SET idempotency_key=EXCLUDED.idempotency_key
                        RETURNING id,status
                        """).param("id", UUID.randomUUID()).param("owner", snapshot.ownerId())
                .param("course", snapshot.courseId()).param("session", snapshot.sessionId()).param("type", type)
                .param("version", version).param("key", key).param("maxAttempts", properties.jobs().maxRetry() + 1)
                .param("exam", snapshot.examId()).param("hash", snapshot.snapshotHash())
                .param("now", Timestamp.from(clock.instant()))
                .query((row, ignored) -> new JobQueue.JobAccepted(row.getObject("id", UUID.class),
                        row.getString("status"))).single();
    }
}
