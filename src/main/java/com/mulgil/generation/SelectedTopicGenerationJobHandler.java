package com.mulgil.generation;

import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.springframework.stereotype.Component;

@Component
final class SelectedTopicGenerationJobHandler implements JobHandler {
    private final SelectedTopicGenerationService service;

    SelectedTopicGenerationJobHandler(SelectedTopicGenerationService service) {
        this.service = service;
    }

    @Override
    public String jobType() {
        return "target_generate";
    }

    @Override
    public JobPublication handle(JobQueue.ClaimedJob job) throws JobExecutionException {
        SelectedTopicGenerationService.Result result = service.generate(job);
        return () -> service.publish(job, result);
    }
}
