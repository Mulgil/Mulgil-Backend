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
        private final ValidationDetails validationDetails;

        public JobExecutionException(String code, String message, boolean retryable) {
            this(code, message, retryable, null);
        }

        public JobExecutionException(String code, String message, boolean retryable,
                                     ValidationDetails validationDetails) {
            super(message);
            this.code = code;
            this.retryable = retryable;
            this.validationDetails = validationDetails;
        }

        public String code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }

        public ValidationDetails validationDetails() {
            return validationDetails;
        }
    }

    record ValidationDetails(String rule, String path, Integer expectedCount, Integer actualCount) {}
}
