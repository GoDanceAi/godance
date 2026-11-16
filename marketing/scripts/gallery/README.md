# Gallery import

Turns entries from licensed open-source prompt lists into gallery recipes:
credited to their authors, in English and Chinese, each with an output that
Godance generated at a recorded cost, and each approved by a person.

```bash
cd marketing
node scripts/gallery/cli.js plan
```

`plan` runs the whole pipeline as a dry run and prints the count at each
stage and the projected spend. It writes nothing and makes no paid call.

## Two rules that hold everywhere

**Every command is a dry run unless `--apply` is given.** Without it a
command reads, counts and reports, and changes nothing.

**A command that spends money also needs `--max-spend-cents`.** The cap
covers everything this work folder has ever spent, not one run: running
again with the same cap can never spend more in total. Before each paid
call the tool holds the call's projected cost; when what has been spent,
what is in flight and the next call would pass the cap, that call is not
made and nothing more is started.

The API key is read from `GODANCE_API_KEY` and written nowhere. It is sent
to the API (`GODANCE_API_BASE`, default `https://api.godance.ai`) and to no
other host, and it is removed from anything printed or saved.

## The stages, in order

| Command | What it does | Paid |
|---|---|---|
| `fetch` | Reads each list at its pinned commit, after checking its licence file. Drops posts already seen and posts on the removal list. | no |
| `screen` | Sets aside what cannot be used, with a reason for each. `--classify` also has a text model read what is left against the content standard. | only with `--classify` |
| `translate` | Completes each prompt in English and Chinese, keeps its blanks as a template, and gives each candidate its slug. | when a list supplies one language only |
| `sample` | For each candidate that needs an input image: proposes its input slots and generates a sample input for each, on Godance. Only after `screen --inputs`. | yes |
| `generate` | Runs each candidate on Godance, downloads the output at once, and records the cost. | yes |
| `review` | Builds the review sheet. | no |
| `publish` | Writes a recipe for each entry the operator approved. | no |

