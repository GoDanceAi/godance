package ai.godance.web.dto;

import java.time.Instant;
import java.util.UUID;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() { }

    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Size(max = 255) String fullName
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

    public record AuthResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt
    ) {
        public static AuthResponse bearer(String token, Instant expiresAt) {
            return new AuthResponse(token, "Bearer", expiresAt);
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
     * ask for the current password.
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
            Instant createdAt
    ) {
        public static MeResponse from(User u) {
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
                    u.getCreatedAt());
        }
    }
}
