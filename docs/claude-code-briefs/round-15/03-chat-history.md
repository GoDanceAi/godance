# 03 — Explicitly saved adult chat history

## Outcome and privacy boundary

Adults can explicitly save a conversation, return to it, continue it, and archive it using the existing conversation API. New chats are ephemeral until saved. Generation remains usable if history persistence fails.

**ADULT-only:** `backend/src/main/java/ai/godance/service/ParentalNotice.java` says the Godance app does not retain children's chat text and reserves legal-text changes for the owner. Users whose `ageStatus` is not `ADULT` keep the current ephemeral chat, with no saved-history controls or conversation API reads/writes. Do not change that notice or expand child persistence in this round.

Read `00-coordination.md`, `session-prompts.md`, `app/src/pages/ChatPage.tsx`, streaming helpers in `app/src/lib/api.ts`, existing chat/auth E2E, backend `web/ConversationController.java`, `web/dto/ConversationDtos.java`, `service/ConversationService.java`, and conversation/retention integration tests.

## Existing API

All conversation routes use the signed-in session; do not use a Godance API key.

| Operation | Contract |
| --- | --- |
| Create | `POST /v1/conversations` with `{ title?: string }`, at most 255 characters; blank becomes New conversation. |
| List | `GET /v1/conversations`: active conversations, updatedAt descending, no pagination. |
| Metadata | `GET /v1/conversations/{id}`: `{ id, title, createdAt, updatedAt, archivedAt }`. |
| Read messages | `GET /v1/conversations/{id}/messages`: createdAt ascending. |
| Append | `POST /v1/conversations/{id}/messages` with `{ role, content, model?, input_tokens?, output_tokens?, cost_cents? }`; returns one saved message with ID/time. |
| Archive | `DELETE /v1/conversations/{id}` → 204; archives now, purges after the existing 30-day retention. It is not immediate erasure. |

Message role is system/user/assistant and content must be nonblank. Missing/foreign conversations return 404. There is no rename, restore, hard-delete, message edit/delete, transactional turn, client message ID or idempotency key API. Do not invent them.

## Scope and behavior

- Keep the current stateless generation route and streaming behavior. Add an adult-only saved-chat list and selection inside ChatPage, with New chat and explicit Save this chat. No change to job History or shared navigation routes.
- Saving an unsaved chat is an explicit adult action with concise retention/disclosure copy. Until that action, no conversation create/append occurs. Do not silently migrate an old in-memory transcript or default all new chats to saved.
- After explicit saving, continuing that selected saved conversation may append subsequent turns; show that saving is on for that conversation. New chat returns to ephemeral mode.
- Serialize create/append operations and retain successful returned IDs. Append only unsaved messages; never append the entire transcript after every response. Keep generation errors distinct from persistence errors.
- A completed answer remains visible if its save fails. Retry save never calls the generation endpoint. Blank assistant placeholders and interrupted/failed partial assistant text are not automatically appended; retain them in the current view with a truthful status, without pretending the DTO has a partial-message state.
- Do not invent `cost_cents`: the SSE contract does not provide authoritative billed cost. Omit unavailable cost/token metadata; preserve actual returned metadata when loading history.
- Archive requires an accurate confirmation explaining archival/retention. Remove it from the active list on confirmed success. The API still permits reads/appends until purge, so the app must treat archived selections as read-only and never continue them.

## Concurrency and ambiguous failures

Create/append POSTs have no idempotency support. Do not automatically retry them. A lost response can mean the write succeeded. Refetch/reconcile before any replay; a definitively rejected write can be retried explicitly after correction. An unknown create outcome must not trigger another automatic create. If reconciliation cannot disambiguate the result, show an unresolved-save state and preserve the current in-memory answer rather than claiming exactly-once persistence.

Fence streaming callbacks and saves by account, conversation and request identity. While streaming, either disable switching/new/archive or explicitly abort and fence late callbacks. An array index alone cannot identify which loaded conversation receives a chunk. Clear account-scoped state and pending callbacks on logout/account change.

Never store transcripts in localStorage/sessionStorage, URLs, console logs, telemetry or screenshots of real accounts. Use synthetic fixtures only.

## Acceptance and evidence

1. Adult ephemeral chat generates normally without any conversation writes. Explicit Save creates the conversation and appends each eligible message once in the normal successful path.
2. Adults can list/load/continue saved chats, see truthful loading/empty/errors/save status, and start a new ephemeral chat. Loaded messages retain their order and role.
3. Child, unknown-age and pending-consent cases expose no history controls and make no conversation API calls; existing chat/consent behavior remains intact.
4. A failed append does not remove the answer or resend the model request. Test a definitive failure and an ambiguous lost response; no automatic duplicate POST or exactly-once claim.
5. Test switching/new/archive during streaming, late callbacks, account changes, reload and foreign/404 IDs. No chunks or saved content cross conversations/accounts.
6. Archived chats leave the active list and cannot receive new turns through the app; copy does not claim immediate erasure or restoration.
7. No blank/partial assistant record is auto-saved; no estimated chat cost is stored as billed cost. Unsupported rename/restore/delete-message controls are absent.
8. Run app lint/build and focused chat/history/auth E2E on desktop/mobile; final full app CI passes. Include synthetic light/dark screenshots of ephemeral, saved and save-failed states.

Opening move: verify the existing API and the parental-notice boundary, then post the plan and persistence state diagram. Write the failed-save-does-not-resend-generation and child-no-history-writes tests before implementation.
