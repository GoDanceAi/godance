# Godance Media Studio & Community Gallery - Feature Specification (Spec)

> **Document Version**: v1.2.0  
> **Branch**: `feat/media-studio`  
> **Status**: Approved & Implemented  
> **Architecture**: Monolith Full-Stack (Spring Boot 3 WebFlux + React 18 / Vite)

---

## 1. System Overview & Problem Statement

### 1.1 Background & Motivation
Godance (`godance.ai`) is an AI aggregator providing unified text, image, video, and audio generation behind a single OpenAI-compatible API and credit ledger. Prior to this feature, multimodal media creation was constrained to a rudimentary playground:
1. **Unaligned Parameters & Rough UI**: Form controls (such as video duration and file format) were misaligned and detached from actual upstream model capabilities. Key expert parameters (negative prompt, seed, image-to-video reference framing) were missing.
2. **Blank Canvas Friction**: Users were presented with an empty prompt textarea with no visual inspiration or domain-tailored recipes, increasing time-to-value and trial friction.
3. **Lack of UGC Flywheel & Community Retention**: No public gallery existed to showcase community creations, fork high-performing prompts, or reward creators for sharing their outputs.

### 1.2 Core Product Flywheel
```
  ┌─────────────────────────────────────────────────────────────┐
  │                 1. Prompt Recipes & Presets                 │
  │      - Game assets, cinematic photo, motion video           │
  │      - Visual thumbnails & Provenance Lineage Badge         │
  └──────────────────────────────┬──────────────────────────────┘
                                 │ 1-Click Apply
                                 ▼
  ┌─────────────────────────────────────────────────────────────┐
  │                2. Deep Media Studio Workspace               │
  │      - Model capabilities dynamic mapping                   │
  │      - Reference image (I2V / I2I), seed, negative prompt   │
  └──────────────┬───────────────────────────────┬──────────────┘
                 │ Output Produced               │ Value-Add Tools
                 ▼                               ▼
  ┌──────────────────────────────┐ ┌────────────────────────────┐
  │   3. Community Gallery       │ │  4. Post-Processing Tools │
  │   - Masonry showcase feed    │ │  - Background Removal      │
  │   - 1-Click Remix            │ │  - 4K Ultra-Res Upscale    │
  │   - Daily Share Rewards (+10¢│ │  - Inpaint / Erase (v2)    │
  └──────────────────────────────┘ └────────────────────────────┘
```

---

## 2. Functional Modules Specification

### 2.1 Module 1: Deep Media Studio Workspace
- **Model Capabilities Dynamic Mapping (`ModelCapability`)**:
  - Automatically synchronizes allowed aspect ratios, durations, and quality modes based on selected model:
    - **FLUX 1.1 Pro**: `1:1`, `16:9`, `9:16`, `4:3`, `3:4`; supports seed and reference image; $0.04 / image.
    - **DALL-E 3**: `1:1`, `16:9`, `9:16`; high adherence; $0.08 / image.
    - **Recraft v3**: `1:1`, `16:9`, `9:16`, `4:3`; supports negative prompt; $0.035 / image.
    - **Runway Gen-3 Alpha**: Durations `5s`, `10s`; ratios `16:9`, `9:16`; modes `720p HD`, `1080p Full HD`; supports first frame (I2V); $0.50 / generation.
    - **Kling 1.5**: Durations `5s`, `10s`; ratios `16:9`, `9:16`, `1:1`; modes `Standard`, `Professional`; supports negative prompt; $0.40 / generation.
    - **Luma Dream Machine**: Duration `5s`; ratios `16:9`, `9:16`, `4:3`, `21:9`; $0.60 / generation.
- **Form Controls & Layout Alignment**:
  - 5:7 symmetrical column layout. Form elements strictly share baseline heights, paddings, and label typography.
- **Expert Configuration**:
  - **Negative Prompt**: Dynamic collapsible drawer according to model capability.
  - **Seed Generator**: Manual integer input with 🎲 Dice randomizer button.
  - **Reference Image (I2V / I2I)**: Image attachment slot with preview thumbnail and removal action.

### 2.2 Module 2: Prompt Recipes & Presets Picker
- **Non-Intrusive Modal Architecture**:
  - The studio workspace avoids top-banner pushdown by isolating preset browsing into a non-modal trigger button (`✨ Browse Recipes`) above the prompt textarea.
  - Opening the trigger reveals an interactive, searchable modal featuring:
    - Real-time keyword filter (matching prompt, title, and tags).
    - Category pills: `All`, `🎮 Game Assets`, `📸 Photography`, `🎬 Motion Video`, `🎨 Art`.
    - High-resolution visual output cards illustrating expected results.
- **Prompt Lineage & Provenance Tracking**:
  - Applying a preset injects a provenance badge: `[🌱 Forked from: Title (Model) ✕]`.
  - The badge persists even if the user heavily edits the prompt text, tracking derivative works and crediting parent recipes upon publication.

### 2.3 Module 3: Post-Processing Value-Add Tools
- **Background Removal**:
  - Removes background from generated assets; toggles container to transparent checkerboard grid with drop shadow.
- **4K Ultra-Res Upscaling**:
  - Enhances asset resolution with visual badge overlay (`4K Ultra-Res`).
- **Inpainting / Erase**:
  - Reserved UI action bar slot for brush canvas editor in subsequent release.

### 2.4 Module 4: Community Gallery & Creator Rewards (UGC)
- **Standalone Route (`/gallery`)**:
  - Multi-dimensional filtering: Source (`All`, `Official Recipes`, `Community Showcase`), Modality (`Images`, `Videos`), Topic Categories, and Model Engine.
  - Hover-to-play silent loop video cards.
  - Interactive full-screen Lightbox with prompt inspector, copy-to-clipboard, seed details, and direct remix trigger.
- **1-Click Remix**:
  - Copies prompt, negative prompt, aspect ratio, model, and lineage metadata directly into `/generate`.
- **Share-to-Earn Rewards Hook**:
  - Publishing a generated output to the gallery rewards creators with **10 Cents ($0.10)** credited directly to their single ledger balance.
  - Enforces a hard limit of **5 claims per day per user** ($0.50/day max) via single-ledger idempotency keys.
