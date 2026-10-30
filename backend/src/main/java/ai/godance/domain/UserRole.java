package ai.godance.domain;

/**
 * What an account may do. Everyone starts as {@link #USER}; an
 * {@link #ADMIN} grants the rest through the console. Each role adds to the
 * one before it, so an account holds exactly one.
 *
 * <p>Which role reaches which route is declared on the route, with
 * {@link ai.godance.security.AdminRoles}.
 */
public enum UserRole {
    /** A customer. No console access. */
    USER,
    /** Reads the console. */
    SUPPORT,
    /** {@link #SUPPORT}, plus credit adjustments. */
    FINANCE,
    /** Everything, including granting and revoking roles. */
    ADMIN
}
