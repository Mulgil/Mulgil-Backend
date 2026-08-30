package com.mulgil.generation;

import com.mulgil.common.config.MulgilProperties;
import com.mulgil.job.JobCompletionListener;
import com.mulgil.job.JobQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
final class GenerationScheduler implements JobCompletionListener {
    static final String PROMPT_VERSION = "source-grounded-v1";
    private final JdbcClient jdbc;
    private final GenerationSnapshotService snapshots;
    private final MulgilProperties properties;
    private final ObjectProvider<JobQueue> jobs;
    private final TransactionTemplate transactions;

    GenerationScheduler(JdbcClient jdbc, GenerationSnapshotService snapshots, MulgilProperties properties,
                        ObjectProvider<JobQueue> jobs, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.properties = properties;
        this.jobs = jobs;
        this.transactions = transactions;
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
        String scope = type + ":" + (snapshot.examId() == null ? snapshot.sessionId() : snapshot.examId());
        int version = jdbc.sql("""
                        SELECT COALESCE(max(input_version),0)+1 FROM ai_jobs
                        WHERE owner_id=:owner AND job_type IN (:types)
                          AND ((CAST(:exam AS uuid) IS NULL AND session_id=:session) OR exam_id=CAST(:exam AS uuid))
                """).param("owner", snapshot.ownerId()).param("types", snapshot.examId() == null
                        ? java.util.List.of("preview_generate", "review_generate")
                        : java.util.List.of("exam_summary_generate", "exam_quiz_generate"))
                .param("exam", snapshot.examId()).param("session", snapshot.sessionId())
                .query(Integer.class).single();
        JobQueue.AiJob job = jobs.getObject().enqueue(new JobQueue.EnqueueRequest(type, snapshot.ownerId(),
                snapshot.courseId(), snapshot.sessionId(), null, null, null, null, snapshot.examId(), version,
                snapshot.snapshotHash(), "vertex", model, PROMPT_VERSION));
        return new JobQueue.JobAccepted(job.id(), job.status());
    }
}
