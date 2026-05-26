package ai.godance.domain;

/**
 * Lifecycle state of a {@link ModelCatalogEntry}. Only {@code ACTIVE} rows
 * are dispatchable via the gateway; everything else is non-billable.
 */
public enum CatalogStatus {
    /** Approved, billable, returned by {@code GET /v1/models}. */
    ACTIVE,
    /** Edit pending approval; not yet promoted to {@code ACTIVE}. */
    STAGED,
    /** Refresh job saw a new model id with no adapter mapping; flag only. */
    DISCOVERED,
    /** Retired; kept for historical price-series joins. */
    DEPRECATED
}
