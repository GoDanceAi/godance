-- =====================================================================
-- Godance V19 — verifiable parental consent for children's accounts.
--
-- Signup asks for a birth month. Anyone under 16 (one global threshold:
-- GDPR Art. 8's highest age, which also covers COPPA's 13) gets an
-- account that stays restricted until a parent approves it by paying a
-- top-up on it with their own card through Stripe Checkout.
--
-- age_status                     ADULT (every existing row, and every
--                                signup without a birth month: accepting
--                                the Terms attests the age),
--                                CONSENT_PENDING, CONSENT_GRANTED.
-- birth_month                    first day of the birth month; NULL when
--                                signup didn't ask (API clients, social
--                                sign-in, accounts made before V19).
-- parent_email                   where the consent request goes.
-- parental_consent_token_hash    SHA-256 (hex) of the single live consent
--                                token. The plaintext only goes out in
--                                the email to the parent; cleared once
--                                consent is granted.
-- parental_consent_requested_at  when that token was issued: drives the
--                                7-day expiry and the resend cooldown.
-- parental_consent_at            when the parent's payment cleared.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN age_status                     VARCHAR(32) NOT NULL DEFAULT 'ADULT',
    ADD COLUMN birth_month                    DATE,
    ADD COLUMN parent_email                   VARCHAR(320),
    ADD COLUMN parental_consent_token_hash    VARCHAR(64),
    ADD COLUMN parental_consent_requested_at  TIMESTAMPTZ,
    ADD COLUMN parental_consent_at            TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT ck_users_age_status
        CHECK (age_status IN ('ADULT', 'CONSENT_PENDING', 'CONSENT_GRANTED')),
    ADD CONSTRAINT ck_users_birth_month_first_day
        CHECK (birth_month IS NULL OR EXTRACT(DAY FROM birth_month) = 1);

CREATE UNIQUE INDEX ux_users_parental_consent_token_hash
    ON users (parental_consent_token_hash)
    WHERE parental_consent_token_hash IS NOT NULL;
