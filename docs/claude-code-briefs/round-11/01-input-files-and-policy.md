# Brief 01 — Input files and policy (ships disabled)

**Branch:** `feature/input-files` (worktree `../gd-inputs`) off `main` with round 10 merged
**Reserved migration:** V31
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let an adult account that has accepted the input terms upload images to
use in generations, store them privately with their metadata stripped, keep
them only as long as something uses them, and refuse what the policy does
not allow. Everything is off by default.

## Why now

Briefs 03, 04 and 05 all need somewhere to put an uploaded image, a way to
point at it, and one place that decides what is allowed. Uploads are where
the risk sits:
- photos of real people
- photos of children
- location data in a phone photo
- files kept longer than they need to be

So this brief is mostly policy and retention. The upload route itself is
small.

## Read first

1. This brief and `00-coordination.md` (the file contract, the two ways to
   point at an input, and `JobView.inputs` are binding; V31 is yours)
2. `backend/CLAUDE.md`
3. `storage/ObjectStorageClient.java`, `storage/S3ObjectStorageClient.java`,
   and `backend/deploy/encrypt-at-rest.md`
4. Retention and closure:
   - `service/DataRetentionService.java`
   - `service/AccountClosureService.java`
   - `repository/GenerationJobRepository.java` (`findPurgeCandidates`,
     `redactPurged`)
   - round 9's saved generations
5. `domain/User.java` (`ageStatus`), `domain/AgeStatus.java`,
   `service/ParentalConsentService.java`
6. `security/RateLimiter.java`, `config/SecurityConfig.java`,
   `service/AuditLogService.java`
7. `service/WebhookDestinations.java`: the public-only client that every
   user-supplied URL must go through
8. The job view from rounds 7 and 10 (`web/dto/ImageDtos.java`, `JobView`)

## Scope — backend

### Data (V31)

- **`input_files`:**
  - id, owner, storage key, content type, bytes, width, height
  - `contains_person` (nullable boolean)
  - created time, `expires_at` (nullable)
  - `deleted_at`
- **`generation_job_inputs`:** job, role, position, and either `file_id` or
  (`source_job_id`, `source_index`). This is the one table that says which
  job uses which input.
- **On `users`:** `inputs_terms_version` and `inputs_terms_accepted_at`.

### Settings (`godance.inputs.*`)

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | false | Nothing is accepted anywhere while false |
| `allow-people` | false | Refuse images that contain a person |
| `check-model` | empty | Vision chat model for the person check |
| `terms-version` | 1 | Accepting an older version does not count |
| `unused-ttl-hours` | 24 | Lifetime of a file nothing uses |
| `max-bytes` | 20 MB | Per file |
| `max-megapixels` | 40 | Per image |

### Upload: `POST /v1/files`

- **Order of checks**, each with its contract error code:
  1. enabled
  2. adult account
  3. current terms accepted
  4. size limits
  5. content type, read from the file's own bytes
  6. dimensions
  7. person check
- **Strip metadata** without re-encoding:
  - JPEG: APP1 (EXIF and XMP) and APP13 segments
  - PNG: `eXIf`, `tEXt`, `zTXt` and `iTXt` chunks
  - WebP: `EXIF` and `XMP ` chunks
  
  Keep the colour profile. Test each format with a file that carries GPS
  data.
- **The person check.**
  - Define an interface, for example `InputPersonCheck`, returning
    `contains_person` or "unavailable".
  - Brief 02 supplies the implementation backed by `check-model`. Until then,
    nothing implements it.
  - With `allow-people` false, "unavailable" refuses the upload with
    `input_check_unavailable`. With `allow-people` true, the check is skipped
    and `contains_person` is null.
- **Storage.** Store under a key that cannot be guessed, separate from job
  outputs. Set `expires_at` to now plus `unused-ttl-hours`.
- **Rate limiting:** per account and per address, with the existing limiter.
- **Audit events:**
  - `input.uploaded`: size and type, never the image
  - `input.refused`: the reason
  - `inputs.terms_accepted`

### Read, list, delete

- Owner only. Another user's file answers 404.
- `content` redirects to a short-lived signed URL.
- Delete marks the file deleted and removes the object. A job that used it
  keeps its record, and its `inputs[].url` becomes null.

### Pointing at an input

One service method resolves a reference to bytes and records the use. Briefs
03, 04 and 05 call it and add no tables.

- **Resolving** checks:
  - ownership
  - that the file exists and is not deleted
  - for a `job_output`, that the job is the caller's, is `DONE`, is not
    purged, and has that index
- **Recording** writes `generation_job_inputs` in the transaction that
  creates the job.
- **A refused reference** writes no job and no ledger row.

### Retention

- **Unused files.** The nightly retention run deletes files whose
  `expires_at` has passed and that nothing uses.
- **Files in use.** A file in use has `expires_at` null while any
  unpurged job or any recipe (brief 05) uses it. It becomes unused again
  when the last of these is purged or deleted, and then gets a fresh
  `unused-ttl-hours`.
- **Purge.** Purging a job clears its `generation_job_inputs` rows
  (`JobView.inputs` becomes empty) and re-evaluates those files.
- **Closing an account** deletes all its files and rows, used or not.
- Add a test for each of these, including a file used by a saved job (round
  9), which must survive the 90-day purge.

### `JobView.inputs`

As in the contract, with URLs signed when the job is read.

### Terms: `POST /v1/inputs/terms`

- Session only. It records the version the user accepted.
- `GET /v1/auth/me` gains `inputs`:
  `{ "enabled": true, "terms_version": 1, "accepted": false, "allow_people": false }`.
  The app reads this to know what to show.

### Document it (last commit)

A "Files and inputs" section in `marketing/src/pages/docs/index.astro`.
Cover:
- the limits
- the two ways to point at an input
- retention
- what is refused and why

## Scope — app

- **Settings page:** an "Inputs" section, shown only when `inputs.enabled`
  is true. It contains the terms text, read from a constant the operator
  edits, with the version, and an Accept button.
- **Copy in the app:**
  - When accepted, one line saying when.
  - For a non-adult account, one line saying inputs are not available, with
    no control.

## Hard rules

- Edits only in `backend/src/**`, migration V31, `application.yml`,
  `app/src/**` (the Settings section), `app/e2e/**`, and the docs page in
  the last commit.
- **Fail closed** everywhere. With `enabled` false, behaviour for every
  existing route is identical to the parent commit.
- No image is ever logged, put in an audit entry, or sent anywhere except
  the check model and, later, the provider the user chose.
- The regional allowlist applies to the check model like any other call.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. Read the purge and closure paths end to end. Write the
failing tests first:
- an upload with `enabled` false answers `inputs_disabled`
- a JPEG with GPS EXIF is stored without it

Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- **Backend tests cover:**
  - every refusal in the check order, with its code
  - metadata stripped for each format, with the image still decodable
  - the person check:
    - "unavailable" refusing with `allow-people` false
    - skipped with it true
    - a person refused
  - owner-only read and delete
  - a reference to another user's file or job answering 404 with no ledger
    row
  - retention: unused expiry, in-use survival, release after purge, a saved
    job's input surviving 90 days
  - account closure
  - terms version: an older acceptance does not count
- App tests cover the Settings section hidden, shown, accepted, and the
  non-adult case.
- A curl transcript of each route in the final summary.
- All work committed on `feature/input-files`; `main` untouched.

## Out of scope (do not start)

- Video or audio uploads.
- Using inputs in generation (03, 04).
- The person-check implementation (02).
- Face matching or identity checks.
- Sharing files between users.
- Inputs on the marketing site.
