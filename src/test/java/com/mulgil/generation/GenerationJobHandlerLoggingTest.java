package com.mulgil.generation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulgil.job.JobHandler;
import com.mulgil.job.JobQueue;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationJobHandlerLoggingTest {
    @Test
    void logsOnlySafeValidationDiagnostics_whenGeneratedOutputIsRejected() {
        JobQueue.ClaimedJob job = new JobQueue.ClaimedJob(UUID.randomUUID(), "review_quiz_generate",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null, null,
                1, "a".repeat(64), 0, 2, "logging-test");
        JobHandler.JobExecutionException rejection = new JobHandler.JobExecutionException(
                "INVALID_GENERATION_OUTPUT", "Generated content was invalid.", false,
                new JobHandler.ValidationDetails("OPTIONS_COUNT", "quizQuestions[0].question.options", 4, 3));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(GenerationJobHandler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);
        try {
            GenerationJobHandler.logOutputRejected(job, GenerationModelPort.Artifact.QUIZ, rejection);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key).containsExactly(
                "event", "jobId", "operation", "artifact", "errorCode", "rule", "path",
                "expectedCount", "actualCount");
        assertThat(event.getKeyValuePairs()).extracting(pair -> String.valueOf(pair.value)).containsExactly(
                "generation.output.rejected", job.id().toString(), "review_quiz_generate", "quiz",
                "INVALID_GENERATION_OUTPUT", "OPTIONS_COUNT", "quizQuestions[0].question.options", "4", "3");
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getMDCPropertyMap()).isEmpty();
    }
}
