package ai.godance.domain;

/**
 * Single-tenant: just USER (everyone) and ADMIN (Godance operator).
 * Per outcome brief §"Users & Access (v1)".
 */
public enum UserRole {
    USER,
    ADMIN
}
