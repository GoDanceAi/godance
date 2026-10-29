-- =====================================================================
-- Godance V18 — hold each request's estimate against spending caps
-- (spec risk R-10).
--
-- Caps were a threshold check on completed usage: a request passed while
-- spend was under the cap, whatever it cost, and concurrent or pending
-- requests all saw the same headroom, so together they could pass the cap.
--
-- A capped account's request now holds its estimate here, inside the
-- pre-debit transaction, after the balance update has locked the user's
-- row: requests from one account check and hold one at a time, and each
-- sees the others' holds. A hold counts until the request's usage row
-- exists (same request_id, outcome OK) or the request is refunded, and
-- for one hour at most. Accounts with no cap write no holds.
-- =====================================================================

CREATE TABLE spend_holds (
    request_id   VARCHAR(64)   PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key_id   UUID,
    cents        BIGINT        NOT NULL CHECK (cents > 0),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_spend_holds_user_created ON spend_holds (user_id, created_at);
CREATE INDEX ix_spend_holds_key_created  ON spend_holds (api_key_id, created_at) WHERE api_key_id IS NOT NULL;
