package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobWorkerTest {
    @Test
    void genericWorkerDoesNotClaimChunkEmbeddings() {
        JobQueue queue = mock(JobQueue.class);
        JobHandler chunkHandler = mock(JobHandler.class);
        when(chunkHandler.jobType()).thenReturn("chunk_embed");
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.jobs()).thenReturn(new MulgilProperties.Jobs(2, 60, 60, 4));
        JobWorker worker = new JobWorker(queue, List.of(chunkHandler), properties);

        try {
            worker.poll();
        } finally {
            worker.close();
        }

        verifyNoInteractions(queue);
    }

    @Test
    void directPessimisticLockingFailureIsRetryableDatabaseDeadlock() throws JobHandler.JobExecutionException {
        assertFailure(new PessimisticLockingFailureException("deadlock"), "DATABASE_DEADLOCK", true);
    }

    @Test
    void wrappedPessimisticLockingFailureIsRetryableDatabaseDeadlock() throws JobHandler.JobExecutionException {
        assertFailure(new RuntimeException("handler failed", new PessimisticLockingFailureException("deadlock")),
                "DATABASE_DEADLOCK", true);
    }

    @Test
    void nestedPostgresDeadlockSqlStateIsRetryableDatabaseDeadlock() throws JobHandler.JobExecutionException {
        assertFailure(new RuntimeException("handler failed", new SQLException("", "40P01")),
                "DATABASE_DEADLOCK", true);
    }

    @Test
    void deeplyNestedPostgresDeadlockSqlStateIsRetryableDatabaseDeadlock() throws JobHandler.JobExecutionException {
        assertFailure(new RuntimeException("handler failed",
                        new IllegalStateException("database failed", new SQLException("", "40P01"))),
                "DATABASE_DEADLOCK", true);
    }

    @Test
    void nestedNonDeadlockSqlStateIsNotRetryableJobHandlerFailure() throws JobHandler.JobExecutionException {
        assertFailure(new RuntimeException("handler failed",
                        new IllegalStateException("database failed", new SQLException("", "23505"))),
                "JOB_HANDLER_FAILED", false);
    }

    @Test
    void nonmatchingRuntimeFailureIsNotRetryableJobHandlerFailure() throws JobHandler.JobExecutionException {
        assertFailure(new IllegalStateException("handler failed"), "JOB_HANDLER_FAILED", false);
    }

    @Test
    void nonmatchingDataAccessFailureIsNotRetryableJobHandlerFailure() throws JobHandler.JobExecutionException {
        assertFailure(new DataAccessResourceFailureException("database unavailable"), "JOB_HANDLER_FAILED", false);
    }

    private static void assertFailure(RuntimeException failure, String code, boolean retryable)
            throws JobHandler.JobExecutionException {
        JobQueue queue = mock(JobQueue.class);
        JobHandler handler = mock(JobHandler.class);
        when(handler.jobType()).thenReturn("pdf_extract");
        JobQueue.ClaimedJob claimed = claimedJob();
        when(queue.claim(anyString(), anySet())).thenReturn(claimed);
        doThrow(failure).when(queue).run(claimed, handler);
        MulgilProperties properties = mock(MulgilProperties.class);
        when(properties.jobs()).thenReturn(new MulgilProperties.Jobs(2, 60, 60, 4));
        JobWorker worker = new JobWorker(queue, List.of(handler), properties);

        try {
            worker.poll();
            verify(queue).fail(claimed, code, "Job handler failed.", retryable);
        } finally {
            worker.close();
        }
    }

    private static JobQueue.ClaimedJob claimedJob() {
        return new JobQueue.ClaimedJob(UUID.randomUUID(), "pdf_extract", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, null, null, null, null, 1, "source-hash", 1, 3, "worker");
    }
}
