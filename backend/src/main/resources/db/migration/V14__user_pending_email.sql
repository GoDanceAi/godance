-- =====================================================================
-- Godance V14 — an email address waiting to be verified.
--
-- An X.com sign-in shares no email, so the account gets a placeholder
-- address. When the user adds a real one it waits here, unverified, and
-- replaces users.email only once verified from the account's own
-- session. Kept out of users.email so an unverified address can neither
-- block its owner's signup nor have their OAuth sign-in link into it.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN pending_email VARCHAR(320);
