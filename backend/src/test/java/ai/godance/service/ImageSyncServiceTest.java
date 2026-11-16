package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import ai.godance.config.ImagesProperties;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.ImageResponseMode;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.service.ImageSyncService.Outcome;
import ai.godance.storage.ObjectStorageClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * How {@link ImageSyncService#await} counts its waits
 * ({@value ImageSyncService#WAITS_METER}): each once, by how it ended,
 * whatever its caller does after the outcome. The job's row is a mock,
 * read every 10 ms; no database.
 */
class ImageSyncServiceTest {

    private static final Duration VERIFY_WITHIN = Duration.ofSeconds(10);

    private final UUID jobId = UUID.randomUUID();
    private final GenerationJobRepository jobs = mock(GenerationJobRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // A 30-second wait: no test here comes near it.
    private final ImageSyncService sync = new ImageSyncService(jobs, mock(GenerationJobOutputRepository.class),
            mock(ObjectStorageClient.class), mock(ApiKeyService.class),
            new ImagesProperties(ImageResponseMode.ASYNC, Duration.ofSeconds(30), Duration.ofMillis(10),
                    DataSize.ofMegabytes(25)),
            meters);

    @Test
    void await_givenAFailedJobAnsweredAsAnError_expectsTheWaitCountedOnceAsFailed() {
        jobIs(JobStatus.FAILED);

        // What ImageJobAnswers does for the 502: Mono.error in a flatMap, which cancels the wait.
        StepVerifier.create(sync.await(jobId).flatMap(waited -> Mono.error(new IllegalStateException("502"))))
                .expectError(IllegalStateException.class)
                .verify(VERIFY_WITHIN);

        assertThat(counts()).isEqualTo(Map.of("done", 0.0, "failed", 1.0, "timeout", 0.0, "cancelled", 0.0));
    }

    @Test
    void await_givenACallerThatLeavesAfterTheOutcome_expectsTheWaitCountedOnceByItsOutcome() {
        jobIs(JobStatus.DONE);

        // As a client that disconnects while its answer is built.
        StepVerifier.create(sync.await(jobId))
                .expectNextMatches(waited -> waited.outcome() == Outcome.DONE)
                .thenCancel()
                .verify(VERIFY_WITHIN);

        assertThat(counts()).isEqualTo(Map.of("done", 1.0, "failed", 0.0, "timeout", 0.0, "cancelled", 0.0));
    }

    @Test
    void await_givenACallerThatLeavesWhileTheJobRuns_expectsTheWaitCountedAsCancelled() {
        jobIs(JobStatus.RUNNING);

        StepVerifier.create(sync.await(jobId))
                .expectSubscription()
                .expectNoEvent(Duration.ofMillis(100))   // read about ten times, still running
                .thenCancel()
                .verify(VERIFY_WITHIN);

        assertThat(counts()).isEqualTo(Map.of("done", 0.0, "failed", 0.0, "timeout", 0.0, "cancelled", 1.0));
    }

    private void jobIs(JobStatus status) {
        GenerationJob job = new GenerationJob();
        job.setId(jobId);
        job.setStatus(status);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    /** How many waits ended each way. */
    private Map<String, Double> counts() {
        Map<String, Double> counts = new LinkedHashMap<>();
        for (String outcome : List.of("done", "failed", "timeout", "cancelled")) {
            counts.put(outcome, meters.counter(ImageSyncService.WAITS_METER, "outcome", outcome).count());
        }
        return counts;
    }
}
