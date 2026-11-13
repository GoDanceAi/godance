-- V24: what each model is and what it accepts.
--
-- display_name and description are for people choosing a model.
-- capabilities is a JSON object; every key is optional, and an absent key
-- means unknown, not unlimited:
--
--   sizes            sizes the provider accepts           (advisory)
--   default_size     size used when a request names none  (advisory)
--   durations        clip lengths in seconds              (advisory)
--   default_seconds  clip length used when a request names none
--   max_n            most outputs one request can return; a request asking
--                    for more is refused before any charge. Absent = 1.
--
-- A model approved later from a pricing proposal has none of these, which
-- is a valid state: it is listed without them and accepts n = 1.
--
-- Values come from the adapter in gateway/providers/ and the provider's
-- public documentation. Where neither is certain the key is left out.
--   max_n 10      OpenAI Images API: n is 1-10; the adapter forwards n and
--                 keeps every image returned.
--   max_n 1       BFL and nano-banana: the adapter asks for one image and the
--                 provider returns one. Video: every adapter returns one clip.
--   default_size  only where the adapter itself sends a default.
--   default_seconds  the adapter's defaultDurationSeconds(); a test fails if
--                 the two ever disagree.

ALTER TABLE model_catalog
    ADD COLUMN display_name VARCHAR(128),
    ADD COLUMN description  TEXT,
    ADD COLUMN capabilities JSONB;

ALTER TABLE model_catalog
    ADD CONSTRAINT ck_model_catalog_capabilities_object
        CHECK (capabilities IS NULL OR jsonb_typeof(capabilities) = 'object');

UPDATE model_catalog AS m
   SET display_name = v.display_name,
       description  = v.description,
       capabilities = v.capabilities::jsonb
  FROM (VALUES
    -- ----- Image ---------------------------------------------------------
    ('gpt-image-2',        'GPT Image 2',
     'OpenAI''s GPT Image 2 text-to-image model.',
     '{"max_n": 10}'),
    ('gpt-image-1.5',      'GPT Image 1.5',
     'OpenAI''s GPT Image 1.5 text-to-image model.',
     '{"sizes": ["1024x1024", "1536x1024", "1024x1536", "auto"], "default_size": "auto", "max_n": 10}'),
    ('seedream-5.0',       'Seedream 5.0',
     'ByteDance''s Seedream 5.0 text-to-image model, served through Volcengine Ark.',
     '{"default_size": "1024x1024"}'),
    ('qwen-image-2.0',     'Qwen-Image 2.0',
     'Alibaba''s Qwen-Image 2.0 text-to-image model, served through DashScope.',
     '{"default_size": "1024x1024"}'),
    ('qwen-image-2.0-pro', 'Qwen-Image 2.0 Pro',
     'Alibaba''s Qwen-Image 2.0 Pro text-to-image model, served through DashScope.',
     '{"default_size": "1024x1024"}'),
    ('flux-2-pro',         'FLUX.2 [pro]',
     'Black Forest Labs'' FLUX.2 [pro] text-to-image model. One image per request.',
     '{"default_size": "1024x1024", "max_n": 1}'),
    ('flux-1.1-pro',       'FLUX1.1 [pro]',
     'Black Forest Labs'' FLUX1.1 [pro] text-to-image model. One image per request.',
     '{"default_size": "1024x1024", "max_n": 1}'),
    ('nano-banana',        'Nano Banana',
     'Google''s Gemini image model (gemini-3-pro-image-preview). One image per request.',
     '{"max_n": 1}'),
    ('imagen-4',           'Imagen 4',
     'Google''s Imagen 4 text-to-image model.',
     NULL),
    ('sdxl',               'Stable Diffusion XL',
     'Stability AI''s SDXL 1.0 text-to-image model.',
     '{"default_size": "1024x1024"}'),
    -- ----- Video ---------------------------------------------------------
    ('seedance-2.0',       'Seedance 2.0',
     'ByteDance''s Seedance 2.0 text-to-video model, served through Volcengine Ark. One clip per request.',
     '{"default_size": "1280x720", "default_seconds": 5, "max_n": 1}'),
    ('sora-2',             'Sora 2',
     'OpenAI''s Sora 2 text-to-video model. One clip per request.',
     '{"default_size": "1280x720", "default_seconds": 10, "max_n": 1}'),
    ('sora-2-pro',         'Sora 2 Pro',
     'OpenAI''s Sora 2 Pro text-to-video model. One clip per request.',
     '{"default_size": "1280x720", "default_seconds": 10, "max_n": 1}'),
    ('sora-2-pro-hd',      'Sora 2 Pro HD',
     'Sora 2 Pro at its higher resolution. One clip per request.',
     '{"default_size": "1792x1024", "default_seconds": 10, "max_n": 1}'),
    ('veo-3.1',            'Veo 3.1',
     'Google''s Veo 3.1 text-to-video model, in 16:9. One clip per request.',
     '{"default_seconds": 5, "max_n": 1}'),
    ('veo-3.1-fast',       'Veo 3.1 Fast',
     'Google''s Veo 3.1 Fast text-to-video model, in 16:9. One clip per request.',
     '{"default_seconds": 5, "max_n": 1}'),
    ('kling-3.0',          'Kling 3.0',
     'Kuaishou''s Kling 3.0 text-to-video model. One clip per request.',
     '{"default_seconds": 5, "max_n": 1}'),
    ('runway-gen4',        'Runway Gen-4',
     'Runway''s Gen-4 text-to-video model. One clip per request.',
     '{"default_size": "1280x720", "default_seconds": 5, "max_n": 1}'),
    ('minimax-hailuo-2.3', 'Hailuo 2.3',
     'MiniMax''s Hailuo 2.3 text-to-video model. One clip per request.',
     '{"default_seconds": 6, "max_n": 1}')
  ) AS v(model_name, display_name, description, capabilities)
 WHERE m.model_name = v.model_name;
