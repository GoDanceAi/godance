# Gallery content standard

**Version:** 2

**Person samples:** not allowed

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

## Templates and their variables

Some prompts have blanks. A recipe keeps each one as a variable with a
default, so that a visitor can fill in a value of their own. The gallery
shows the template, the names of its variables and their defaults.

- **The defaults are part of the recipe.** Every rule applies to each
  default, in English and in Chinese, exactly as it applies to the prompt.
  The published prompt is the template with its defaults filled in, so a
  default that fails a rule fails the recipe.
- **A variable must not invite a real person's name or likeness, or someone
  else's character or brand.** A variable named "celebrity", "actor" or
  "your face" is rejected under rule 1, and one named "brand logo" or
  "favourite character" under rule 2, whatever its default is. A variable
  for an object, a colour, a place, a material or a style is fine.
- **The reviewer sees them.** The review sheet shows the template with each
  variable marked, and every default in both languages, beside the prompt.

What a visitor later types into a variable is not gallery content. The
reviewer checks what the gallery shows.

## Recipes with inputs, and their samples

Some prompts work from an image the visitor supplies: a product to place in
a scene, a still to animate. A recipe says so with input slots. Each slot
has a hint that tells a visitor what to put in it, and a sample input that
the gallery shows beside the output.

- **A sample input is generated on Godance.** It is made for the recipe,
  from a prompt written for the purpose, and the recipe records the model,
  the prompt and the cost. It is never taken from a source list, and never
  a photograph of anything real.
- **Every rule applies to a sample,** exactly as it applies to an output.
  The reviewer sees each sample beside the output it produced.
- **An object sample shows no person.** No face, no body, no hands. A sample
  for a product slot is the product alone, on a plain background.
- **Person samples are not allowed** in this version. The line
  "Person samples" at the top of this document says so, and the import tool
  reads it: while it says "not allowed", an entry whose input is about a
  person is not screened in, not sampled and not published.

  When the operator allows them, by raising the version and changing that
  line to "allowed", a person sample depicts a fictional adult who
  resembles no real or public person. Rules 1 and 4 apply as everywhere
  else.
- **The hint never asks for a real person's name, a child, or anyone who has
  not consented.** "A portrait of your favourite actor" fails rule 1, and
  "a photo of your child" fails rule 4, whatever the sample shows. "One
  product on a plain background" is fine.

What a visitor later uploads into a slot is not gallery content. It is
covered by the terms a visitor accepts before uploading, not by this
standard.

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

## Community recipes

Signed-in users can publish their own recipes to the community, where
anyone can see and run them. The community is switched off until the
operator turns it on (`godance.community.enabled`). A community recipe is
held to this same standard, and it is pre-moderated: nothing an account
makes is public until a moderator (an account with the `SUPPORT` or `ADMIN`
role) has checked it against this document and approved it.

- **The same rules.** Rules 1 to 8 apply to a community recipe's title, its
  template, each variable's default, its tags and every example output,
  exactly as they apply to a gallery recipe. "The output counts too" applies
  to each example.
- **No real people in examples.** An example shows no real person, named or
  recognisable, and no look-alike of one. Only an adult account with a
  verified email can publish, and the examples are its own finished runs of
  the recipe: a run that used an uploaded or earlier image cannot be an
  example, so an example never shows anyone's upload.
- **Variables must not invite a real person's name or likeness.** As in
  "Templates and their variables": a variable named "celebrity", "your
  face" or "my ex" is rejected under rule 1, and one named "brand logo" or
  "favourite character" under rule 2, whatever its default is.
- **Input slots.** While uploads may not show people
  (`godance.inputs.allow-people` is false), a recipe with input slots cannot
  be submitted at all. Once they may, a slot's hint follows "Recipes with
  inputs, and their samples".
- **The profile is reviewed with the recipe.** A handle, display name or link
  that imitates a real person, a brand or Godance itself, or a link to
  anything that fails this standard, fails the recipe. A change to the
  display name or link after approval waits for a moderator before it is
  shown, and the handle cannot change once approved.

**A recipe is rejected** when any part of it fails a rule above; when an
example could not have come from the recipe as published, or shows
something the template does not ask for; when its title, tags or template
advertise, carry a link, or ask for contact details; or when the moderator
is unsure. The author sees the reason.

**A public recipe is removed** when a report, or a moderator's own look,
shows that it fails a rule above. A report alone removes nothing: the
recipe stays public, back in the moderation queue, until a moderator
decides. The author sees the reason. An author can also unpublish a recipe
at any time, and closing an account or deleting the recipe unpublishes it.
Removing or unpublishing deletes the public copies of the examples at once;
the record of what was published, and why it came down, is kept.

**The moderator sees** the template with each variable and its default, the
examples, the author's profile, and the earlier decisions on that author's
submissions.

What a visitor types into a variable, or uploads into a slot, when they run
a community recipe is not community content, as for the gallery.

## Changing this standard

Any change to the rules raises the version number at the top. Recipes
approved under an earlier version keep the version they were checked
against, so it is always possible to tell which need another look.

- **Version 2** added "Recipes with inputs, and their samples", and the
  "Person samples" line. Nothing in version 1's rules changed, so a recipe
  approved under version 1 needs no second look.

## For the operator to decide before approving this standard

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
- **Person samples.** Whether, and when, a recipe may show a sample input of
  a fictional adult. Until the line at the top says "allowed", none can be
  imported.
- **What counts as an input about a person.** The import tool decides with a
  list of words kept beside it (`input_people` in `screen-terms.json`), and
  the list is broad: a product recipe that mentions a hand holding the
  product is treated as a person recipe and set aside.
