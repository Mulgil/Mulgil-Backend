package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
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
}
