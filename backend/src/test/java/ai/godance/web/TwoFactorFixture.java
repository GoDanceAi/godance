package ai.godance.web;

import java.time.Instant;
import java.util.UUID;

import ai.godance.domain.UserTwoFactor;
import ai.godance.repository.UserTwoFactorRepository;

/** For tests about something else, such as admin routes, that only need an account with 2FA on. */
final class TwoFactorFixture {

    private TwoFactorFixture() { }

    /** Marks 2FA on without enrolling. The secret is a stand-in, so no code will verify. */
    static void turnOn(UserTwoFactorRepository twoFactors, UUID userId) {
        UserTwoFactor row = new UserTwoFactor();
        row.setUserId(userId);
        row.setSecretEncrypted("v1:stand-in");
        row.setEnabledAt(Instant.now());
        twoFactors.save(row);
    }
}
