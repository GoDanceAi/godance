-- V40: bring your own key (round 14 brief 03).
--
-- A user stores their own API key for a provider. Requests to that
-- provider's models then go out on it, and Godance charges only its fee
-- (godance.byok.fee-basis-points of the list price). The regional allowlist
-- still decides first: a stored key never makes a forbidden provider
-- reachable, not even to check the key.
--
-- No plaintext at rest (backend CLAUDE.md, hard constraint 2): key_encrypted
-- is KeyVault's AES-256-GCM value, bound to the user and the provider, so a
-- value copied onto another row does not decrypt. last4 is all of the key
-- that is ever shown.

CREATE TABLE provider_credentials (
    id                    UUID          PRIMARY KEY,
    user_id               UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider              VARCHAR(32)   NOT NULL,
    key_encrypted         TEXT          NOT NULL
        CONSTRAINT ck_provider_credentials_vault CHECK (key_encrypted LIKE 'v1:%'),
    last4                 VARCHAR(4)    NOT NULL,
    -- valid: the provider accepted it; invalid: the provider refused it, and
    -- requests go out on the platform's key only if fallback_to_platform;
    -- unchecked: never answered either way.
    status                VARCHAR(16)   NOT NULL
        CONSTRAINT ck_provider_credentials_status CHECK (status IN ('valid', 'invalid', 'unchecked')),
    fallback_to_platform  BOOLEAN       NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    checked_at            TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_credentials_user_provider UNIQUE (user_id, provider)
);

COMMENT ON COLUMN provider_credentials.key_encrypted IS
    'KeyVault value (v1:...), context byok:<user_id>:<provider>; the key is never stored in clear';

-- A request that went out on the caller's own key: cost_cents is then Godance's
-- fee alone, and provider_cost_* what the provider bills the caller directly.
ALTER TABLE usage_records
    ADD COLUMN byok BOOLEAN NOT NULL DEFAULT false;
