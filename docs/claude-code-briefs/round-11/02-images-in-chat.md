# Brief 02 — Images in chat

**Branch:** `feature/chat-images` (worktree `../gd-chat-vision`) off `main` with round 10 merged
**Reserved migration:** V32 (capability seed)
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Accept OpenAI's image parts in `/v1/chat/completions` for models that can
see, bill them correctly, and use that to implement brief 01's person check.

## Why now

Three things need a model that can look at an image:
- brief 01's person check
- the Studio's "describe this image as a prompt" (brief 05)
- the people Godance is built for, whose AI assistants routinely send
  images in OpenAI's message shape

Today `ChatCompletionDtos.Message` and `gateway/ChatDtos.ChatMessage` take
`content` as a string, so such a request fails validation.

## Read first

1. This brief and `00-coordination.md` (the chat parts, the file URL form
   and the capability shape are binding; V32 is yours)
2. `backend/CLAUDE.md`
3. `web/dto/ChatCompletionDtos.java`, `gateway/ChatDtos.java`,
   `service/ChatCompletionService.java` (including how the pre-debit is
   estimated before a call and settled after it)
4. `gateway/ProviderAdapter.java` and every chat adapter in
   `gateway/providers/`
5. `gateway/CostCalculator.java`, `service/CreditLedgerService.settle`
6. `service/WebhookDestinations.java` (the public-only client)
7. Brief 01's input service and person-check interface. Code against the
   contract until 01 merges.
8. Each provider's current documentation for image input: accepted
   formats, size limits, how images are counted as tokens, and whether URLs
   or only inline data are accepted

## Scope

### 1. The request shape

- `content` is a string or a list of parts, as in the contract. A string
  behaves exactly as today.
- The domain `ChatMessage` carries a list of parts, so adapters never
  re-parse JSON. A string becomes one text part.
- **Limits:** at most 10 images per request, and each at most 20 MB after
  fetching. The total request stays under the existing body limit.

### 2. Resolving an image

- **A data URL:** decode it and check the type from its bytes.
- **An `https` URL:** fetch through `WebhookDestinations.client()`, with the
  size limit, a 10-second timeout, no redirects, and an image content type.
- **`<api base>/v1/files/{id}/content`:** recognise the API's own base and
  read the file through brief 01's service, owner only. Never fetch it over
  the network.
- **Anything else** answers 400 with `invalid_image_url`.
- **Fetch failures** answer 400 with `image_fetch_failed`, and no ledger row
  is written.

### 3. Adapters

- Map image parts for each adapter whose provider supports image input on
  the catalog's model. Use the provider's documented form: inline base64
  or URL, as each requires.
- Seed `capabilities.inputs.images.max` (V32) for each model where support
  is confirmed in the provider's documentation. Cite the source in the
  migration comment. Where it is not certain, leave it out, because absent
  means no.
- A model without the capability answers 400 with
  `model_does_not_accept_images` before any charge.
- Streaming and non-streaming both work.

### 4. Billing

- **Settlement.** It uses the provider's reported usage, as today. Image
  tokens are inside that, so the bill is correct once the provider reports
  them. Add a test per adapter that a cassette with image tokens settles
  them.
- **The pre-debit estimate** must not under-hold. Add a per-image token
  allowance to the estimate, for each model, from the provider's counting
  rule. Where the rule is uncertain, use the largest documented count.
- Nothing else in pricing changes.

### 5. The person check

Implement brief 01's interface with `godance.inputs.check-model`.

- **The call.** One image and a fixed instruction asking for a strict
  yes/no on whether any person or part of a person (face, body, hands) is
  visible. Use `max_tokens` small and `temperature` 0.
- **Reading the answer.** Accept only an unambiguous answer. Anything else
  counts as "unavailable", which fails closed in brief 01.
- **The call is Godance's own.**
  - The user is not charged.
  - Record its cost on the platform's usage, in the existing pattern for
    internal calls. If there is none, add a usage record with no ledger
    debit, and say so in the summary.
  - Time out after 15 seconds, as "unavailable".
- **The regional allowlist applies.** A check model the region may not use
  is a configuration error at start-up.

### 6. Document it (last commit)

The chat section of `marketing/src/pages/docs/index.astro`. Cover:
- image parts
- the three URL forms
- the limits
- which models accept images (link to `/models`)

## Hard rules

- Edits only in `backend/src/**`, migration V32, and the docs page in the
  last commit.
- A request with string content is byte-for-byte unchanged on the wire to
  every provider. Prove it with the existing cassettes, which must still
  match.
- No user-supplied URL is fetched except through the public-only client.
- No image is logged or audited.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes. Do not mock the adapter interface.

## Opening move

Run the baseline. List, for each chat model in the catalog, whether the
provider documents image input and how it counts image tokens, with links.
Write the failing test first: a request with one data-URL image to a model
seeded with the capability reaches the provider's cassette with the image
in the provider's format. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests. Every existing chat
  cassette still matches.
- **Tests cover:**
  - each URL form
  - a private address refused
  - a redirect refused
  - an oversized image refused
  - another user's file answering 404
  - a model without the capability refused before charge
  - streaming with an image
  - the pre-debit covering the image allowance
  - settlement of image tokens per adapter
  - the person check: yes, no, ambiguous and timeout
- A curl transcript with an image part in the final summary.
- All work committed on `feature/chat-images`; `main` untouched.

## Out of scope (do not start)

- Images in the app's chat page.
- Audio or file parts.
- OpenAI's Responses API.
- Generating images from chat.
- Storing chat images.
