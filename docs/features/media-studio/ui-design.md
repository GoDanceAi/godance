# Godance Media Studio & Community Gallery - UI & Interaction Design Specification

> **Document Version**: v1.1.0  
> **Branch**: `feat/media-studio`  
> **Design Tokens**: Godance "Unified Ledger" Palette (`app/src/index.css`)

---

## 1. Design Philosophy & Problems Solved

1. **Elimination of Vertical Workspace Pushdown**:
   Previous iterations placed static preset cards across the top of the canvas, pushing the generation form below the fold. The redesign isolates inspiration browsing into a non-intrusive modal picker (`✨ Browse Recipes`), preserving a focused, distraction-free 5:7 responsive workspace.
2. **Strict Form & Quality Alignment**:
   Standardized form control heights (`input`, `btn-lg`, `btn-sm`), label baselines, and grid proportions across both Image and Video modes.
3. **UGC Retention & Provenance Visibility**:
   Applied recipes display an explicit, removable lineage token (`[🌱 Forked from: ... ✕]`). Completed generations feature prominent post-processing actions and a share reward modal that immediately reflects earnings in the navigation balance.

---

## 2. Layout & Wireframe Architecture

### 2.1 Media Studio Workspace Layout (`/generate`)

```
+-----------------------------------------------------------------------------------------------+
|  Header: [Logo]  [Dashboard]  [Chat]  [Studio*]  [Gallery]        [Balance: $50.00]  [🎁 1/5]  |
+-----------------------------------------------------------------------------------------------+
| Left Controls Panel (5 Columns)               | Right Output & Canvas Panel (7 Columns)       |
| -------------------------------------------   | -------------------------------------------   |
| [🖼️ Image Generation]  [🎬 Video Generation] | Header: Output Status (Idle / Rendering / Ready) |
|                                               |                                               |
| Model Engine: Dropdown + Badges + Pricing     | Visual Canvas:                                |
|                                               | - Aspect Ratio Border Container               |
| Prompt Section:                               | - Transparency Checkerboard (on BG remove)    |
| - Header: Label + [✨ Browse Recipes] Trigger  | - Video HTML5 Player / Zoomable Image         |
| - Provenance Badge: [🌱 Forked from: ... ✕]   |                                               |
| - Multiline Textarea (Be descriptive)         | Post-Processing Suite (Rendered upon DONE):    |
|                                               | - [✂️ Remove Background (Cutout)]             |
| Aspect Ratio: [1:1] [16:9] [9:16] [4:3] Grid  | - [🔍 Upscale to 4K (Ultra Detail)]           |
|                                               | - [🎨 Inpaint / Erase]                        |
| Symmetrical Grid:                             |                                               |
| - Duration / Outputs (Aligned)                | Bottom Action Bar:                            |
| - Render Quality / File Format (Aligned)      | - Asset Details & Actual Cost                 |
|                                               | - [Download Full Asset ↗]                     |
| Reference Image Slot: Base image thumbnail    | - [🚀 Publish & Share (+10¢)] Reward Action   |
| Collapsible: Negative Prompt & Seed (🎲 Dice) |                                               |
|                                               |                                               |
| [ ✨ Generate Button (~$0.04) ]               |                                               |
+-----------------------------------------------------------------------------------------------+
```

---

### 2.2 Preset Picker Modal Architecture (`✨ Browse Recipes`)

- **Trigger**: Click `✨ Browse Recipes` above the prompt textarea.
- **Overlay**: Full-screen backdrop blur (`bg-black/70 backdrop-blur-sm`).
- **Container**: Max width `4xl`, height capped at `85vh` with inner scroll.
- **Header**: Search input with instant keyword matching + Category filter chips (`All`, `🎮 Game`, `📸 Photo`, `🎬 Video`, `🎨 Art`).
- **Card Grid**: 3-column responsive card layout. Each card displays:
  - Expected output thumbnail with high-contrast badge.
  - Title and prompt snippet.
  - Recommended model and aspect ratio.
- **Action**: Clicking any card immediately hydrates prompt, negative prompt, model, ratio, duration, and mounts the lineage token before closing the modal.

---

### 2.3 Community Gallery Layout (`/gallery`)

```
+-----------------------------------------------------------------------------------------------+
| Header: Gallery Title & Description + [✨ Open Studio →] Navigation                           |
| --------------------------------------------------------------------------------------------- |
| Search & Filter Toolbar:                                                                      |
| - Simple Search Input: "Search prompts, authors, styles..."                                   |
| - Model Filter Dropdown: "All Models", "FLUX 1.1 Pro", "Runway Gen-3", etc.                  |
| - Source Tabs: [All Assets]  [⭐ Official Recipes]  [👥 Community UGC]                         |
| - Type Badges: [All]  [🖼️ Images]  [🎬 Videos]                                                 |
| - Topic Badges: [All]  [🎮 Game]  [📸 Photo]  [🎬 Motion]  [🎨 Art]                           |
| --------------------------------------------------------------------------------------------- |
| 3-Column Responsive Masonry Showcase Feed:                                                    |
|                                                                                               |
| +-------------------------+ +-------------------------+ +-------------------------+           |
| | [Video Poster / Preview]| | [Image Thumbnail]       | | [Video Poster / Preview]|           |
| | (Hover to play video)   | |                         | | (Hover to play video)   |           |
| | Title & Prompt Snippet  | | Title & Prompt Snippet  | | Title & Prompt Snippet  |           |
| | Author · 2h ago         | | Author · 4h ago         | | Author · 6h ago         |           |
| | [❤️ 142]   [⚡ Remix]    | | [❤️ 98]    [⚡ Remix]    | | [❤️ 215]   [⚡ Remix]    |           |
| +-------------------------+ +-------------------------+ +-------------------------+           |
+-----------------------------------------------------------------------------------------------+
```

---

### 2.4 Lightbox Modal Inspection (`/gallery`)

- Clicking any gallery card launches an edge-to-edge inspection dialog:
  - **Left Section**: Full-resolution asset view (responsive video player or lossless image zoom).
  - **Right Section (420px)**:
    - Author attribution and timestamp.
    - Full prompt with **Copy Prompt** button (includes clipboard feedback toast).
    - Seed, aspect ratio, duration, and model technical parameters.
    - Lineage attribution (e.g. `🌱 Forked from 16-Bit Pixel Hero`).
    - Primary CTA: **`⚡ Remix in Studio`** (navigates with URL search parameters to `/generate`).
