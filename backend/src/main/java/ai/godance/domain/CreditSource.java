package ai.godance.domain;

public enum CreditSource {
    STRIPE,
    /** NOWPayments — v1 crypto top-up rail (USDT on TRC-20 / ERC-20 / BEP-20). */
    NOWPAYMENTS,
    /** Binance Pay — v1.1 crypto rail, scaffolded but not yet live. */
    BINANCE_PAY,
    /** Legacy Coinbase Commerce rail — retired; kept for historical ledger rows. */
    COINBASE,
    DEBIT,
    REFUND,
    ADJUSTMENT,
    PROMO
}
