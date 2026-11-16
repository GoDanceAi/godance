# Godance templates for n8n

Two n8n workflows that turn the rows of a Google Sheet into Godance jobs, one
row at a time, and write each result back to its row. No code, no webhook and
no public URL: the workflow polls each job until it is finished.

| Template | What a row becomes |
|---|---|
| [`godance-sheet-to-images.json`](./godance-sheet-to-images.json) | An image job: `POST /v1/images/generations` |
| [`godance-sheet-to-videos.json`](./godance-sheet-to-videos.json) | A video job: `POST /v1/videos/generations` |

Both use n8n's built-in nodes only (Manual Trigger, Google Sheets, Loop Over
Items, HTTP Request, If, Edit Fields, Wait and sticky notes): no community
node and no Code node. Both call `https://api.godance.ai/v1` and nothing else.

## Every row costs credit: estimate first

A row is a paid job. A sheet of 200 rows is 200 paid jobs, started by one
click. Before you run a sheet:

1. **Price one row.** `POST /v1/estimates` answers what a request would
   reserve, charges nothing and creates no job. Multiply by your rows.

   ```bash
   # One image
   curl https://api.godance.ai/v1/estimates \
     -H "Authorization: Bearer $GODANCE_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{ "model": "gpt-image-2", "n": 1, "size": "1024x1024" }'

   # One 5-second clip
   curl https://api.godance.ai/v1/estimates \
     -H "Authorization: Bearer $GODANCE_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{ "model": "seedance-2.0", "seconds": 5 }'
   ```

   The figure is `estimated_cost_cents`, in whole US cents.

2. **Give the workflow a key of its own, with a daily cap.** A key can carry
   `daily_cap_cents`: the most it may spend in a UTC day. A request that
   would take it past that answers `402`, until the next day, and the rows
   left over fail without being charged. Set the cap when you create the key
   (`POST /v1/keys`) or later (`PATCH /v1/keys/{id}`).

3. **Try three rows first.** Leave the rest of the sheet without a prompt,
   or put any word in their `status` cell: only rows with a prompt and an
   empty status are run.

## The sheet

The first row holds the column names, exactly as written here. You fill in
the first group; the workflow writes the second. A column of the second
group that is missing is added to the sheet the first time it is written.

**Images**

| Column | You fill it in | What it holds |
|---|---|---|
| `prompt` | yes | What to make. A row without a prompt is skipped. |
| `model` | yes | An image model, for example `gpt-image-2`. `GET /v1/models` lists them. |
| `size` | optional | For example `1024x1024`. Empty: the model's default. |
| `status` | leave empty | Written by the workflow: `RUNNING`, then `DONE`, `FAILED` or `TIMEOUT`. A row whose status is not empty is skipped. |
| `job_id` | no | The Godance job, written as soon as it is accepted. |
| `output_url` | no | A link to the first image of the job. |
| `cost_cents` | no | What the job was charged, in whole US cents. |
| `error_message` | no | Why a row failed. Empty when it did not. |

**Videos** — the same, and two more that you fill in:

| Column | You fill it in | What it holds |
|---|---|---|
| `seconds` | optional | The length of the clip, a whole number. Empty: the model's standard length. It sets the price. |
| `first_frame_file_id` | optional | The id of an image you uploaded with `POST /v1/files`: the clip starts from it. It is sent as `"first_frame": { "file_id": … }`. Leave it empty for a clip from text alone. |

