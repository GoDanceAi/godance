package ai.godance.domain;

/**
 * Social sign-in providers supported alongside email+password.
 * Persisted as a string in {@code oauth_identities.provider}.
 */
public enum OAuthProvider {
    GOOGLE,
    APPLE,
    X;

    /** Map a Spring Security {@code registrationId} ("google"/"apple"/"x") to the enum. */
    public static OAuthProvider fromRegistrationId(String registrationId) {
        return switch (registrationId.toLowerCase()) {
            case "google" -> GOOGLE;
            case "apple" -> APPLE;
            case "x", "twitter" -> X;
            default -> throw new IllegalArgumentException(
                    "unknown OAuth registrationId: " + registrationId);
        };
    }
}
