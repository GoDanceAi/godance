package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class RateLimitExceededExceptionTest {

    @Test
    void constructor_givenWait_expects429ProblemWithRetryAfterHeader() {
        RateLimitExceededException ex = new RateLimitExceededException(Duration.ofSeconds(840));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("840");
        assertThat(ex.getBody().getStatus()).isEqualTo(429);
        assertThat(ex.getBody().getDetail()).isEqualTo("too many requests, try again in 14 minutes");
    }

    @ParameterizedTest
    @CsvSource({
            "200,   1,    1 second",
            "1000,  1,    1 second",
            "1001,  2,    2 seconds",
            "59000, 59,   59 seconds",
            "60000, 60,   1 minute",
            "61000, 61,   2 minutes",
            "3600000, 3600, 60 minutes"})
    void constructor_givenWaitInMillis_expectsSecondsRoundedUpAndReadableDetail(
            long millis, long expectedSeconds, String expectedWait) {
        RateLimitExceededException ex = new RateLimitExceededException(Duration.ofMillis(millis));

        assertThat(ex.getRetryAfterSeconds()).isEqualTo(expectedSeconds);
        assertThat(ex.getReason()).isEqualTo("too many requests, try again in " + expectedWait);
    }
}
