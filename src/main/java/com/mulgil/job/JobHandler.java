package com.mulgil.job;

public interface JobHandler {
    String jobType();

    JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException;

    @FunctionalInterface
    interface JobPublication {
        void publish();
    }

    final class JobExecutionException extends Exception {
        private final String code;
        private final boolean retryable;

        public JobExecutionException(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
