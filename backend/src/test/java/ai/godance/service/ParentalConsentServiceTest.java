package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

/**
 * The age rule: under 16 needs a parent's consent. Signup has the birth
 * month, not the day, so the whole birth month counts as before the 16th
 * birthday; nobody counts as 16 early.
 */
class ParentalConsentServiceTest {

    private static final YearMonth MAY_2010 = YearMonth.of(2010, 5);

    @Test
    void needsConsent_givenFifteenYearOld_expectsTrue() {
        assertThat(ParentalConsentService.needsConsent(MAY_2010, LocalDate.of(2026, 4, 30))).isTrue();
    }

    @Test
    void needsConsent_givenSixteenthBirthdayMonth_expectsTrueAllMonth() {
        assertThat(ParentalConsentService.needsConsent(MAY_2010, LocalDate.of(2026, 5, 1))).isTrue();
        assertThat(ParentalConsentService.needsConsent(MAY_2010, LocalDate.of(2026, 5, 31))).isTrue();
    }

    @Test
    void needsConsent_givenMonthAfterSixteenthBirthdayMonth_expectsFalse() {
        assertThat(ParentalConsentService.needsConsent(MAY_2010, LocalDate.of(2026, 6, 1))).isFalse();
    }

    @Test
    void needsConsent_givenDecemberBirthMonth_expectsAdultFromJanuary() {
        YearMonth dec2009 = YearMonth.of(2009, 12);
        assertThat(ParentalConsentService.needsConsent(dec2009, LocalDate.of(2025, 12, 31))).isTrue();
        assertThat(ParentalConsentService.needsConsent(dec2009, LocalDate.of(2026, 1, 1))).isFalse();
    }

    @Test
    void needsConsent_givenAdult_expectsFalse() {
        assertThat(ParentalConsentService.needsConsent(YearMonth.of(1990, 1), LocalDate.of(2026, 10, 29)))
                .isFalse();
    }
}
