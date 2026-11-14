# Gallery fixtures

Invented recipes for building and testing the gallery pages without real
content. Nothing here comes from a source list: the titles, prompts, authors
and links are made up, and the media is abstract shapes drawn for the purpose.

- `recipes/` — seven recipes in the gallery format. Point the collection at
  it with `GALLERY_CONTENT_DIR=fixtures/gallery/recipes`.
- `media/` — stand-in media, laid out as the recipes name it. The images are
  drawn by `make-media.mjs`; the two `.webm` clips are a second of canvas
  animation each. All of it is far smaller than the sizes the recipes state.

| Recipe | Covers |
|---|---|
| `tin-robot-tea-party` | an image; Chinese prompt from the list |
| `harbour-fog-timelapse` | a video with a poster; the run sent the Chinese prompt |
| `origami-crane-unfolding` | a video without a poster; no Chinese title |
| `moss-covered-typewriter` | a machine-translated prompt; a portrait image |
| `neon-noodle-cart-poster` | a prompt with variables |
| `glass-whale-over-rooftops` | two runs, on two models |
| `handwritten-receipt-note` | a prompt with quotes, `$`, a backtick, newlines and `</script>`; two outputs |

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
