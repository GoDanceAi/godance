-- V34: which video models take an image to start from, end on or follow
-- (round 11 brief 04).
--
-- capabilities.inputs says what a video request to the model may carry:
--   first_frame       the frame the clip starts from
--   last_frame        the frame it ends on
--   reference_images  images it follows, and the most it takes (max)
-- A field the model does not list answers 400 input_not_supported before
-- anything is charged: absent means no.
--
-- A model is listed here only where its provider's own documentation, read
-- on 2026-11-14, says that model takes the input on the API its adapter
-- calls.
--
--   seedance-2.0        first frame, last frame (with a first frame), and
--                       1 to 9 reference images, referred to in the prompt
--                       by position.
--                       https://docs.volcengine.com/docs/ark/create-video-generation-task-api
--                       https://docs.volcengine.com/docs/ark/seedance-2-0
--   kling-3.0           first frame, and last frame with a first frame.
--                       Reference images are documented for another model
--                       (kling-v3-omni), so none here.
--                       https://kling.ai/document-api/api/video/3-0-omni/image-to-video
--                       https://kling.ai/document-api/guides/capability-map/video
--   minimax-hailuo-2.3  first frame only. A last frame is documented for
--                       Hailuo-02 and reference images for S2V-01, so
--                       neither here.
--                       https://platform.minimax.io/docs/api-reference/video-generation-i2v
--
-- Left out, because the documentation did not confirm it for the model as
-- the catalog and its adapter have it:
--   sora-2, sora-2-pro, sora-2-pro-hd   OpenAI lists Sora 2 and its video
--                                       API as shut down
--   veo-3.1, veo-3.1-fast               these ids are listed as shut down on
--                                       the API the adapter calls
--   runway-gen4                         a first frame is documented for other
--                                       ids, on an endpoint the adapter does
--                                       not call
--
-- No price changes: none of the three prices a clip that starts from an
-- image differently from one that starts from text, and none charges for
-- the image. (Ark prices by output resolution; Kling and MiniMax by clip.)
-- So this migration adds no input price; per_input_image_microcents is
-- brief 03's column, in V33.
--
-- To add a model later: confirm it in the provider's documentation, teach
-- its adapter to send the image, then set the same keys on its row (the
-- admin catalog editor writes capabilities).

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb)
       || '{"inputs": {"first_frame": true, "last_frame": true, "reference_images": {"max": 9}}}'::jsonb
 WHERE modality = 'VIDEO' AND model_name = 'seedance-2.0';

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb)
       || '{"inputs": {"first_frame": true, "last_frame": true}}'::jsonb
 WHERE modality = 'VIDEO' AND model_name = 'kling-3.0';

UPDATE model_catalog
   SET capabilities = COALESCE(capabilities, '{}'::jsonb)
       || '{"inputs": {"first_frame": true, "last_frame": false}}'::jsonb
 WHERE modality = 'VIDEO' AND model_name = 'minimax-hailuo-2.3';
