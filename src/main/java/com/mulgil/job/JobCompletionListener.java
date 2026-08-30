package com.mulgil.job;

@FunctionalInterface
public interface JobCompletionListener {
    void onCompleted(JobQueue.CompletionEvent event);
}
