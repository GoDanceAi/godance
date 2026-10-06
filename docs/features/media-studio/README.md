# Godance Media Studio & Community Gallery

> **Feature Directory**: `docs/features/media-studio/`  
> **Branch**: `feat/media-studio`  
> **Status**: Implemented & Verified  
> **Stack**: React 18 / TypeScript / Tailwind CSS / Spring Boot 3 (WebFlux) / PostgreSQL

---

## 1. Quick Start & Local Dev UI Preview

To inspect and test the Media Studio and Gallery UI in local development:

### 1.1 Start Frontend Dev Server
```bash
cd app
npm install
npm run dev
```

The application starts at **`http://localhost:5173`**:
- **Media Studio Workspace**: [http://localhost:5173/generate](http://localhost:5173/generate)
- **Community Gallery & Presets**: [http://localhost:5173/gallery](http://localhost:5173/gallery)

### 1.2 Offline & Standalone Dev Fallbacks
The frontend is equipped with intelligent offline development fallbacks:
- **Authentication**: When running without an active backend instance, a mock developer user (`dev@godance.ai`, balance `$50.00`) is automatically hydrated so all protected routes (`/generate`, `/gallery`, `/dashboard`) are accessible without redirect loops.
- **Model Catalog**: If `/v1/models` is unreachable, the studio automatically falls back to curated image and video models (`flux-1.1-pro`, `dall-e-3`, `recraft-v3`, `runway-gen3-alpha`, `kling-v1.5`, `luma-dream-machine`).
- **Generation & Post-Processing Simulation**: Submitting a generation offline executes a realistic state simulation (Queued ➜ Generating ➜ Ready) with test output assets, allowing full verification of the layout, background removal (transparency checkerboard), 4K upscale badges, and share-reward workflows.

---

## 2. Architectural Coupling & Isolation Analysis

This feature is designed following **Orthogonal Decoupling** principles within the Godance monolith architecture.

### 2.1 Relationship with Core (`main`)
- **Database Schema**: Zero schema mutations or column alterations on existing tables (`users`, `generation_jobs`, `credit_ledger`, `usage_records`).
  - Added a single independent table `gallery_items` via Flyway migration `V12__gallery.sql`.
  - The foreign key `user_id` uses `ON DELETE SET NULL`, allowing official featured showcases to exist without dummy user rows.
- **Service Layer**:
  - The studio produces standard asynchronous tasks via existing `/v1/images/generations` and `/v1/videos/generations` endpoints.
  - The share reward mechanism calls the existing `CreditLedgerService.topUp` public method with `CreditSource.PROMO`, strictly honoring all single-ledger ACID accounting rules.
- **Blast Radius**: Isolated entirely to the `/v1/gallery` and `/v1/rewards/share` route namespace. Any error or maintenance in this module has zero impact on core chat completions, payment webhooks, or existing generation pipelines.

### 2.2 Relationship with Admin Dashboard (`feat/admin-dashboard`)
- **Complete Decoupling**: `feat/media-studio` and `feat/admin-dashboard` share **zero direct dependencies**:
  - Studio code never imports from `ai.godance.service.admin.*`.
  - Admin console code never imports from gallery services.
- **Natural Operational Synergy**: When users synthesize media assets in the Media Studio, records are written to `generation_jobs`. Operators navigating to `/admin/jobs` in the Admin Dashboard automatically observe these jobs, their latency, and costs without needing custom glue code.

---

## 3. Documentation Index

- [`spec.md`](./spec.md) - Full Product and Technical Specification.
- [`api-spec.md`](./api-spec.md) - REST API Contracts and Feasibility Analysis.
- [`ui-design.md`](./ui-design.md) - UI Architecture, Responsive Grids, and Design System Specs.
- [`user-guide.md`](./user-guide.md) - End-User Guide and Operations Manual.