Also: `rerun` adds runs on other models to recipes already published (see
[rerun](#rerun)), `remove <slug>` takes a recipe down, `retemplate <slug>`
gives a published recipe its template, and `status` shows what each stage
has done and what has been spent.

A first batch, once the operator has given the go-ahead and a cap:

```bash
node scripts/gallery/cli.js fetch --apply
node scripts/gallery/cli.js screen --apply
node scripts/gallery/cli.js translate --apply --max-spend-cents 6000
node scripts/gallery/cli.js generate  --apply --max-spend-cents 6000
node scripts/gallery/cli.js review --apply
# the operator works through .gallery-work/review/index.html
node scripts/gallery/cli.js publish --apply
```

Each stage can be run again and picks up where it stopped. `--source <id>`
limits a command to one list.

A batch that takes input recipes adds two things: `--inputs objects` on
`screen`, and the `sample` stage between `translate` and `generate`:

```bash
node scripts/gallery/cli.js screen --inputs objects --apply
node scripts/gallery/cli.js translate --apply --max-spend-cents 6000
node scripts/gallery/cli.js sample    --apply --max-spend-cents 6000
node scripts/gallery/cli.js generate  --apply --max-spend-cents 6000
```

### screen

Screening only narrows. Nothing it does approves an entry: what it does not
set aside goes on to review, no more. The fixed rules set an entry aside
when it is broken in the list, needs an input image, video or audio, is in
a language other than English or Chinese, has a blank with no default, is
too long, is written for a longer clip than `--max-video-seconds` (default
10), or mentions a term in `config/screen-terms.json`.

`--inputs` decides what happens to an entry that needs an input image:

- **`none`** (the default) sets every one aside, as before.
- **`objects`** lets through the entries whose input is a product or an
  object. An entry that mentions a person (the words under `input_people` in
  `config/screen-terms.json`) stays set aside, with the word that marked it
  as its reason. The rule is broad on purpose: a doubtful entry is set
  aside.
- **`people`** lets person entries through too. It is refused, before
  anything is read, unless `docs/gallery-content-standard.md` carries the
  line `**Person samples:** allowed`. Only the operator changes that line,
  and raises the standard's version with it.

An entry that needs a video or audio input is always set aside.

### sample

A recipe with inputs shows a sample input beside its output, and a visitor
swaps in their own. The sample is generated on Godance. It is never an
image from a source list: the lists' images are not read at all.

For each candidate that passed `screen --inputs`:

1. A Godance text model reads the prompt and proposes its slots: each
   slot's name, its role (`image` for an image recipe; `first_frame`,
   `last_frame` or `reference_image` for a video), a hint in English and
   Chinese, and a prompt for the slot's sample. The prompt is handed over
   as text to read, not as instructions.
2. The proposal is checked before any picture is paid for. An entry let
   through as an object cannot get a slot, a hint or a sample that mentions
   a person, and what the content standard's terms set aside in a prompt
   they set aside in a slot too. A refused proposal fails the entry, with
   its reason.
3. One sample per slot is generated on the image model `model-map.json`
   names under `sample.model` (the first image model otherwise), in the
   shape of the run it feeds, and saved as `<slug>/input-<n>.webp`.

It is a paid stage like `generate`: a dry run unless `--apply`, nothing
spent without `--max-spend-cents`, the same budget guard, and the same
resume rules. A sample job in flight holds its money and is picked up by
its id on the next run; `--retry-failed` and `--retry-uncertain` work as
they do for `generate`. The dry run prints what it would do and what it
would cost.

The operator edits slot names and hints in the review sheet.

### translate and templates

Some lists mark a blank in a prompt as
`{argument name="lantern color" default="warm red"}`. The recipe keeps it:

- `prompt.template` holds the prompt in both languages with each blank as a
  `{{lantern color}}` marker, and `prompt.variables` holds each blank's
  default, with a `zh_default` where the Chinese uses other words.
- Filled with the defaults, each template is the prompt in its language,
  exactly. The run is made with that filled prompt, so the output is of the
  prompt as its author wrote it.
- A name the template rules refuse is replaced by one they accept, and a
  name the list uses with two defaults becomes two variables.
  `source.changes` lists each change, after "placeholders rewritten as
  {{name}}".

A machine translation has to bring every marker back unchanged. When one is
lost or changed, the request is made once more. If that fails too, the
filled prompt is translated as before, the recipe has no template
(`source.changes` then says "placeholders resolved"), and the review sheet's
notes say why. Defaults that are words are translated; numbers, sizes and
brand names are left as they are.

The rules for a template are in `src/lib/gallery-template.js`, and the
schema checks every recipe against them.

### generate

- `--images` and `--videos` cap how many runs are made in total (default
  120 and 30). Runs are spread across categories.
- Images are made one at a time. A video always states its seconds: the
  length its prompt names, or `--video-seconds` (default 5).
- At most two jobs are in flight. A `429` is retried after a wait.
- Images are saved as WebP with a 480-pixel thumbnail. A video gets a
  poster when `ffmpeg` is installed; without it the tool says so and leaves
  the poster out.

**Input recipes.** Before anything is submitted, the tool uploads one small
image to check that the account can use inputs. If it cannot, the run stops
with the reason and what to do about it (inputs switched off on the API, an
account that is not an adult's, or the input terms not accepted), and
nothing is submitted or spent: not even the recipes that need no input. An
upload is free. Then, for each input recipe, the samples are uploaded
through `POST /v1/files`; an image recipe runs through
`POST /v1/images/edits` with the file ids, and a video carries its
`first_frame`, `last_frame` and `reference_images`. A candidate whose
sample is not finished waits for `sample`.

**No double payment.** A run's record is saved before its request is sent
and again, with the job id, the moment the API accepts it. A later run
resumes that job by its id.

If a request was sent and no clear answer came back, the run is reported as
**uncertain**: the job may exist. It is never sent again by itself and its
money stays held. Check `GET /v1/jobs` for it. If the job is not there,
`--retry-uncertain` sends it again; if it is, that would pay twice.

`--retry-failed` runs failed entries again. A failed job is refunded by the
API, so it has cost nothing.

### review

`review --apply` writes one static page, `.gallery-work/review/index.html`.
Open it in a browser. For each candidate it shows the output, the prompt in
both languages, the source and author, the screening notes and the cost.
Mark each one approved or rejected; a rejection needs a reason. Apply the
content standard to the output as well as to the prompt.

For an input recipe the sheet shows each sample before the output, with its
slot, the prompt that made the sample, and both costs. The standard applies
to each sample as well. Slot names and hints can be edited there; the edits
travel in `decisions.json` and are what `publish` writes.

**Save decisions.json** downloads the decisions. Put the file in
`.gallery-work/review/`. The page also keeps the choices in the browser, so
a review can be done in more than one sitting.

Only the operator approves. The tool reads `decisions.json`; no command
writes it.

### publish

Writes one file per approved recipe to `src/content/gallery/`, each checked
first with the schema module (`src/lib/gallery-schema.js`, the only
definition of the format). A recipe the schema rejects, or whose slug is
taken, is refused and named. Afterwards the whole folder is checked, as
`npm run validate` does.

Media is never committed. `publish` lists the files to upload in
`.gallery-work/manifest.json`; uploading them to public storage is the
operator's step. The files are in `.gallery-work/media/`, in the layout the
recipes refer to.

### rerun

`rerun` adds a run on other models to recipes that are already published,
so a recipe can show the same prompt on several models, with what each run
cost. The comparison pages are built from these runs.

```bash
node scripts/gallery/cli.js rerun --models seedream-5.0,nano-banana --slugs harbour-card,tea-label --max-spend-cents 1000
node scripts/gallery/cli.js rerun --models flux-2-pro --tag poster --max-spend-cents 1000 --apply
node scripts/gallery/cli.js rerun --china-hosted --all --max-spend-cents 1000
```

- **What it runs.** One job per selected recipe (`--slugs a,b`, `--tag
  <tag>` or `--all`) and model (`--models`): the recipe's prompt, in the
  language its first run sent, and for a recipe with inputs its sample
  inputs, uploaded as `generate` does. A recipe with a template is run with
  its template filled with the defaults, which is the prompt itself. A
  recipe that already has a run on a model gets no second one. A model the
  catalogue does not list, that makes the other kind of output, or that
  cannot take the recipe's input slots is reported with the reason, and
  nothing is spent on it.
- **Parameters.** The first run's are carried over only where the catalogue
  (`GET /v1/models`) says the model takes them: a size it lists, or its
  default size; a count up to its `max_n` (1 when it states none); a clip
  length it lists, or its default. Anything else is left out, and the plan
  says what and why. A video always states its seconds: the model's default
  when the first run's cannot be kept. `config/rerun-params.json` holds the
  exceptions, each one confirmed by the provider's own documentation.
- **`--china-hosted`** adds, to each selected recipe with no run on a
  China-hosted model, a run on the cheapest one of its kind (the price of an
  image, or of a second of video, with the fee), from
  `config/china-hosted.json`. When two cost the same, the one listed first
  in that file is chosen.
- **Money.** As `generate`: a dry run unless `--apply`, the same budget over
  everything the work folder has spent and holds, at most two jobs in
  flight, and the same rules against paying twice (`--retry-failed`,
  `--retry-uncertain`). `--max-spend-cents` is needed even for a dry run,
  which says how much of the plan the cap leaves room for. Outputs are saved
  as `<slug>/<model>-<n>`, so no file of a recipe's other runs is touched.
- **Review.** `review --apply` shows each new run beside its recipe's first
  run, with both costs and what the new run left out. It works in a work
  folder that imported nothing. Each new run is approved or rejected on its
  own; its decision is saved under `runs` in `decisions.json`, by
  `<slug>@<model>`.
- **Publish.** `publish --apply` adds each approved run at the end of its
  recipe's runs, with the day it was approved (`approved_on`). Nothing else
  in the recipe changes: not its other runs, not their order, not its
  review. A run is refused when its recipe's prompt or samples are no longer
  the ones it was made with. Its media goes into the manifest, as for a new
  recipe.

### retemplate

`retemplate <slug>`, or `retemplate --all`, gives a recipe that was
published before templates its template. It reads the entry the recipe came
from, in its list at the commit the recipe pins, and builds the template as
`translate` does.

It writes only when the template, filled with its defaults, is the published
prompt exactly, in English and in Chinese. Outputs, costs and the review
stay as they are, and **no paid call is made**: the language the list does
not supply is not translated again. Its template is made from the published
text, which works when each default stands in it as written (a number, a
size, a brand name). A recipe whose default was translated is left as it
is, and the report says so for each recipe:

```
  + harbour-card: would add a template, 2 variables (ratio, brand)
  = harbour-poster: left as it is: the Chinese prompt does not contain the
    default of {{boat colour}} as written ("red"): it was translated, and a
    Chinese template cannot be made without translating again
```

Like every command it is a dry run unless `--apply` is given.

### remove

```bash
node scripts/gallery/cli.js remove <slug> --apply --reason "the author asked"
```

Deletes the recipe, adds its original post to `config/removal-list.json` so
a later import cannot bring it back, and lists its media for deletion in
the manifest. Commit the deleted recipe and the removal list together.

## What the operator edits

All in `config/`, all committed:

- **`sources.json`** — the lists, each pinned to a full commit, with the
  licence it is expected to carry. To read a list at a newer commit, change
  its `commit` and run `plan`. A list whose licence file is missing, or is
  not the configured licence, is refused and nothing is read from it.
- **`model-map.json`** — which model to run a list's prompts on when
  Godance does not serve the one it was written for; the text models used
  to classify and translate (and, under `text.sample`, to propose slots);
  the image model for sample inputs (`sample.model`); the sizes requested
  for each frame shape.
- **`screen-terms.json`** — the terms that set an entry aside, by rule of
  the content standard, and under `input_people` the words that mark an
  input as being of a person. A starting list, meant to be extended.
- **`removal-list.json`** — written by `remove`.
- **`china-hosted.json`** — the models `rerun --china-hosted` chooses from:
  each catalogue model whose provider is in the Alicloud region's
  allowlist. A test fails when it and the backend's allowlist and catalogue
  disagree.
- **`rerun-params.json`** — exceptions to how `rerun` carries parameters
  over: values a model takes beyond what the catalogue lists, and values to
  send instead of others. Add one only when the provider's documentation
  confirms it.

## The work folder

`marketing/.gallery-work/` is git-ignored. Deleting it loses the record of
what was spent and of jobs still running, so keep it until a batch is
published.

| Path | Holds |
|---|---|
| `sources/` | the lists' text files at their pinned commits |
| `entries.json`, `screened.json`, `translated.json` | the state of each stage |
| `samples.json` | each input recipe's slots, and its samples with their job ids |
| `runs.json` | one record per run, with its job id |
| `reruns.json` | one record per published recipe and model `rerun` ran, with its job id |
| `ledger.json` | every cent spent, one line per paid call |
| `media/` | generated images and videos, and sample inputs |
| `review/index.html`, `review/decisions.json` | the sheet, and the operator's decisions |
| `manifest.json` | media to upload, and to delete |

## Things to know

- **Only text is read from a list.** The lists' own example images and
  videos are never requested; the reader refuses any path that is not a
  text file.
- **A projection is not a bill.** Projected spend comes from the model
  catalogue (`GET /v1/models`, free and public) plus the platform fee, or
  from seed prices with `--offline`. What a recipe records is its job's
  `actualCostCents`.
- **Text-model costs are estimates.** A chat reply does not state its
  price, so the ledger records its token usage at the catalogue price.
- **The video list changes daily.** Its README carries only the newest
  hundred prompts, so each pinned commit yields a different set.
- **Changes are recorded.** Whatever the tool changes in a prompt is listed
  in the recipe's `source.changes`; see `src/content/gallery/NOTICE.md`.

## Adding a list

Write an adapter in `adapters/` that exports `id`, `wanted(paths)` (which
text files to read) and `parse(files)` (one raw entry per prompt), register
it in `stages/fetch.js`, and add the list to `config/sources.json` with its
pinned commit and licence. An adapter guesses nothing: an entry it cannot
read cleanly is returned with a problem, and `screen` sets it aside.

## Tests

```bash
npm test
```

`node --test`, with the API and the lists mocked: nothing in the tests
touches the network or spends anything. CI does not run them yet.
