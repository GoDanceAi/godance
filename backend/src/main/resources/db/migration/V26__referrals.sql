-- V26: referrals (round 9; ships disabled).
--
-- A user shares a link carrying their referral code. Someone who signs up
-- with it claims the code, and both of them are credited once when the
-- invited user first tops up (ReferralRewardService). The credits are
-- REFERRAL rows in credit_ledger, keyed by the referral's id; this table
-- records who invited whom and what came of it.
--
-- A code is made the first time its owner asks for it: eight characters
-- from an alphabet with no look-alikes (no 0/O, 1/I/L). A user can be
-- invited once, so invited_user_id is unique.
--
-- Closing an account anonymises the users row and never deletes it, so
-- these rows stay. The cascades matter only for the unapproved child
-- accounts the retention job deletes, and those never take part.

CREATE TABLE referral_codes (
    user_id     UUID          PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    code        VARCHAR(8)    NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_referral_codes_code UNIQUE (code)
);

CREATE TABLE referrals (
    id                      UUID          PRIMARY KEY,
    referrer_id             UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_user_id         UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    claimed_at              TIMESTAMPTZ   NOT NULL,
    -- PENDING until the invited user's first qualifying top-up; REWARDED
    -- once both credits are written; VOID when it can never be rewarded.
    status                  VARCHAR(16)   NOT NULL
                            CHECK (status IN ('PENDING', 'REWARDED', 'VOID')),
    rewarded_at             TIMESTAMPTZ,
    -- The top-up it was rewarded on.
    rewarded_topup_id       UUID          REFERENCES credit_ledger(id),
    -- What each side got. The referrer's is 0 past the monthly limit.
    referrer_reward_cents   BIGINT        CHECK (referrer_reward_cents >= 0),
    referee_reward_cents    BIGINT        CHECK (referee_reward_cents >= 0),
    CONSTRAINT uq_referrals_invited_user UNIQUE (invited_user_id),
    CONSTRAINT ck_referrals_not_self CHECK (referrer_id <> invited_user_id),
    CONSTRAINT ck_referrals_rewarded CHECK (
        status <> 'REWARDED'
        OR (rewarded_at IS NOT NULL AND rewarded_topup_id IS NOT NULL
            AND referrer_reward_cents IS NOT NULL AND referee_reward_cents IS NOT NULL))
);

-- A referrer's counts (GET /v1/referrals/me) and this month's rewards.
CREATE INDEX ix_referrals_referrer_status ON referrals (referrer_id, status);
-- The sweep reads the pending ones, oldest claim first.
CREATE INDEX ix_referrals_pending ON referrals (claimed_at) WHERE status = 'PENDING';
