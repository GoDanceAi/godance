# Gallery content standard

**Version:** 1

**Status:** draft, for the operator's approval. No review starts until it is approved.

The gallery on godance.ai is open to everyone, with no sign-in and no age
check, so it is held to an all-ages standard. This document says what may
appear in it. It applies to a recipe as a whole: its title, its prompt in
both languages, and the output Godance generated from it.

A recipe is published only after a person has checked it against this
standard and approved it. Software narrows what reaches that person; it
never approves anything.

## The rules

A recipe is rejected if any of these applies to its prompt or to its output.

1. **Real people.** No real person, named or described so as to be
   recognisable. This covers public figures, celebrities and private
   individuals alike, living or dead, and look-alikes of them.
2. **Other people's characters and brands.** No third-party characters,
   franchises, brands, logos or trademarks, and no "in the style of" a named
   living artist. A generic genre or period ("a 1970s film poster",
   "watercolour") is fine.
3. **Sexual content.** Nothing sexualised or suggestive, no nudity, and no
   focus on body parts.
4. **Minors.** No minor as the subject of a portrait, fashion, romance or
   similar prompt.
5. **Violence and harm.** No graphic violence, gore, self-harm, drugs or
   hate symbols.
6. **Imitations of the real thing.** Nothing that imitates official
   documents, identity papers, currency, receipts, news reports, or a real
   organisation's advertising or interface.
7. **Claims.** No political, medical or financial claims in rendered text.
8. **When unsure, reject.** A recipe that needs an argument to be let in
   stays out.

## The output counts too

The reviewer applies every rule to the generated output as well as to the
prompt. A harmless prompt can still produce an output that fails: a face
that resembles a real person, a logo the model added by itself, text that
makes a claim. An output that fails is rejected even when its prompt passes.

## How it is applied

1. **Screening.** The import tool sets aside entries that plainly fail,
   using a list of terms kept beside the tool
   (`marketing/scripts/gallery/config/screen-terms.json`) and, optionally, a
   text model that reads each prompt against this document. Screening
   records a reason for everything it sets aside. Passing screening means
   only that an entry goes on to review.
2. **Review.** The operator looks at each candidate's output and prompt and
   marks it approved or rejected. A rejection carries a reason.
3. **Publication.** Only approved recipes are written. Each records the
   date it was approved and the version of this standard it was checked
   against (`review.approved_on`, `review.standard_version`).
4. **Removal.** A published recipe that turns out to fail, or whose author
   asks for it to be taken down, is removed and kept from being imported
   again. See the notice in `marketing/src/content/gallery/NOTICE.md`.

## Changing this standard

Any change to the rules raises the version number at the top. Recipes
approved under an earlier version keep the version they were checked
against, so it is always possible to tell which need another look.

## For the operator to decide before approving version 1

These are judgement calls the draft leaves open. Each changes how strict
screening is, not what the tool can do.

- **Passing mentions of a brand (rule 2).** "Shot on iPhone" or "Pixar-style
  3D" name a brand without putting it in the picture. The term list
  currently sets these aside.
- **Children outside portraits (rule 4).** The rule is about a minor as the
  subject. The term list currently sets aside any prompt that mentions a
  child at all, including, say, a picture-book scene.
- **Invented interfaces (rule 6).** A mock-up of an app that does not exist
  passes; one that names a real service does not. Many entries in the image
  list's `ui` category are the second kind.