`first_frame_file_id` needs input files, which are being rolled out: until
they are switched on, a row that carries one fails with `inputs_disabled` and
nothing is charged. A row without it is unaffected. Only some video models
take a first frame; the [docs](https://godance.ai/docs#video-inputs) list
them.

`output_url` is a signed link that stops working after a while. Download the
file, or read the job again (`GET /v1/jobs/{job_id}`) for a fresh link. Files
are kept for 90 days unless you save the job.

## Set it up

1. **The key, as a credential.** In n8n, create a credential of the type
   **Header Auth** and name it **Godance API**:
   - Name: `Authorization`
   - Value: `Bearer ` followed by your Godance API key

   The templates name this credential and hold nothing of it: your key is
   stored by n8n, never in the workflow, so the workflow is safe to export
   and share.
2. **Import.** In a new workflow, open the menu and choose **Import from
   File**, then pick the template. A credential named Godance API is picked
   up by its name; if n8n shows the two HTTP Request nodes without one,
   choose it in **Create the image job** (or **Create the video job**) and
   **Get the job**.
3. **The sheet.** Open **Get the rows** and **Update the row**. In each,
   choose your Google account, the spreadsheet and the sheet. In **Update
   the row**, the mapping stays on **Map Automatically** and the column to
   match on stays `row_number`.
4. **Run it.** Click **Execute workflow**.

## What each node does

| Node | What it does |
|---|---|
| When clicking ‘Execute workflow’ | Starts the run by hand. Swap it for a Schedule Trigger to run on a timer. |
| Get the rows | Reads the whole sheet. Each row comes with its `row_number`. |
| Not run yet? | Keeps the rows that have a prompt and an empty status. |
| Loop Over Items | Hands on one row at a time. |
| Create the image job / Create the video job | Sends the row to Godance. It answers with a `job_id` at once. This is the node that spends credit, and it is never retried. |
| Job accepted? | A `job_id` means yes. Anything else is a refusal: no credit, a cap reached, an unknown model. |
| Row is running / Row was refused | Shape what is written to the row: the job id, or the reason for the refusal. |
| Update the row | Writes to the row, found by its `row_number`. Every outcome goes through this one node. |
| Still running? | A row that has just started goes on to be checked; any other goes back to the loop for the next row. |
| Start checking | Sets the first wait, the longest wait and the number of checks. Edit them here. |
| Wait before checking | Waits. |
| Get the job | Reads the job: `GET /v1/jobs/{job_id}`. |
| Job done? / Job failed? / Checks left? | Decide what happens next: write the result, write the failure, wait again, or give up waiting. |
| Wait longer | Doubles the wait, up to the longest wait, and counts the check. |
| Row is done / Row failed / Row timed out | Shape what is written to the row. |

## Waiting, and its limits

| | First wait | Longest wait | Checks | Gives up after about |
|---|---|---|---|---|
| Images | 5 s | 60 s | 20 | 17 minutes |
| Videos | 15 s | 60 s | 40 | 39 minutes |

Each wait is twice the one before, up to the longest. Change the three
numbers in **Start checking**.

- **A row that fails does not stop the run.** Its `status` becomes `FAILED`,
  the reason goes to `error_message`, and the next row is taken. A failed
  job is refunded by Godance.
- **`TIMEOUT` is not a failure of the job.** The workflow stopped waiting;
  the job carries on, and is charged if it finishes. Find it by its `job_id`
  in your Godance history, or read it with `GET /v1/jobs/{job_id}`.
- **`402` on one row means `402` on the rest.** The balance or the key's cap
  is used up. The remaining rows are marked `FAILED` and cost nothing. Top
  up or raise the cap, clear their `status`, and run again.
- **To run a row again, clear its `status` cell.** A row is never sent
  twice in one run. If n8n itself stops between sending a row and writing
  its `job_id`, that row still has an empty status: look at your Godance
  history before you run the sheet again.
- **Rows run one at a time**, so a long sheet is a long execution. If your
  n8n limits how long an execution may run, run the sheet in parts: rows
  already done are skipped.
- **Google Sheets has its own limits** on reads and writes per minute. The
  workflow makes two writes for each row that starts, and retries a write
  that Google refuses.

## For maintainers

`marketing/scripts/n8n/n8n-templates.test.js` checks both templates on every
`npm test` in `marketing/`: they parse as n8n exports, use only the node
types listed there, hold no credential value, point only at
`https://api.godance.ai/v1`, never send a row twice, and write every outcome
to the sheet. After editing a template in n8n, export it, remove the `id`,
`versionId` and `meta` that the export adds, set the credential's `id` back
to `null`, clear the spreadsheet and sheet in both Google Sheets nodes, and
run the check.

### How the templates were tried

With n8n 2.47.7, in Docker, in November 2026:

- **Imported.** Both files import with `n8n import:workflow`, and their two
  HTTP Request nodes pick up a credential named Godance API by its name.
- **Held to n8n's node definitions.** Every parameter of every node is one
  that n8n's own definition of that node type has at the template's
  `typeVersion`, with nothing required left out. The two Google Sheets nodes
  were checked as they are once a spreadsheet and a sheet are chosen.
- **Run against a mock of the API**, with the two Google Sheets nodes
  replaced by stand-ins that serve rows and record updates, and with short
  waits. One run covered a job that finishes, a model that does not exist
  (404), a spending cap reached (402), a job that fails, a job that never
  finishes, an error while reading a job, a row already done and a row
  without a prompt; another, an API that cannot be reached. Each row was
  sent once, each outcome was written to its row, and the run carried on to
  the last row.

Not tried: the Google Sheets nodes against a real spreadsheet, and the live
API. Run three rows of your own before a long sheet.

The nodes are pinned at these type versions: HTTP Request 4.2, Google Sheets
4.5, If 2.2, Edit Fields 3.4, Wait 1.1, Loop Over Items 3. Each is older
than the newest that n8n 2.47.7 has, so that an n8n somewhat behind can load
them too; only 2.47.7 was tried.
