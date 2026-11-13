package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The shape of a referral code, and how a claimed one is read. */
class ReferralCodeTest {

    @Test
    void randomCode_givenManyDraws_expectsEightCharactersFromTheAlphabetOnly() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            String code = ReferralService.randomCode();
            assertThat(code).hasSize(ReferralService.CODE_LENGTH).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]+");
            seen.add(code);
        }
        assertThat(seen).hasSizeGreaterThan(1_990);   // ~31^8 codes: a repeat here would be a bug
    }

    @Test
    void codeAlphabet_givenLookAlikes_expectsNoneOfThem() {
        assertThat(ReferralService.CODE_ALPHABET).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void normalize_givenLowerCaseAndSpaces_expectsCanonicalCode() {
        assertThat(ReferralService.normalize("  k7qm2xpd ")).isEqualTo("K7QM2XPD");
    }

    @Test
    void normalize_givenWrongShape_expectsNull() {
        assertThat(ReferralService.normalize(null)).isNull();
        assertThat(ReferralService.normalize("")).isNull();
        assertThat(ReferralService.normalize("K7QM2XP")).isNull();      // too short
        assertThat(ReferralService.normalize("K7QM2XPDD")).isNull();    // too long
        assertThat(ReferralService.normalize("K7QM2XP0")).isNull();     // a zero is never in a code
        assertThat(ReferralService.normalize("K7QM-XPD")).isNull();
    }
}
