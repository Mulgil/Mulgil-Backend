package com.mulgil.job;

import com.mulgil.common.config.MulgilProperties;

import java.util.List;

public final class JobWorkerTestDriver {
    private JobWorkerTestDriver() {}

    public static void poll(JobQueue queue, JobHandler handler, MulgilProperties properties) {
        JobWorker worker = new JobWorker(queue, List.of(handler), properties);
        try {
            worker.poll();
        } finally {
            worker.close();
        }
    }
}
