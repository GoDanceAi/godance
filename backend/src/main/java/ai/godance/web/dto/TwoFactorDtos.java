package ai.godance.web.dto;

import java.util.List;

import ai.godance.web.dto.AuthDtos.AuthResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class TwoFactorDtos {

    private TwoFactorDtos() { }

    /** {@code currentPassword} is required when the account has a password. */
    public record EnrolRequest(
            @Size(max = 128) String currentPassword
    ) { }

    /** {@code secret} for typing in by hand; {@code otpauthUri} is what the QR code encodes. */
    public record EnrolResponse(
            String secret,
            String otpauthUri
    ) { }

    /** The code that proves the authenticator app holds the secret. */
    public record EnableRequest(
            @NotBlank @Size(max = 32) String code
    ) { }

    /**
     * {@code recoveryCodes} are shown this once; only their hashes are kept.
     * Turning 2FA on ends every other session, so {@code session} replaces
     * the caller's.
     */
    public record EnableResponse(
            List<String> recoveryCodes,
            AuthResponse session
    ) { }

    /**
     * {@code code} is a current authenticator code or a recovery code; the
     * latter is how someone who lost their device moves to a new one.
     * {@code currentPassword} is required when the account has a password.
     */
    public record DisableRequest(
            @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 32) String code
    ) { }

    /** An authenticator code or a recovery code, to finish signing in. */
    public record VerifyRequest(
            @NotBlank @Size(max = 32) String code
    ) { }

    public record StatusResponse(
            boolean enabled,
            int recoveryCodesRemaining
    ) { }
}
