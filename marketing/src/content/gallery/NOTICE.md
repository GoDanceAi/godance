# Gallery notice

Every `.json` file in this folder is one recipe: a prompt, the model and
parameters it was run with, what the run cost, and where the prompt came
from. This notice covers all of them.

## Where the prompts come from

The prompts are taken from open-source prompt lists that carry a clear
licence. Each recipe names its list, and the exact commit it was read at,
in `source.list` and `source.list_commit`.

| List | Licence |
|---|---|
| [Evolink-AI/awesome-gpt-image-2-API-Prompts](https://github.com/Evolink-AI/awesome-gpt-image-2-API-Prompts) | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| [YouMind-OpenLab/awesome-seedance-2-prompts](https://github.com/YouMind-OpenLab/awesome-seedance-2-prompts) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |

A recipe's own licence is in `source.licence`. A list with no licence, or
with one that does not allow reuse, is never a source.

## Credit

Each prompt is credited to the person who wrote it. A recipe gives the
author as the list names them, a link to the author, and a link to the
original post, in `source.author`, `source.author_url` and
`source.original_url`. This is done for every recipe, including those from
a list whose licence does not ask for it.

## Changes

A prompt is kept as its author wrote it wherever possible. Whatever was
changed is listed in the recipe's `source.changes`, in plain words:

- `placeholders resolved`: a templated prompt had blanks, which were filled
  with the defaults the author gave. The blanks and their defaults are kept
  in `prompt.variables`.
- `translated to Chinese` or `translated to English`: the list carried the
  prompt in one language only, and the other was machine-translated. Text
  meant to appear inside the picture was left as the author wrote it.
- `title translated to English`: the title was not in English.

`prompt.zh_origin` says where the Chinese prompt came from: `list` when the
list supplied it, `machine` when it was machine-translated.

## Outputs

The images and videos shown with a recipe were generated on Godance from
that recipe's prompt. None of them comes from a list: the lists' own
example images and videos are not downloaded, copied or shown.

> **TO BE FILLED IN BY THE OPERATOR — licence of generated outputs.**
> State here under what terms the generated images and videos may be used.
> This gap is filled before the gallery is switched on.

## Asking for a recipe to be removed

If you wrote one of these prompts and want it taken down, or you believe a
recipe should not be here, tell us and it will be removed. A removed prompt
is also recorded so that a later import cannot bring it back.

> **TO BE FILLED IN BY THE OPERATOR — removal contact.**
> Give the address removal requests go to. This gap is filled before the
> gallery is switched on.

## What is checked before a recipe is published

Every recipe is reviewed by a person against the gallery content standard,
`docs/gallery-content-standard.md`, before it is published. The recipe
records when it was approved and under which version of the standard, in
`review`.
