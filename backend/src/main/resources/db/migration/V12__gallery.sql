-- =====================================================================
-- Godance V12 — Community Gallery and Studio Presets
-- =====================================================================

CREATE TABLE gallery_items (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            REFERENCES users(id) ON DELETE SET NULL,
    job_id                      UUID            REFERENCES generation_jobs(id) ON DELETE SET NULL,
    title                       VARCHAR(255)    NOT NULL,
    prompt                      TEXT            NOT NULL,
    negative_prompt             TEXT,
    author_name                 VARCHAR(255)    NOT NULL,
    modality                    VARCHAR(32)     NOT NULL,    -- IMAGE | VIDEO
    model                       VARCHAR(128)    NOT NULL,
    provider                    VARCHAR(64)     NOT NULL,
    aspect_ratio                VARCHAR(32)     NOT NULL DEFAULT '1:1',
    duration                    VARCHAR(32),
    seed                        BIGINT,
    output_url                  VARCHAR(2048)   NOT NULL,
    thumbnail_url               VARCHAR(2048)   NOT NULL,
    likes_count                 INTEGER         NOT NULL DEFAULT 0,
    remix_count                 INTEGER         NOT NULL DEFAULT 0,
    is_official                 BOOLEAN         NOT NULL DEFAULT FALSE,
    category                    VARCHAR(32)     NOT NULL DEFAULT 'ART', -- GAME | PHOTO | VIDEO | ART
    tags                        JSONB,
    forked_from_title           VARCHAR(255),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX ix_gallery_items_created_at ON gallery_items (created_at DESC);
CREATE INDEX ix_gallery_items_category   ON gallery_items (category);
CREATE INDEX ix_gallery_items_modality   ON gallery_items (modality);
CREATE INDEX ix_gallery_items_model      ON gallery_items (model);
CREATE INDEX ix_gallery_items_official   ON gallery_items (is_official);
CREATE INDEX ix_gallery_items_user_id    ON gallery_items (user_id);

-- Seed initial official recipe showcases
INSERT INTO gallery_items (
    id, user_id, title, prompt, negative_prompt, author_name, modality,
    model, provider, aspect_ratio, duration, seed, output_url, thumbnail_url,
    likes_count, remix_count, is_official, category, tags, forked_from_title, created_at
) VALUES
(
    '00000000-0000-0000-0000-000000000001', NULL,
    'Cyberpunk Neon Alleyway',
    'Cinematic fast-moving FPV drone flight navigating through rainy narrow alleys of a dense neon-lit cyberpunk metropolis, reflections on wet asphalt',
    NULL, 'Godance Studio', 'VIDEO',
    'runway-gen3-alpha', 'Runway', '16:9', '5s', 482910,
    'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4',
    'https://images.unsplash.com/photo-1518709268805-4e9042af9f23?auto=format&fit=crop&w=800&q=80',
    142, 39, TRUE, 'VIDEO', '["cyberpunk", "neon", "cinematic"]'::jsonb, NULL, now() - INTERVAL '2 hours'
),
(
    '00000000-0000-0000-0000-000000000002', NULL,
    '16-Bit Cyber Warrior Sprite',
    'Detailed 16-bit pixel art sprite sheet of a cyber warrior character in action pose, clean outlines, sharp palette, white background, retro game asset',
    'blurry, 3d, realistic', 'Godance Studio', 'IMAGE',
    'flux-1.1-pro', 'Black Forest Labs', '1:1', NULL, 12904,
    'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=1200&q=80',
    'https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80',
    98, 27, TRUE, 'GAME', '["pixel-art", "game-dev", "sprite"]'::jsonb, NULL, now() - INTERVAL '4 hours'
),
(
    '00000000-0000-0000-0000-000000000003', NULL,
    'Studio Film Portrait in Portra 400',
    'Candid intimate portrait of an architect in an open concrete studio, natural golden hour sidelight, Kodak Portra 400 film grain, 35mm lens, f/1.8 shallow depth of field',
    NULL, 'elena_lens', 'IMAGE',
    'flux-1.1-pro', 'Black Forest Labs', '4:3', NULL, 90214,
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=1200&q=80',
    'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=800&q=80',
    215, 53, FALSE, 'PHOTO', '["portrait", "portra400", "film"]'::jsonb, '35mm Film Portrait', now() - INTERVAL '6 hours'
),
(
    '00000000-0000-0000-0000-000000000004', NULL,
    'Isometric Crystal Dungeon Tile',
    'Isometric 3D render of a glowing crystal cave dungeon tile with stone floor and treasure chest, Unreal Engine 5 aesthetic, volumetric lighting, diorama cutout',
    NULL, 'Godance Studio', 'IMAGE',
    'flux-1.1-pro', 'Black Forest Labs', '1:1', NULL, 7721,
    'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=1200&q=80',
    'https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=800&q=80',
    83, 19, TRUE, 'GAME', '["isometric", "ue5", "dungeon"]'::jsonb, NULL, now() - INTERVAL '12 hours'
);
