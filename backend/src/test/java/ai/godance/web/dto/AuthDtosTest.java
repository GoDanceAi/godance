package ai.godance.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.web.dto.AuthDtos.MeResponse;

/**
 * {@link MeResponse#hasEmail()} tells clients whether {@code email} is a
 * real inbox or the placeholder an X.com sign-in gets.
 */
class AuthDtosTest {

    private static User user(String email) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setFullName("Someone");
        u.setRole(UserRole.USER);
        u.setActive(true);
        return u;
    }

    @Test
    void meResponseFrom_givenRealEmail_expectsHasEmailTrue() {
        MeResponse me = MeResponse.from(user("someone@godance.test"));

        assertThat(me.hasEmail()).isTrue();
        assertThat(me.email()).isEqualTo("someone@godance.test");
        assertThat(me.hasPassword()).isFalse();
    }

    @Test
    void meResponseFrom_givenPasswordAccount_expectsHasPasswordTrue() {
        User u = user("someone@godance.test");
        u.setPasswordHash("$2a$12$placeholderplaceholderplaceholderplaceholderplaceh");

        assertThat(MeResponse.from(u).hasPassword()).isTrue();
    }

    @Test
    void meResponseFrom_givenSyntheticXAddress_expectsHasEmailFalse() {
        MeResponse me = MeResponse.from(user("x-42" + User.SYNTHETIC_EMAIL_SUFFIX));

        assertThat(me.hasEmail()).isFalse();
        assertThat(me.pendingEmail()).isNull();
    }

    @Test
    void meResponseFrom_givenAddressBeingAdded_expectsPendingEmailAndStillNoEmail() {
        User u = user("x-42" + User.SYNTHETIC_EMAIL_SUFFIX);
        u.setPendingEmail("added@godance.test");

        MeResponse me = MeResponse.from(u);

        assertThat(me.hasEmail()).isFalse();
        assertThat(me.pendingEmail()).isEqualTo("added@godance.test");
    }

    @Test
    void meResponseFrom_givenChildWaitingForConsent_expectsStatusAndMaskedParentEmail() {
        User u = user("kid@godance.test");
        u.setAgeStatus(AgeStatus.CONSENT_PENDING);
        u.setParentEmail("parent@family.test");

        MeResponse me = MeResponse.from(u);

        assertThat(me.ageStatus()).isEqualTo(AgeStatus.CONSENT_PENDING);
        assertThat(me.parentEmailMasked()).isEqualTo("p***@family.test");
    }

    @Test
    void meResponseFrom_givenAdult_expectsAdultAndNoParentEmail() {
        MeResponse me = MeResponse.from(user("someone@godance.test"));

        assertThat(me.ageStatus()).isEqualTo(AgeStatus.ADULT);
        assertThat(me.parentEmailMasked()).isNull();
    }

    @Test
    void maskEmail_givenAddress_expectsFirstLetterAndDomainOnly() {
        assertThat(AuthDtos.maskEmail("ada.lovelace@example.com")).isEqualTo("a***@example.com");
        assertThat(AuthDtos.maskEmail("not-an-address")).isEqualTo("***");
    }
}
