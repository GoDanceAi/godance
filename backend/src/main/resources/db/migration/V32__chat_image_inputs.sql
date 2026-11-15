-- V32: which chat models accept images (round 11 brief 02).
--
-- capabilities.inputs.images.max is the most images one chat request to the
-- model may carry. A model without it answers 400
-- model_does_not_accept_images before anything is charged: absent means no.
--
-- A model is listed here only where its provider's own documentation, read
-- on 2026-11-14, says that model takes image input on the API the adapter
-- calls. The figure is 10 for every one of them: Godance's own limit per
-- request, which is below each provider's.
--
--   OpenAI      gpt-4o, gpt-4o-mini, gpt-4.1, gpt-5.5, gpt-5.4, gpt-5.4-nano
--               Each model page lists "Input modalities: text, image" and
--               Chat Completions as supported; up to 1,500 images a request.
--               https://developers.openai.com/api/docs/models/gpt-4o (and the
--               same page for each id)
--               https://developers.openai.com/api/docs/guides/images-vision
--   Anthropic   claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5
--               Listed as supported models on the vision page; 100 images a
--               request on a 200k-token model, 600 on the others.
--               https://platform.claude.com/docs/en/build-with-claude/vision
--   Google      gemini-3.1-flash-lite
--               "Inputs: Text, Image, Video, Audio, and PDF"; up to 3,600
--               images a request.
--               https://aistudio.google.com/docs/models/gemini-3.1-flash-lite
--               https://aistudio.google.com/docs/image-understanding
--
-- Left out, because the documentation did not confirm image input for the
-- model id the catalog sends:
--   claude-3-5-sonnet-20241022, claude-3-5-haiku-20241022   listed as retired
--   gemini-1.5-pro, gemini-1.5-flash                        no longer documented
--   gemini-3.1-pro            the documented id is gemini-3.1-pro-preview
--   doubao-1.6-pro            the id is not in Ark's model list
--   grok-4                    the id is not in xAI's model table
--   ernie-5.1                 listed under text generation only
--   kimi-k2.6                 vision is documented for the K3 models only
--   qwen-3.6, qwen-3.6-coder  the documented ids are qwen3.6-plus and qwen3.6-flash
--   glm-4.7-flash             input is text only
--   minimax-m2.7              image input is documented for the M3 models only
--   deepseek-v4-pro, deepseek-v4-flash   the documentation site could not be reached
--
-- To add one later: confirm it in the provider's documentation, then set
-- the same key on its row (the admin catalog editor writes capabilities).

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb) || '{"inputs": {"images": {"max": 10}}}'::jsonb
 WHERE modality = 'TEXT'
   AND model_name IN (
        'gpt-4o', 'gpt-4o-mini', 'gpt-4.1', 'gpt-5.5', 'gpt-5.4', 'gpt-5.4-nano',
        'claude-opus-4-7', 'claude-sonnet-4-6', 'claude-haiku-4-5',
        'gemini-3.1-flash-lite');
