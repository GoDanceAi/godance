# Brief 02 — Email Sending (replace the [email-stub] logs)

**Branch:** `feature/email-sending` (worktree `../gd-email`) off `main` @ `c9d87b1`
**Reserved migration:** V13 (expect none; V12 belongs to brief 01)
**Baseline:** full suite green on parent (~234 tests; confirm exact count first)

## Mission (one line)

Make Godance actually send its transactional emails — verification links,
spending-cap warnings, auto-recharge failures, and the pricing-proposal
digest — via Resend, with a logging fallback for dev.

## Why now

Email verification currently logs the link
(`AuthService` — grep `[email-stub]`), the 80% spending warning is logged
and never delivered, and pricing proposals have no notification path. Real
users (Stripe live is imminent, P1 #8) can't be asked to read droplet logs.

## Read first

1. This brief + `00-coordination.md`
2. `backend/CLAUDE.md`
3. Call sites to wire (grep `email-stub` and read around each):
   - `service/AuthService.java` — verification URL log (~line 68) + any
     resend-verification path
   - `service/SpendingLimitService.java` — 80% soft-warning log
   - auto-recharge failure path (grep `autorecharge` audit events for where
     failures surface)
   - the pricing cron's digest hook (`service/pricing/` — round 3 left the
     "alert a daily digest" seam; find it before designing)
4. WireMock usage in existing tests (`wiremock` is already a dependency —
   e.g. the Stripe/NOWPayments integration tests) for the API-mock pattern.

## Design

- `service/mail/EmailSender` interface: `send(to, subject, textBody, htmlBody)`.
  Two impls, selected by config:
  - `ResendEmailSender` — POST `https://api.resend.com/emails`, WebClient,
    API key from env. Non-2xx → log + audit `mail.send.failed`; **never
    throw into the calling business flow** (a failed mail must not fail a
    signup or a chat request).
  - `LoggingEmailSender` — current behavior, used when the key is the dummy
    default (dev/CI) — keep the `[email-stub]` log format so local dev
    doesn't change.
- Config (`application.yml`):
  `godance.mail.api-key: ${RESEND_API_KEY:re_dev_dummy_key_do_not_use_for_real_calls}`
  `godance.mail.from: ${EMAIL_FROM:Godance <noreply@godance.ai>}`
  `godance.mail.api-base: ${RESEND_API_BASE:https://api.resend.com}` (test override)
- Add both vars to `backend/deploy/.env.prod.template` (you own it this
  round) with a comment: domain must be verified in Resend first.
- Four templates, inline (subject + text + minimal HTML, no template engine):
  verify-email, spend-warning-80, autorecharge-failed, pricing-proposals-digest.
- Digest = the pricing cron sends ONE email per run listing pending
  proposals (count + model/type/old→new lines) to `GODANCE_ADMIN_EMAILS`
  recipients — reuse `AdminProperties`, don't invent a new list. Skip
  silently when the allowlist is empty (fail-closed, consistent with admin API).

## Hard rules

- Commit to `feature/email-sending` as you go; no push, no deploy.
- Scope: `backend/src/**` (mail package, the four call sites, tests) +
  `.env.prod.template`. Nothing else — no frontend, no `web/` controllers,
  no migrations unless truly forced (then V13).
- Before tests: `cd backend && docker compose up -d`.
- Mail failures are logged + audited, never propagated.

## Opening move

Grep every `email-stub` / warning / digest call site and list them with
line numbers in your build plan (3–5 bullets) before writing code. Confirm
the Resend request shape against their public API docs and pin it in a
WireMock fixture.

## Acceptance criteria

- `mvn clean test` green; new tests: unit (sender selection, payload shape
  via WireMock, non-2xx swallowed + audited) + integration (signup triggers
  exactly one verify mail; 80% cap crossing triggers one warning, not one
  per request; digest sent only when proposals pending AND allowlist
  non-empty).
- `[email-stub]` logging preserved verbatim under dummy-key config.
- All work committed on `feature/email-sending`; `main` untouched.

## Out of scope

Marketing/bulk email, unsubscribe management, HTML template engine, email
change flows, admin UI (brief 01), retry queues (a failed transactional
mail just audits — revisit if volume warrants).
