package ai.godance.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * What a failed job's stored error says, for batch fallbacks: only what can
 * never be a content refusal is a provider error or a timeout.
 */
class JobFailureTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "upstream: 500 Internal Server Error from POST http://openai.test/v1/images/generations | PROVIDER_ERROR",
            "upstream: 503 Service Unavailable from POST http://openai.test/v1/images/generations | PROVIDER_ERROR",
            "upstream: 429 Too Many Requests from POST http://openai.test/v1/images/generations | PROVIDER_ERROR",
            "gateway dispatch: provider OPENAI is not allowed in this region | PROVIDER_ERROR",
            "worker lost: the job outlived its lease | TIMEOUT",
            "upstream: java.util.concurrent.TimeoutException: Did not observe any item or terminal signal within 300000ms | TIMEOUT",
            "upstream: Timeout on blocking read for 360000000000 NANOSECONDS | TIMEOUT",
            // A 400 can be OpenAI's content_policy_violation: never retried on another model.
            "upstream: 400 Bad Request from POST http://openai.test/v1/images/generations | OTHER",
            "upstream: 422 Unprocessable Entity from POST http://bfl.test/v1/flux-2-pro | OTHER",
            "upstream: BFL: Content Moderated for job abc | OTHER",
            "upstream: Kling: failed for task abc | OTHER",
            "upstream returned no images | OTHER",
            "finalize: could not store the output | OTHER",
    })
    void of_givenAStoredError_expectsItsClass(String error, JobFailure expected) {
        assertThat(JobFailure.of(error)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void of_givenNoError_expectsOther(String error) {
        assertThat(JobFailure.of(error)).isEqualTo(JobFailure.OTHER);
    }
}
