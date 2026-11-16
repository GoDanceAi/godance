package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.User;

/**
 * The profile rules (round 14 brief 01): what a handle may be, the reserved
 * words, the display name and the link, and who may take part. The
 * integration tests check the same rules on the wire.
 */
class ProfileServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"mina", "abc", "mina-k", "m1n4", "a-b-c", "123"})
    void handle_givenValidHandle_expectsItLowercased(String handle) {
        assertThat(ProfileService.handle(" " + handle.toUpperCase() + " ")).isEqualTo(handle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-mina", "mina-", "mi--na", "mi na", "mi_na", "mína", "mina.k", "",
            "abcdefghijklmnopqrstuvwxyzabcde"})
    void handle_givenInvalidHandle_expectsInvalidProfile(String handle) {
        assertThatThrownBy(() -> ProfileService.handle(handle))
                .isInstanceOfSatisfying(CommunityRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo(CommunityRefusedException.CODE_INVALID_PROFILE);
                    assertThat(e.getStatusCode().value()).isEqualTo(400);
                });
    }

    @Test
    void handle_givenThirtyCharacters_expectsAccepted() {
        String thirty = "a".repeat(30);
        assertThat(ProfileService.handle(thirty)).isEqualTo(thirty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", "godance", "support", "moderator", "staff", "official", "api",
            "community", "gallery", "admin-2", "god-ance", "godance-team", "admin123", "2-admin", "settings"})
    void handle_givenReservedWord_expectsRefusedAsReserved(String handle) {
        assertThatThrownBy(() -> ProfileService.handle(handle))
                .isInstanceOfSatisfying(CommunityRefusedException.class, e -> {
                    assertThat(e.code()).isEqualTo(CommunityRefusedException.CODE_INVALID_PROFILE);
                    assertThat(e.getBody().getProperties()).containsEntry("reason", "reserved");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"supporting-cast", "teamwork-studio", "mina", "apiary", "staffordshire"})
    void isReserved_givenOrdinaryHandle_expectsFalse(String handle) {
        // Reserved words are matched whole, after removing digits and hyphens; a longer word is not one of them.
        assertThat(ProfileService.isReserved(handle)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrator-9", "admins", "godance-fan", "godancer"})
    void isReserved_givenStaffOrBrandPrefix_expectsTrue(String handle) {
        assertThat(ProfileService.isReserved(handle)).isTrue();
    }

    @Test
    void displayName_givenBlankOrTooLongOrMultiline_expectsInvalidProfile() {
        assertThatThrownBy(() -> ProfileService.displayName("   ")).isInstanceOf(CommunityRefusedException.class);
        assertThatThrownBy(() -> ProfileService.displayName("x".repeat(61))).isInstanceOf(CommunityRefusedException.class);
        assertThatThrownBy(() -> ProfileService.displayName("two\nlines")).isInstanceOf(CommunityRefusedException.class);
        assertThat(ProfileService.displayName("  Mina Kaur  ")).isEqualTo("Mina Kaur");
        assertThat(ProfileService.displayName("x".repeat(60))).hasSize(60);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.test/mina", "ftp://example.test", "javascript:alert(1)", "example.test",
            "https://", "https://user:pw@example.test/", "https:// spaced.test"})
    void link_givenNotHttps_expectsInvalidProfile(String link) {
        assertThatThrownBy(() -> ProfileService.link(link)).isInstanceOf(CommunityRefusedException.class);
    }

    @Test
    void link_givenHttpsOrNothing_expectsKept() {
        assertThat(ProfileService.link("https://example.test/mina")).isEqualTo("https://example.test/mina");
        assertThat(ProfileService.link("HTTPS://example.test/mina")).isEqualTo("https://example.test/mina");
        assertThat(ProfileService.link(null)).isNull();
        assertThat(ProfileService.link("  ")).isNull();
        assertThatThrownBy(() -> ProfileService.link("https://example.test/" + "a".repeat(300)))
                .isInstanceOf(CommunityRefusedException.class);
    }

    @Test
    void cannotTakePart_givenAccounts_expectsOnlyVerifiedAdultsAllowed() {
        User adult = new User();
        adult.setEmailVerified(true);
        assertThat(ProfileService.cannotTakePart(adult)).isNull();

        User unverified = new User();
        unverified.setEmailVerified(false);
        assertThat(ProfileService.cannotTakePart(unverified)).isEqualTo("email_not_verified");

        User child = new User();
        child.setEmailVerified(true);
        child.setAgeStatus(AgeStatus.CONSENT_GRANTED);
        assertThat(ProfileService.cannotTakePart(child)).isEqualTo("not_adult");
    }
}
