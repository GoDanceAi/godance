package ai.godance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;

/**
 * Ends a test only once every generation job it started has ended, and fails
 * the test, naming those jobs, if one is still open {@link #TIMEOUT} after
 * it. Every integration class whose tests start jobs carries it:
 * {@code @ExtendWith(OpenJobsGuard.class)} next to {@code @SpringBootTest}.
 *
 * <p>Why: a request that answers 202 returns while its job is still on its
 * way to the provider, on a bounded-elastic worker. The classes reset their
 * WireMock servers before each test, and that does not stop a job left
 * running: its call reaches the mocks during the next test, as that test's
 * request (a 404 once the stub is gone), and fails a check of the next
 * test's journal, such as "no request" or "exactly one request". JUnit
 * orders methods by a hash of their names, so adding or renaming a test can
 * put such a pair next to each other anywhere. Before this guard it failed
 * CI three times, each time in another class (image edits, video inputs,
 * image sync answers).
 *
 * <p>A job ends after its provider has answered, and WireMock journals a
 * request before answering it, so once the test's jobs have ended nothing
 * more of the test reaches the mocks.
 *
 * <p>The jobs waited for are those a worker may hold: open and leased. The
 * services lease every job they accept, before its worker starts (V16). A
 * row a test writes straight into the database, to read it back, has no
 * lease and no worker: nothing would end it, and nothing of it reaches a
 * mock. Jobs already open when the test began are left out: one left by an
 * earlier test has failed that test already, so a job that never ends fails
 * one test, not every test after it. A webhook goes out after its job has
 * ended and is not waited for; a test that sets one waits for the delivery
 * itself.
 */
public final class OpenJobsGuard implements BeforeEachCallback, AfterTestExecutionCallback {

    /** How long a test's jobs get to end once the test is over. A Sora job polls every 5 s. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** A job in one of these has not ended. */
    private static final Set<JobStatus> OPEN =
            EnumSet.complementOf(EnumSet.of(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED));

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(OpenJobsGuard.class);
    private static final String HELD_BEFORE = "heldBefore";

    /** Before the class's own {@code @BeforeEach}, so a job started there is the test's too. */
    @Override
    public void beforeEach(ExtensionContext context) {
        Set<UUID> heldBefore = held(jobs(context)).stream().map(GenerationJob::getId).collect(Collectors.toSet());
        context.getStore(NAMESPACE).put(HELD_BEFORE, heldBefore);
    }

    /** Right after the test method, before any {@code @AfterEach}, whether or not the test passed. */
    @Override
    public void afterTestExecution(ExtensionContext context) {
        GenerationJobRepository jobs = jobs(context);
        Set<?> heldBefore = context.getStore(NAMESPACE).get(HELD_BEFORE, Set.class);
        await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50)).atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(held(jobs).stream()
                                .filter(job -> !heldBefore.contains(job.getId()))
                                .map(OpenJobsGuard::describe))
                        .as("jobs the test started that are still open after it")
                        .isEmpty());
    }

    /** The jobs a worker may hold: open and leased. */
    private static List<GenerationJob> held(GenerationJobRepository jobs) {
        return OPEN.stream()
                .flatMap(status -> jobs.findByStatus(status).stream())
                .filter(job -> job.getLeaseExpiresAt() != null)
                .toList();
    }

    private static String describe(GenerationJob job) {
        return job.getId() + " " + job.getStatus() + " " + job.getProvider() + "/" + job.getModel();
    }

    private static GenerationJobRepository jobs(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context).getBean(GenerationJobRepository.class);
    }
}
