# Godance Media Studio & Community Gallery - API Specification

> **Document Version**: v1.1.0  
> **Branch**: `feat/media-studio`  
> **Backend Stack**: Spring Boot 3.4 WebFlux, Spring Data JPA, PostgreSQL, Flyway

---

## 1. Architectural Boundaries & Data Flow

All endpoints are hosted within the unified Spring Boot monolith behind reactive non-blocking WebFlux pipelines.

### 1.1 Authentication & Security Context
- `GET /v1/gallery` and `POST /v1/gallery/{id}/like` are publicly accessible without authentication.
- `POST /v1/gallery` and `POST /v1/rewards/share` require a valid JWT Bearer token via `SecurityConfig.java`.
- Principal user IDs are extracted from the JWT subject claim (`JwtService.userIdFromSubject`).

---

## 2. REST Endpoints Specification

### 2.1 Retrieve Community Gallery Feed
`GET /v1/gallery`

#### Query Parameters
| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `source` | `String` | No | `ALL`, `OFFICIAL`, or `COMMUNITY` |
| `modality` | `String` | No | `ALL`, `IMAGE`, or `VIDEO` |
| `category` | `String` | No | `ALL`, `GAME`, `PHOTO`, `VIDEO`, `ART` |
| `model` | `String` | No | Target model identifier (e.g. `flux-1.1-pro`) |
| `q` | `String` | No | Search substring matching title, prompt, or author |
| `page` | `Integer`| No | 0-indexed page number (default: 0) |
| `size` | `Integer`| No | Items per page (default: 20, max: 100) |

#### Response (200 OK)
```json
{
  "items": [
    {
      "id": "00000000-0000-0000-0000-000000000002",
      "title": "16-Bit Cyber Warrior Sprite",
      "prompt": "Detailed 16-bit pixel art sprite sheet of a cyber warrior...",
      "negativePrompt": "blurry, 3d, realistic",
      "authorName": "Godance Studio",
      "modality": "IMAGE",
      "model": "flux-1.1-pro",
      "provider": "Black Forest Labs",
      "aspectRatio": "1:1",
      "duration": null,
      "seed": 12904,
      "outputUrl": "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=1200&q=80",
      "thumbnailUrl": "https://images.unsplash.com/photo-1550745165-9bc0b252726f?auto=format&fit=crop&w=800&q=80",
      "likesCount": 98,
      "remixCount": 27,
      "isOfficial": true,
      "category": "GAME",
      "tags": ["pixel-art", "game-dev", "sprite"],
      "forkedFromTitle": null,
      "createdAt": "2026-10-06T09:30:00Z"
    }
  ],
  "page": 0,
  "totalPages": 1,
  "totalElements": 1
}
```

---

### 2.2 Publish Creation to Community Gallery
`POST /v1/gallery`

#### Request Headers
`Authorization: Bearer <jwt_token>`

#### Request Body
```json
{
  "jobId": "uuid-optional",
  "title": "Neon Rain Drone Flight",
  "prompt": "Cinematic fast-moving FPV drone flight through rainy neon city...",
  "negativePrompt": "shaky, low frame rate",
  "modality": "VIDEO",
  "model": "runway-gen3-alpha",
  "provider": "Runway",
  "aspectRatio": "16:9",
  "duration": "5s",
  "seed": 482910,
  "outputUrl": "https://storage.godance.ai/assets/vid123.mp4",
  "thumbnailUrl": "https://storage.godance.ai/assets/thumb123.jpg",
  "category": "VIDEO",
  "tags": ["drone", "cyberpunk", "fpv"],
  "forkedFromTitle": "Cyberpunk Neon Flythrough"
}
```

#### Response (201 Created)
Returns the created `GalleryItemView` object.

---

### 2.3 Like Creation
`POST /v1/gallery/{id}/like`

#### Response (200 OK)
```json
{
  "id": "00000000-0000-0000-0000-000000000002",
  "likesCount": 99
}
```

---

### 2.4 Claim Share-to-Earn Reward
`POST /v1/rewards/share`

#### Request Headers
`Authorization: Bearer <jwt_token>`

#### Business Rules
- Checks daily claim history in `credit_ledger` with `sourceRef = 'SHARE_REWARD'`.
- Daily ceiling: **5 claims per calendar day (UTC)**.
- Reward amount: **10 Cents ($0.10)** credited via `CreditSource.PROMO`.
- Idempotency key: `share-reward:{userId}:{date}:{claimIndex}`.

#### Response (200 OK - Success)
```json
{
  "success": true,
  "rewardCents": 10,
  "claimsToday": 3,
  "maxDailyClaims": 5,
  "newBalanceCents": 5030
}
```

#### Response (200 OK - Daily Quota Reached)
```json
{
  "success": false,
  "rewardCents": 0,
  "claimsToday": 5,
  "maxDailyClaims": 5,
  "newBalanceCents": 5050
}
```

---

## 3. Database Schema (`V12__gallery.sql`)

```sql
CREATE TABLE gallery_items (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            REFERENCES users(id) ON DELETE SET NULL,
    job_id                      UUID            REFERENCES generation_jobs(id) ON DELETE SET NULL,
    title                       VARCHAR(255)    NOT NULL,
    prompt                      TEXT            NOT NULL,
    negative_prompt             TEXT,
    author_name                 VARCHAR(255)    NOT NULL,
    modality                    VARCHAR(32)     NOT NULL,
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
    category                    VARCHAR(32)     NOT NULL DEFAULT 'ART',
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
```
