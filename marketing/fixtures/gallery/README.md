# Gallery fixtures

Invented recipes for building and testing the gallery pages without real
content. Nothing here comes from a source list: the titles, prompts, authors
and links are made up, and the media is abstract shapes drawn for the purpose.

- `recipes/` — ten recipes in the gallery format. Point the collection at
  it with `GALLERY_CONTENT_DIR=fixtures/gallery/recipes`.
- `media/` — stand-in media, laid out as the recipes name it. The images,
  sample inputs among them, are drawn by `make-media.mjs`; the `.webm` clips
  are a second of canvas animation each. All of it is far smaller than the
  sizes the recipes state.
- `invalid/` — recipes the format refuses, each for one reason. Not a
  gallery folder: the tests read it to see the refusal.

| Recipe | Covers |
|---|---|
| `tin-robot-tea-party` | an image; Chinese prompt from the list |
| `harbour-fog-timelapse` | a video with a poster; the run sent the Chinese prompt |
| `origami-crane-unfolding` | a video without a poster; no Chinese title |
| `moss-covered-typewriter` | a machine-translated prompt; a portrait image; published before templates, so its blank is filled and only listed |
| `neon-noodle-cart-poster` | a template with two variables in both languages; one has a Chinese default |
| `ceramic-mug-json-prompt` | a prompt that is JSON, whose template ends in `}}` |
| `glass-whale-over-rooftops` | two runs, on two models |
| `handwritten-receipt-note` | a prompt with quotes, `$`, a backtick, newlines and `</script>`; two outputs |
| `enamel-teapot-on-marble` | an image recipe with one input slot, role `image`; a hint in both languages |
| `paper-boat-sets-off` | a video recipe with a `first_frame` slot and a `reference_image` slot; a hint in English only; a sample whose path has a space, brackets and an apostrophe |

| Invalid recipe | Refused because |
|---|---|
| `invalid/sample-without-generated-from` | its sample input does not say how it was generated (`input-sample`) |

## Looking at the pages

The gallery is off unless the media base URL and the removal contact are set.
For local work, set them in the environment (they override
`src/lib/gallery-config.ts`):

```bash
GALLERY_CONTENT_DIR=fixtures/gallery/recipes \
GALLERY_MEDIA_BASE_URL=http://localhost:4399/ \
GALLERY_REMOVAL_CONTACT=removals@example.test \
GALLERY_OUTPUT_LICENCE="(the output licence statement)" \
GALLERY_RUN_THIS=false \
npm run dev
```

and serve the media beside it:

```bash
python3 -m http.server 4399 --directory fixtures/gallery/media
```
