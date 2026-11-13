# Brief 01 — Recipe batches

**Branch:** `feature/recipe-batches` (worktree `../gd-batches`) off `main` with round 11 merged
**Reserved migration:** V36
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Run one recipe version over up to hundreds of rows of variables and inputs.
Each row becomes an ordinary job, charged by the existing path, never
submitted twice, and paused, not failed, when credit or a cap runs out.

## Why now

This is the step the people Godance is built for are working toward: the
recipe that works, run over 50 products or 200 names. Today that means a
script, and most of them cannot write one yet. A batch on the server:
- runs whether or not a tab stays open
- respects spending caps
- leaves one record of what happened to each row

## Read first

1. This brief and `00-coordination.md` (the batch contract is binding; V36 is yours)
2. `backend/CLAUDE.md`
3. Round 10 brief 03's recipe code (versions, rendering, running a recipe)
   and round 11 brief 05's input slots
4. Job creation and settlement:
   - `service/ImageGenerationService.java`, `service/VideoGenerationService.java`
   - round 11 brief 03's edit path
   - `service/GenerationJobSettlement.java`
   - `service/GenerationJobSweepJob.java` (leases and abandoned jobs)
5. Quartz:
   - `domain/ScheduledTrigger.java`
   - `service/AutoRechargeCronJob.java` and `DataRetentionJob.java` as
     patterns
   - how scheduled work is made single-instance
6. `service/SpendingLimitService.java` and the exceptions its checks throw;
   `InsufficientCreditException`
7. `service/WebhookDestinations.java` and how job completion webhooks are
   signed
8. Round 10 brief 01's funnel code (`service/admin/Funnel*`)
9. `service/DataRetentionService.java`, `service/AccountClosureService.java`

## Scope

### Data (V36)

- **`recipe_batches`:**
  - id, owner, `created_by_channel` (`STUDIO` or `API`), recipe, version,
    model, params, `max_concurrency`, `webhook_url`
  - status, `pause_reason`
  - counts, `estimated_total_cents`
  - created, started and completed times
- **`recipe_batch_rows`:** batch, index, status, variables (jsonb), inputs
  (jsonb references), job id, cost, error.
- **On `generation_jobs`:** `batch_row_id`, nullable.

### Create and estimate

- **Validation.** Validate every row with the same code that validates a
  single recipe run. On any failure, answer 400 naming the first failing
  row index and the reason. No batch is written and nothing is charged.
- **The estimate** is the sum of each row's estimate from the shared
  estimate code, which round 11 extended for inputs. `enough` compares it
  with the balance.
- **Limits.** Past `max-rows`, answer `batch_too_large`. Past the
  per-account limit on batches that are not finished, answer 409 with
  `batch_limit_reached`.
- **Inputs.** Rows referencing input files mark those files in use through
  round 11's service, so they are not deleted while the batch waits.

### Running

- **A Quartz job, single-instance, every few seconds,** advances batches
  that are `QUEUED` or `RUNNING`. For each, it keeps up to
  `max_concurrency` rows `RUNNING`.
- **Starting a row.** Write the row as `RUNNING`, with a reserved job id,
  before the provider is called. Then create the job through the normal
  path with:
  - `recipe` and the row's values
  - `channel: BATCH`
  - `batch_row_id`
  - **no per-job webhook**
- **After a crash,** a row that is `RUNNING` with a job id resumes that job.
  It is never resubmitted. Reuse the job sweep's lease logic.
- **Pausing.** A refused pre-debit for credit, or for the user or key cap,
  pauses the batch with that reason. The row goes back to `PENDING`, and no
  ledger row is written.
- **Row outcomes.** When a row's job ends, the row takes its status and
  cost. A failed job is refunded by the existing path, and the row records
  its error. **One failed row does not stop the batch.**
- **The end.** The batch is `DONE` when no row is pending or running. Then
  send the batch webhook, signed like job webhooks, with the counts and the
  results URLs.
- **Cancel** marks pending rows `SKIPPED` and lets running rows finish.
- **Resume** applies to `PAUSED` batches only; otherwise answer
  `batch_not_resumable`.

### Results

- **`results.csv`:** one line per row with:
  - index, status
  - each variable as a column
  - job id, cost, error
  - each output's signed URL, as columns `output_1` to `output_n`

  Stream it rather than building it in memory.
- **`results.zip`:** stream the outputs, named
  `<index>-<first variable value, made safe for a file name>-<n>.<ext>`.
  Refuse with 413 and `results_too_large` past the configured size, and
  point to the CSV.

### Funnel and retention

- **Funnel.** The round 10 funnel gains `first_batch` per cohort and
  source.
  - A batch created through an API key also counts as the user's first API
    job, if it is earlier than any.
  - Jobs with `channel: BATCH` count as API use only when the batch's
    `created_by_channel` is `API`.
- **Retention.** Row variables are redacted when the row's job is purged,
  as the job's prompt is. A batch with no unpurged rows is deleted at the
  next retention run.
- **Closure.** Account closure deletes batches and rows.

### Audit and docs

- **Audit events:** `batch.created`, `batch.paused` (with the reason),
  `batch.cancelled`, `batch.completed`.
- **Document it (last commit):** a "Batches" section in
  `marketing/src/pages/docs/index.astro`:
  - the routes
  - an example with curl
  - how pausing and resuming work
  - what results contain and how long links last

  Hand 04 two lines for `llms.txt` in your summary.

## Hard rules

- Edits only in `backend/src/**`, migration V36, `application.yml`, and the
  docs page in the last commit.
- **A batch never charges anything itself.** Every cent goes through the
  normal job pre-debit and settlement. Pin it with a test: the sum of the
  rows' ledger entries equals the batch's `spent_cents`, and there is
  nothing else.
- **No row is submitted twice,** including after a crash between reserving
  and submitting. Test it.
- Single-instance scheduling, as the existing cron jobs do.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes for providers.

## Opening move

Run the baseline. Read the job sweep's lease and recovery code, and state,
with file and line, how an abandoned job is recovered. Write the failing
test first: a three-row batch with `max_concurrency` 1 produces three jobs,
in order, with the ledger summing to `spent_cents`. Then post a 3–5 bullet
plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- **Tests cover:**
  - estimate and create, including a bad row at index 7 refused with
    nothing written
  - concurrency respected
  - pause on credit and on a key cap, then resume
  - cancel
  - a failed row refunded while the batch carries on
  - recovery after a crash at each step
  - the webhook signed and sent once
  - the CSV and zip contents, and the zip size refusal
  - the funnel's `first_batch`
  - retention and closure
  - inputs held in use until the batch ends
- A curl transcript of a whole batch, from estimate to results, in the
  final summary.
- All work committed on `feature/recipe-batches`; `main` untouched.

## Out of scope (do not start)

- OpenAI's `/v1/batches` shape.
- Scheduling batches for later.
- Batches across several recipes.
- A discount for batches.
- Per-job webhooks inside a batch.
- The app (02).
