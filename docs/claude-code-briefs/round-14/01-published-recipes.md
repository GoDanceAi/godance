# Brief 01 — Published recipes, profiles and follows

**Branch:** `feature/published-recipes` (worktree `../gd-publish`) off `main` with round 13 merged
**Reserved migration:** V39
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Let an adult user with a profile publish one of their recipes, with chosen
example outputs and a licence. After a moderator approves it, anyone can
see and run it, follow its author, and see what it was made from and how
often it is run.

## Why now

Aspiring builders learn from how others tuned a recipe. Consumers come for
what others made. Round 10 gave every user recipes with versions and
attempts. Round 11 gave them input slots. The gallery has a content standard
and a removal process. Publishing is the next step on those.

The risk is user content in public, so this brief is mostly moderation,
licences and what is and is not exposed.

## Read first

1. This brief and `00-coordination.md` (the publishing contract is binding;
   V39 is yours)
2. `backend/CLAUDE.md` (single-tenant; no plaintext secrets)
3. Round 10's recipes, versions, rendering, origin record and funnel
4. Round 11's input slots and inputs policy
5. `docs/gallery-content-standard.md`, and round 8's removal process
6. `security/AdminRoles`, the admin console controllers, `security/RateLimiter.java`,
   `service/AuditLogService.java`
7. `service/DataRetentionService.java` (job outputs are purged after 90
   days unless saved), `service/AccountClosureService.java`
8. `storage/ObjectStorageClient.java`

## Scope

### Data (V39)

- **`profiles`:** user, unique `handle` (3–30 characters, lowercase letters,
  digits and hyphens, with a reserved-words list), display name, link
  (`https` only), created time.
- **`published_recipes`:**
  - public id, recipe, version, licence, status (`PENDING`, `PUBLIC`,
    `REJECTED`, `REMOVED`)
  - `made_from` (a gallery slug or a published recipe)
  - review fields: moderator, time, reason
  - times
- **`published_recipe_examples`:** copies of the chosen outputs. Copy them
  on approval, so the 90-day purge of the source job never removes a public
  example.
- **`follows`** (follower, followee).
- **`reports`** (reporter, target, reason, status).
- **On `generation_jobs`:** `published_recipe_id`, through round 10's origin
  record.

### Publishing

- **Who.** Only `ADULT` accounts with a verified email and a profile.
- **What is chosen.** The version, a licence from
  `godance.community.licences` (default: `CC-BY-4.0` and `CC0-1.0`), and one
  to four of the user's own `DONE`, unpurged jobs of that recipe as examples.
- **Refused:**
  - a version with an input slot whose role takes people, while round 11's
    `allow-people` is false
  - a recipe whose examples used inputs. Examples must show no user upload.
  
  A later round can relax this with a sample-input flow like the gallery's.
- **What happens.** Publishing answers 202 and creates a `PENDING` record.
- **Unpublishing** makes it `REMOVED` at once, deletes the example copies,
  and keeps the record for audit.
- **Limits:** pending submissions per user per day, and published recipes
  per user (`godance.community.*`).

### Moderation

- **Admin routes** (`@AdminRoles({SUPPORT, ADMIN})`):
  - a queue of `PENDING` items and open reports
  - approve
  - reject with a reason
  - remove
- **Review shows** the template, the variables and their defaults, the
  examples, the author's profile, and prior decisions on this author.
- **Approve** copies the examples to public storage under the media base
  and makes the recipe `PUBLIC`.
- **Reject and remove** record a reason the author can see.
- **Reports.** A report on a `PUBLIC` item puts it back in the queue. The
  item stays public unless a moderator removes it. Rate-limit reports.
- **Audit events:** `community.published`, `community.approved`,
  `community.rejected`, `community.removed`, `community.reported`.

### Public read routes (no auth)

- As in the contract. They return only `PUBLIC` items and only the exposed
  fields.
  - **Never returned:** a user id, an email, the source job ids, or any
    input file.
  - **Allowed:** the example copies' public URLs.
- **`runs` and `runners`** count jobs carrying `published_recipe_id`, total
  and distinct users, excluding the author's own.
- **Cache-friendly.** Set `Cache-Control` for a few minutes, and keep
  queries index-backed and paged.

### Follows and feed

- Follow and unfollow by handle. You cannot follow yourself.
- **The feed** is the newest `PUBLIC` recipes from followed authors, paged.
- **Followers.** A profile shows its follower count, never who the
  followers are.

### Running a published recipe

`recipe: { "published_id": "…", "variables": {…} }` is accepted by the
generation routes, as round 10's `recipe` is. The server renders the
published version. The job records `published_recipe_id`. The runner's own
recipe is unaffected, since they do not own this one.

### Retention and closure

- **Closing an account:**
  - deletes its profile, follows and reports
  - unpublishes its recipes and deletes their example copies
  - removes its follows of others
- **Purge** never touches example copies; unpublishing does.

### Content standard

Add a "Community recipes" section to `docs/gallery-content-standard.md`.
- The same rules as the curated gallery.
- No real people in examples.
- Variables must not invite a real person's name or likeness.
- What gets a recipe rejected or removed.

## Hard rules

- Edits only in `backend/src/**`, migration V39, `application.yml`, and the
  content standard section.
- **Nothing is public before approval.**
- **Public routes expose only the listed fields.** Test that the serialised
  public recipe contains no other keys.
- A rejected or removed item is never returned by a public route.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baseline. Write the failing test that a `PENDING` published recipe
is not returned by `/v1/public/recipes`, and that after approval it is,
with exactly the exposed fields. Then post a 3–5 bullet plan before writing
code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- **Tests cover:**
  - the profile handle rules and reserved words
  - who may publish
  - an examples choice that is not the user's or not `DONE` refused
  - input-slot and input-example refusals
  - the moderation transitions and their audit events
  - examples copied on approval and surviving the source job's purge
  - public routes exposing exactly the fields
  - counts excluding the author
  - follows and feed
  - running a published recipe and recording it
  - reports reopening review
  - account closure
- A curl transcript from profile to publish, approve, public read and run in
  the final summary.
- All work committed on `feature/published-recipes`; `main` untouched.

## Out of scope (do not start)

- Likes and comments (a later round).
- Creator rewards.
- Direct messages.
- Collections.
- Search beyond tag and recency.
- Post-moderation.
- Recipes with input examples.
