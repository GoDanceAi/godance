package ai.godance.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() { }

    /**
     * {@code birthMonth} ({@code YYYY-MM}) is optional here: the web app
     * always asks, but an API client that leaves it out is treated as an
     * adult attesting their age by accepting the Terms. Anyone under 16
     * must give {@code parentEmail}, a parent's or guardian's address that
     * differs from their own; it is ignored for everyone else.
     */
    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 255) String fullName,
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "must be YYYY-MM") String birthMonth,
            @Email @Size(max = 320) String parentEmail
    ) { }

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) { }

    public record ForgotPasswordRequest(
            @Email @NotBlank String email
    ) { }

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) { }

    /**
     * Either a session ({@code accessToken}), or, when the account has
     * two-factor sign-in on, {@code twoFactorToken} and no session: the
     * caller sends that to {@code POST /v1/auth/2fa/verify} with a code, by
     * {@code expiresAt}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt,
            String twoFactorToken
    ) {
        public static AuthResponse bearer(String token, Instant expiresAt) {
            return new AuthResponse(token, "Bearer", expiresAt, null);
        }

        public static AuthResponse twoFactor(String challengeToken, Instant expiresAt) {
            return new AuthResponse(null, null, expiresAt, challengeToken);
        }
    }

    /** The token from a verification email, to free that address from its account. */
    public record ReleaseEmailRequest(
            @NotBlank @Size(max = 64) String token
    ) { }

    /** An address to send a fresh verification (and release) link to. */
    public record ReleaseLinkRequest(
            @Email @NotBlank @Size(max = 320) String email
    ) { }

    /**
     * A new email for the signed-in account: a first real address for an
     * X.com placeholder account, or a change. {@code currentPassword} is
     * required when the account has a password.
     */
    public record ChangeEmailRequest(
            @Email @NotBlank @Size(max = 320) String email,
            @Size(max = 128) String currentPassword
    ) { }

    /**
     * {@code hasEmail} is false when {@code email} is only a placeholder
     * (an X.com sign-in, which shares no address), so clients don't show
     * it or offer to verify it. {@code pendingEmail} is an address the
     * user has asked to add or change to and not yet verified, or null.
     * {@code hasPassword} tells clients whether a change of email will
     * ask for the current password. {@code ageStatus} is
     * {@code CONSENT_PENDING} while a child's account waits for a parent's
     * approval; {@code parentEmailMasked} then says (masked) where the
     * request went, and is null for an adult. {@code twoFactorEnabled} is
     * whether signing in needs an authenticator code.
     */
    public record MeResponse(
            UUID id,
            String email,
            boolean hasEmail,
            String pendingEmail,
            boolean hasPassword,
            String fullName,
            UserRole role,
            boolean emailVerified,
            long creditBalanceCents,
            Long dailyCreditCapCents,
            Long monthlyCreditCapCents,
            Instant createdAt,
            AgeStatus ageStatus,
            String parentEmailMasked,
            boolean twoFactorEnabled
    ) {
        public static MeResponse from(User u, boolean twoFactorEnabled) {
            return new MeResponse(
                    u.getId(),
                    u.getEmail(),
                    !u.hasSyntheticEmail(),
                    u.getPendingEmail(),
                    u.getPasswordHash() != null,
                    u.getFullName(),
                    u.getRole(),
                    u.isEmailVerified(),
                    u.getCreditBalanceCents(),
                    u.getDailyCreditCapCents(),
                    u.getMonthlyCreditCapCents(),
                    u.getCreatedAt(),
                    u.getAgeStatus(),
                    u.getParentEmail() == null ? null : maskEmail(u.getParentEmail()),
                    twoFactorEnabled);
        }
    }

    /**
     * An address with most of its local part hidden, {@code p***@example.com},
     * to show someone where an email went without spelling the address out.
     */
    public static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
