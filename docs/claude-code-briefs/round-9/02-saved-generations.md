# Brief 02 — Saved generations

**Branch:** `feature/saved-generations` (worktree `../gd-saved`) off `main` with round 7 merged
**Reserved migration:** V27
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let a user save a generation so that it is kept past the 90-day deletion,
up to a limit per account, and find saved ones again in History.

## Why now

Every job's files and prompt are deleted 90 days after it was created
(`DataRetentionService.JOB_RETENTION`). That is the right default, and it
means anything a user wants to keep must be downloaded before the date
passes. Saving is the user saying "keep this one".

Saved files are kept at no charge for as long as the account exists, so the
number an account can save is limited.

## Read first

1. This brief and `00-coordination.md` (the saved-generation contract is binding)
2. `backend/CLAUDE.md`
3. Round 7 brief 01 and the code it produced: job outputs, `JobView` with
   `mediaExpiresAt`, and the paged job list
4. `service/DataRetentionService.java` and
   `repository/GenerationJobRepository.java`: `findPurgeCandidates`,
   `redactPurged`, and the account-closure query. Read the retention tests.
5. `service/AccountClosureService.java`
6. `web/JobController.java`, `config/SecurityConfig.java`
7. App: the History and job pages and the Studio result from round 7
   brief 02, `app/src/lib/api.ts`, `app/e2e/mock-api.ts`

## Scope — backend

### Data (V27)

Record when a job was saved. A nullable timestamp on `generation_jobs` is
enough; add an index that serves "this user's saved jobs" and the count.

### Save and un-save

- `PUT /v1/jobs/{id}/saved` and `DELETE /v1/jobs/{id}/saved`, as in the
  contract. Owner only: another user's job answers 404, as job reads do.
- Only a `DONE` job that has not been purged can be saved.
- Both calls are idempotent.
- **The limit.** `godance.retention.saved-limit`, default 500. Saving past
  it answers 409 with `saved_limit_reached`. Count and insert under one
  lock, so two requests at once cannot both pass the limit.
- Audit events: `job.saved` and `job.unsaved`.

### Retention

- The nightly purge skips saved jobs. This is the load-bearing change:
  `findPurgeCandidates` must not return them.
- Un-saving restores the normal rule. A job already older than the
  retention period is purged at the next run.
- **Closing an account still deletes everything,** saved or not. That path
  must not change.
- `JobView.saved` is true for a saved job and its `mediaExpiresAt` is null.
  A job that was un-saved after the retention period has a
  `mediaExpiresAt` in the past until the next run deletes it; the app shows
  it as due for deletion.

### List

`GET /v1/jobs?saved=true` returns the caller's saved jobs, newest first,
and combines with the paging and filters round 7 added.

### Document it (last commit)

Update the jobs section of `marketing/src/pages/docs/index.astro`: the
`saved` field, the two routes, the limit and what saving does to deletion.

## Scope — app

- **Save control** on each History card, on the job page and on a finished
  Studio result. It shows the saved state and toggles it.
- **Saved filter** in History, using `?saved=true`.
- **How many are saved** against the limit, on the saved view.
- **At the limit,** say so and point to the saved view. Do not offer to
  un-save something automatically.
- **Un-saving an old job.** When the job is older than the retention
  period, confirm first: its files will be deleted at the next run.
- **Deletion date.** A saved job shows that it is kept, in place of a date.
- A job that cannot be saved (failed, or already purged) shows no control.

## Optional: saved recipes

Do this only if round 8 brief 03 (the hand-off into the app) is on `main`.
Otherwise leave it out and say so in the final summary.

- A small table of saved recipe slugs per user, with routes to add, remove
  and list them. Not counted against the generation limit: it stores a
  slug, not files.
- A save control on the Studio's "from the gallery" line, and a list of
  saved recipes that opens each one in the Studio.

## Hard rules

- Edits only in `backend/src/**`, migration V27, `application.yml`,
  `app/src/**`, `app/e2e/**` and the one docs page.
- Additions only on the wire.
- Account closure deletes saved jobs exactly as it deletes the rest.
- Nothing here changes what a job costs or how it is charged.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. Read the purge path end to end and write the failing
test first: a saved job older than the retention period survives a purge
run, and its files are still in storage. Then post a 3–5 bullet plan
before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- Backend tests cover:
  - a saved job older than the retention period survives the purge, with
    its files, prompt and outputs intact
  - the same job un-saved is purged at the next run
  - closing the account deletes saved jobs and their files
  - only a `DONE`, unpurged job can be saved; each other state answers 409
  - the limit holds under concurrent requests
  - another user's job answers 404 for both routes
  - save and un-save are idempotent
  - `saved` and `mediaExpiresAt` read back correctly in all three cases
  - the list filter, combined with paging
- App tests cover: saving and un-saving from each of the three places, the
  saved filter, the count against the limit, the message at the limit, the
  confirmation for an old job, and no control on a job that cannot be saved.
- `marketing/` builds.
- A curl transcript of the new routes in the final summary.
- All work committed on `feature/saved-generations`; `main` untouched.

## Out of scope (do not start)

Collections or folders. Sharing a saved generation. A paid tier with a
higher limit. Changing the 90-day period. An admin view of saved storage.
Saving a recipe from the marketing site.
