package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Against the published vectors, so a code an authenticator app shows is a code we accept. */
class TotpTest {

    private static final byte[] RFC_SEED = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    /** RFC 6238 Appendix B, SHA-1. It lists 8 digits; a 6-digit code is the last 6 of them. */
    @ParameterizedTest
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
            "20000000000, 353130",
    })
    void code_givenRfc6238Vectors_expectsPublishedCode(long epochSeconds, String expected) {
        long step = Totp.stepAt(Instant.ofEpochSecond(epochSeconds));

        assertThat(Totp.code(RFC_SEED, step)).isEqualTo(expected);
    }

    /** RFC 4648 section 10, without the padding authenticator apps don't want. */
    @ParameterizedTest
    @CsvSource({
            "f,      MY",
            "fo,     MZXQ",
            "foo,    MZXW6",
            "foob,   MZXW6YQ",
            "fooba,  MZXW6YTB",
            "foobar, MZXW6YTBOI",
    })
    void base32_givenRfc4648Vectors_expectsPublishedText(String input, String expected) {
        assertThat(Totp.base32(input.getBytes(StandardCharsets.US_ASCII))).isEqualTo(expected);
    }

    @Test
    void stepAt_givenSecondsInOneStep_expectsTheSameStep() {
        assertThat(Totp.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(59))).isEqualTo(1);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(60))).isEqualTo(2);
    }
}
