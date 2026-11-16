# Gallery: the first 48 hours after a model launch

When a model joins the catalog, people search for it, and for how it compares
with the models they know. Within 48 hours, run published gallery recipes on
it, review the outputs, publish them, and let the comparison pages show the
evidence: the same prompts on both models, the outputs side by side, and what
each run cost.

The tool is `marketing/scripts/gallery/cli.js` (see its README, "rerun").
Every command below is a dry run unless it says `--apply`, and nothing is
published that the operator has not approved.

## Rules that hold throughout

- **Dry run first.** `rerun` without `--apply` prints the plan: each job, the
  parameters it sends, what it leaves out and why, and what it holds. It
  makes no paid call and writes nothing.
- **Always a cap.** `--max-spend-cents` is required, dry run or not. It
  covers everything the work folder has ever spent; running again with the
  same cap can never spend more in total.
- **Nothing unreviewed.** A new run is published only once it is approved in
  the review sheet. Approving it does not change the recipe's own review.
- **Nothing published changes.** A recipe's existing runs, their order, its
  outputs and its review stay as they are; new runs are added after them.
- **No winner.** The comparison pages show outputs and costs, and leave the
  judging to the reader. The post does the same.

## The cost

Before a job is sent, `rerun` holds what the catalog says it will cost:

- an image: `ceil(per_image_microcents × n × (1 + markup_percent / 100) / 10 000)` cents
- an image with input images (an edit): add `per_input_image_microcents` for each input, where the model has one
- a video: `ceil(per_second_microcents × seconds × (1 + markup_percent / 100) / 10 000)` cents

A recipe already run on a model gets no second run on it. What a run records
is the job's own `actualCostCents`, not the hold.

For 20 recipes whose first run is `gpt-image-2`, rerun on the other four
image models research §5.8 names, at today's catalog prices: `nano-banana`
16 cents, `seedream-5.0` 4, `qwen-image-2.0` 4, `flux-2-pro` 4, so
20 × 28 = 560 cents. A cap of 1000 cents leaves room for every one of them,
and for a retry or two. The dry run prints the exact figure.

## Hour 0 to 2: confirm the model

```bash
curl -s https://api.godance.ai/v1/models | jq '.data[] | select(.id == "NEW-MODEL")'
```

The request is free and needs no key. Check:

- the `id` is the one the provider and the press use, or close to it (if not,
  note the alias question for the operator; the catalog is not changed here)
- `godance.modality` (IMAGE or VIDEO), `pricing` and `markup_percent`
- `capabilities`: `sizes`, `default_size`, `max_n`, `durations`,
  `default_seconds` and `inputs`. What a model does not list, `rerun` does not
  send it. If the provider's documentation confirms sizes or clip lengths the
  catalog does not list, add them to
  `marketing/scripts/gallery/config/rerun-params.json`, naming the page in the
  commit.

## Hour 2 to 6: choose 20 recipes and plan

Choose 20 published recipes that show what the model is good at: text in
the image, product shots, portraits of objects, Chinese prompts, video
motion, whatever its launch notes claim. Pick by slug or by tag:

```bash
cd marketing
node scripts/gallery/cli.js rerun --models NEW-MODEL --slugs slug-1,slug-2,... --max-spend-cents 1000
node scripts/gallery/cli.js rerun --models NEW-MODEL --tag poster --max-spend-cents 1000
```

Read the plan. For each recipe it says one of:

- `+ <slug> on <model>  <params>  holds N cents`: it runs, and lists each of
  the first run's parameters it leaves out, with the reason
- `= <slug> on <model>: …`: it has a run on that model already, or this work
  folder ran it already
- `- <slug> on <model>: cannot be run: …`: the catalog does not list the
  model, the model makes the other kind of output, or it cannot take the
  recipe's input images

The last line says how many runs would start, what they hold, and how much
of that the cap leaves room for.

To give every selected recipe a run on a China-hosted model as well, add
`--china-hosted`: each recipe with no run on one gets a run on the cheapest
of its kind in `config/china-hosted.json`, the first listed when two cost the
same.

## Hour 6 to 12: run it, under the cap

The operator runs this, with a key whose own spending cap is no higher than
the run's:

```bash
GODANCE_API_KEY=... node scripts/gallery/cli.js rerun --models NEW-MODEL --slugs slug-1,slug-2,... --max-spend-cents 1000 --apply
node scripts/gallery/cli.js status
```

At most two jobs run at once. If the run stops, run the same command again:
a job already accepted is picked up by its id, never sent twice. A run
reported as **uncertain** was sent with no clear answer: look for it in
`GET /v1/jobs` before `--retry-uncertain`. A failed job is refunded, and
`--retry-failed` tries it again.

A recipe with input images needs its sample inputs in
`marketing/.gallery-work/media/`, where `sample` saved them. If the work
folder is a fresh one, copy the published media there first.

## Hour 12 to 24: review and publish

```bash
node scripts/gallery/cli.js review --apply
```

Open `marketing/.gallery-work/review/index.html`. Each new run is shown beside
its recipe's first run, with both costs and the parameters it left out.
Apply the content standard to every new output. Approve or reject each one;
a rejection needs a reason. Save `decisions.json` into the same folder.

```bash
node scripts/gallery/cli.js publish            # what it would add
node scripts/gallery/cli.js publish --apply
npm run validate
```

`publish` adds each approved run at the end of its recipe's runs, with the day
it was approved. It refuses a run whose recipe's prompt or samples have
changed since. Upload the files listed in `.gallery-work/manifest.json` to
public storage before the change deploys, then commit the changed recipes in
`marketing/src/content/gallery/` and open a pull request. `main` deploys on
merge.

## Hour 24 to 36: check that the comparison pages appear

A pair of models gets a page once at least `GALLERY_COMPARE_MIN` published
recipes (8 by default) have an approved run on both. Below that there is no
page and no link. Before merging, build locally against the content folder:

```bash
npm run build && ls dist/gallery/compare/
```

On the live site, after the deploy:

- `https://godance.ai/gallery/compare` lists the new pair
- `https://godance.ai/gallery/compare/<a>-vs-<b>` shows both outputs of every
  recipe (so the media was uploaded), each run's cost, and in the summary
  table each model's price today, linked to `/pricing`
- `https://godance.ai/gallery/model/NEW-MODEL` links to its comparisons
- `https://godance.ai/gallery/<slug>.json` lists the new run under `runs`
- `https://godance.ai/sitemap-0.xml` has the comparison page
- "Run this" on a recipe opens the Studio on a run whose model the visitor's
  catalog lists
- the page says nothing about which model is better

## Then: a line in llms.txt, once the gallery is on

`marketing/public/llms.txt` and `llms-full.txt` say nothing of the gallery
while it can be switched off, and `scripts/llms/llms.test.js` fails on any
mention. So this step waits until the gallery is switched on for good. Then
add one line per comparison, in the same commit as the change to that test:

```
- [NEW-MODEL vs OTHER-MODEL](https://godance.ai/gallery/compare/<a>-vs-<b>): the same N gallery prompts on both models, with each output and what each run cost
```

## Hour 36 to 48: post the comparison

Link the comparison page. Give what the page gives: how many prompts, the
outputs, what the runs cost and each model's price today. Say how the runs
were made (through the Godance API, the same prompt on both models, each with
the parameters the model takes). Leave the verdict to the reader.
