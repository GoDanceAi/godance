-- V33: image edits (round 11 brief 03): which image models take input
-- images, and what a provider charges for one.
--
-- 1. per_input_image_microcents: what the provider charges for each input
--    image of an edit, in microcents. Null means it charges nothing, or that
--    no per-image figure is known; either way nothing is added to the price.
--    CostCalculator multiplies it by the number of input images (a mask is
--    not counted) and applies the model's markup, so the estimate, the
--    pre-debit and the charge all carry it. A pricing proposal may set it
--    (field per_input_image).
--
-- 2. capabilities.inputs.images: {"max": N, "mask": true} says the model
--    takes up to N input images, and a mask when "mask" is true. A model
--    without it answers 400 input_not_supported before anything is charged:
--    absent means no.
--
-- A model is listed here only where its provider's own documentation, read
-- on 2026-11-14, says that model takes input images on an API its adapter
-- calls.
--
--   gpt-image-2, gpt-image-1.5
--       POST /v1/images/edits, multipart; up to 16 images; a mask, applied
--       to the first image.
--       https://developers.openai.com/api/reference/resources/images/methods/edit
--       https://developers.openai.com/api/docs/guides/image-generation
--       Price: OpenAI bills input images as image input tokens ($8.00 per 1M)
--       and publishes no tokens-per-image figure for these models (its
--       patch and tile rules are stated to cover vision models, not these).
--       So no per-image price is seeded: the column stays null. THE OPERATOR
--       SHOULD SET ONE, from what the provider's usage reports, before
--       switching inputs on.
--       https://developers.openai.com/api/docs/pricing
--   seedream-5.0
--       The same generation endpoint with `image`: one image or a list, up
--       to 14; no mask. Input images are free.
--       https://www.volcengine.com/docs/82379/1541523
--       https://www.volcengine.com/docs/82379/1544106
--   flux-2-pro
--       The same endpoint with input_image, input_image_2 … input_image_8:
--       up to 8, by address; no mask.
--       https://docs.bfl.ai/flux_2/flux2_image_editing
--       Price: "Ref img $0.015 / MP" on top of the output, at
--       https://bfl.ai/pricing. FLUX.2 documents images up to 4 MP, so the
--       price is seeded at the largest: 4 MP x $0.015 = $0.06 = 60000
--       microcents for each input image.
--
-- Left out:
--   sdxl                  Stability documents image-to-image and a mask, on
--                         separate multipart endpoints the adapter does not
--                         call yet. Not implemented in this round.
--   qwen-image-2.0, -pro  input images are documented on another endpoint
--                         than the one the adapter calls
--   flux-1.1-pro          takes an image prompt only, which is not an edit
--   nano-banana           the model id the adapter sends is listed as shut
--                         down; its successor takes input images
--   imagen-4              text only
--
-- To add a model later: confirm it in the provider's documentation, teach
-- its adapter to send the images, then set the same keys on its row.

ALTER TABLE model_catalog ADD COLUMN per_input_image_microcents BIGINT;

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb) || '{"inputs": {"images": {"max": 16, "mask": true}}}'::jsonb
 WHERE modality = 'IMAGE' AND model_name IN ('gpt-image-2', 'gpt-image-1.5');

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb) || '{"inputs": {"images": {"max": 14}}}'::jsonb
 WHERE modality = 'IMAGE' AND model_name = 'seedream-5.0';

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb) || '{"inputs": {"images": {"max": 8}}}'::jsonb,
       per_input_image_microcents = 60000
 WHERE modality = 'IMAGE' AND model_name = 'flux-2-pro';
