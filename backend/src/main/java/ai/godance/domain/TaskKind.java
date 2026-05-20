package ai.godance.domain;

/**
 * Quartz task kinds scheduled per user via {@code scheduled_triggers}.
 * Expand as features land — v1 ships AUTO_RECHARGE + DAILY_REPORT.
 */
public enum TaskKind {
    AUTO_RECHARGE,
    DAILY_REPORT,
    WEEKLY_SUMMARY
}
