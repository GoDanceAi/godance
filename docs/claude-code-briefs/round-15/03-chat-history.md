# Brief 03 — Chat history

**Branch:** `feature/chat-history` (worktree `../gd-chat-hist`) off `main`; rebase onto brief 01 before merging
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/` on parent; record the counts first

## Mission (one line)

Save every chat in the app as a conversation, and let the user find, reopen,
continue and remove past conversations, through the conversations API that
already exists.

## Why now

`/v1/conversations` was built in an early round: create, list, read,
archive, and read or append messages. The Chat page keeps messages only in
component state, so a reload loses the whole chat and nothing is ever
listed.

## Read first

1. This brief and `00-coordination.md`
2. Backend, read only:
   - `web/ConversationController.java`, `web/dto/ConversationDtos.java`
     (`ConversationView`, `MessageView`, `AppendMessageRequest`)
   - `service/ConversationService.java`: limits, what archive does, and any
     retention
   - whether the conversation stores the model, and if not, how the app
     should remember it
3. App:
   - `app/src/pages/ChatPage.tsx`: how a message is sent, streamed and shown;
     the shared out-of-credit handling
   - `app/e2e/chat.spec.ts`, `app/e2e/sse.ts`, `app/e2e/mock-api.ts`
4. Brief 01, which changes the Chat model list. Rebase onto it.

## Scope

- **Saving:**
  - The first message of a new chat creates a conversation, titled from the
    first line of the message, trimmed to the server's limit.
  - The user's message is appended when it is sent; the assistant's reply is
    appended once its stream ends.
  - A failed or cancelled reply is not appended. Say so in the chat.
- **Never lose the chat because saving failed.** If any save call fails, the
  chat goes on working and a quiet note says this conversation is not being
  saved.
- **History:**
  - A list of conversations, newest first: a side panel on desktop, a drawer
    on mobile.
  - Each entry shows its title and when it was last updated.
  - New chat, open a conversation (its messages load and the user can
    continue), and remove it, with a confirmation that it cannot be undone
    from the app.
- **The URL.** `/chat/:conversationId` opens one, so reload and links work.
  Another user's id, or an archived one, shows a short "not found" and a new
  chat.
- **Model.** Keep the model chosen for a conversation, using the
  conversation if it stores it, or the app otherwise, and restore it on
  open.
- **Big histories.** A long conversation and a long list stay usable. Note in
  the summary what the API returns and whether paging would be needed.

## Hard rules

- Edits only in `ChatPage.tsx`, new chat components, one route in `App.tsx`,
  `app/src/lib/**` and `app/e2e/**`.
- **No backend change.** If the API cannot support something here, stop and
  raise it.
- Streaming, out-of-credit handling and the model picker behave exactly as
  before. The existing chat specs stay green unchanged.
- Messages are rendered as text, as today, never as HTML.

## Opening move

Run the baseline. Read the conversation service and state, with file and
line, what is stored per message and what archive does. Write the failing
e2e test: send a message, reload `/chat/<id>`, and the message and reply are
there. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build`, `npm run test:e2e` green on desktop and
  mobile.
- **e2e covers:**
  - save and reload
  - the list, opening and continuing
  - new chat
  - remove with its confirmation
  - another user's id
  - a failed save keeping the chat working with the note
  - a cancelled reply not saved
  - the model restored on open
- Screenshots of the chat with history on desktop and mobile, in light and
  dark.
- All work committed on `feature/chat-history`; `main` untouched.

## Out of scope (do not start)

- Search across conversations.
- Sharing a conversation.
- Renaming beyond the first-message title, unless the API already supports
  it.
- Images in the chat page.
- Server-side retention changes (operator decision).
