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

    public record AuthResponse(
            String accessToken,
            String tokenType,
            Instant expiresAt
    ) {
        public static AuthResponse bearer(String token, Instant expiresAt) {
            return new AuthResponse(token, "Bearer", expiresAt);
        }
    }

    /** A real address for an account that only has a placeholder (an X.com sign-in). */
    public record AddEmailRequest(
            @Email @NotBlank @Size(max = 320) String email
    ) { }

    /**
     * {@code hasEmail} is false when {@code email} is only a placeholder
     * (an X.com sign-in, which shares no address), so clients don't show
     * it or offer to verify it. {@code pendingEmail} is an address such a
     * user has asked to add and not yet verified, or null.
     */
    public record MeResponse(
            UUID id,
            String email,
            boolean hasEmail,
            String pendingEmail,
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
