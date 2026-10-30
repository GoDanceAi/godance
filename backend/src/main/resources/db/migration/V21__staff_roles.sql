-- =====================================================================
-- Godance V21 — staff roles on users.role.
--
-- /v1/admin/** now reads users.role instead of the GODANCE_ADMIN_EMAILS
-- allowlist, so the column decides who can move money and who can grant
-- access. It takes one of four values:
--
--   USER     everyone; no console access
--   SUPPORT  reads the console
--   FINANCE  SUPPORT + credit adjustments
--   ADMIN    everything, including granting and revoking roles
--
-- The CHECK keeps a hand-written UPDATE from storing a value UserRole
-- can't map, which would break every read of that account. Existing rows
-- all hold USER or ADMIN.
-- =====================================================================

ALTER TABLE users
    ADD CONSTRAINT ck_users_role
    CHECK (role IN ('USER', 'SUPPORT', 'FINANCE', 'ADMIN'));

-- Finding the staff (the pricing digest's recipients, the first-admin
-- bootstrap at startup) must not scan every customer.
CREATE INDEX ix_users_staff_role
    ON users (role)
    WHERE role <> 'USER';
