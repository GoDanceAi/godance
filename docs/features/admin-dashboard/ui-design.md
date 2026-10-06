# Godance Admin Console - UI/UX Architecture & Design Specification

> **Target Framework**: React 18, Tailwind CSS, TypeScript, Vite  
> **Location**: `docs/features/admin-dashboard/ui-design.md`  
> **Design Language**: Godance Unified Ledger Design Tokens (`[data-theme="dark"]` + light mode)

---

## 1. Information Architecture & Layout Structure

The Admin Console adopts a dedicated **Operator Workspace** distinct from the consumer-facing web application.

```
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [Logo] Godance  ADMIN CONSOLE     │  ● Gateway Online (18ms)  │   Go to App ↗   ⚙️  admin@godance.ai [SUPERADMIN]  Log out │
├───────────────┬────────────────────────────────────────────────────────────────────────────────────────┤
│ Admin Console │  Admin / Financial Overview                                              Operator Space │
│ [« Collapse]  │                                                                                        │
├───────────────┼────────────────────────────────────────────────────────────────────────────────────────┤
│ ANALYTICS     │                                                                                        │
│  📊 Overview  │  [ KPI Cards: Settled Volume / Transactions / Cost / Anomalies ]                       │
│  💳 Payments  │                                                                                        │
│ USER GOV      │  [ Interactive Data Tables / Filter Bars / Modal Drawers ]                             │
│  👥 Users     │                                                                                        │
│ OPERATIONS    │                                                                                        │
│  ⚡ Jobs       │                                                                                        │
│  📡 Providers │                                                                                        │
│ SECURITY      │                                                                                        │
│  📜 Audits    │                                                                                        │
│               │                                                                                        │
│ Gateway: ●    │                                                                                        │
└───────────────┴────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Navigation & Layout Mechanics

### 2.1 Operator Top Header (`Layout.tsx`)
When the URL matches `/admin/*`, the application unmounts the consumer header and mounts the **Dedicated Admin Header**:
1. **Total Isolation from Consumer CTAs**:
   - The marketing "Start free" button, user credit balance, and consumer navigation links (`Dashboard`, `Chat`, `API keys`, `Billing`, `Settings`) are completely suppressed.
2. **Live Gateway Connectivity Heartbeat (`ServerStatusBadge.tsx`)**:
   - Performs automated 30-second heartbeats against `/v1/status`.
   - Displays real-time status:
     - 🟢 `● Gateway Online (xx ms)`
     - 🟡 `● Gateway Degraded (N alerts)`
     - 🔴 `● Server Offline`
   - Clicking the badge navigates to `/admin/providers`.
3. **Operator Identity & Role Badge**:
   - Displays current admin email (e.g. `admin@godance.ai`).
   - Prominently showcases the role pill: `[SUPERADMIN]` or `[ADMIN]`.
4. **Admin Quick Actions**:
   - `Go to App ↗`: One-click toggle back to the user app workspace (`/dashboard`).
   - `⚙️ Operator Profile Button`: Opens [`AdminProfileModal.tsx`](file:///Users/jiangyouming/Downloads/pros/projects/godance/app/src/components/AdminProfileModal.tsx).
   - `ThemeToggle`: Sun/Moon toggle for light and dark modes.
   - `Log out`: Secure session termination.

### 2.2 Collapsible Sidebar (`AdminLayout.tsx`)
- **Width States**:
  - Expanded: `w-60` (displays full labels and category section titles).
  - Collapsed: `w-16` (displays centered SVG icons with tooltips).
- **Pixel-Perfect Height Alignment**:
  - Both the Sidebar Header ("Admin Console") and the Main Content Breadcrumb Bar ("Admin / *") are anchored to a strict **`h-12` (48px)** height.
  - Guarantees that the bottom dividing border line (`border-b border-border`) extends across the viewport in a continuous, un-broken line.
- **Smooth Group Transition**:
  - Section titles animate their collapse using CSS transitions:
    `overflow-hidden transition-all duration-200 ease-in-out ${collapsed ? 'max-h-0 opacity-0 mb-0' : 'max-h-5 opacity-100 mb-1.5'}`
  - Prevents abrupt layout shift (jank) and icon jumping when toggling sidebar states.
  - In collapsed mode, subtle dividers (`border-t border-border/40`) maintain logical visual grouping.

---

## 3. Design System & Theme Adaptability

All admin views strictly adhere to Godance's semantic CSS design tokens, enabling complete dual-theme compatibility:

| Semantic Token | Light Mode Value | Dark Mode Value (`[data-theme="dark"]`) | Description |
| :--- | :--- | :--- | :--- |
| `--color-bg` | `#ffffff` | `#0b0f19` | Page background |
| `--color-surface` | `#ffffff` | `#111827` | Card, sidebar, & modal container background |
| `--color-surface-alt` | `#f9fafb` | `#1f2937` | Table header, subtle container, & input background |
| `--color-fg` | `#111827` | `#f9fafb` | Primary heading and high-contrast text |
| `--color-fg-muted` | `#4b5563` | `#9ca3af` | Secondary labels, descriptions, and breadcrumbs |
| `--color-fg-faint` | `#9ca3af` | `#6b7280` | Subtle captions, timestamps, and borders |
| `--color-border` | `#e5e7eb` | `#374151` | Default dividers and table borders |
| `--color-primary` | `#7c3aed` (Violet-600) | `#8b5cf6` (Violet-500) | Primary brand buttons and active nav links |

---

## 4. Offline Developer Experience (Dev Mock Strategy)

To allow frontend engineering without spinning up PostgreSQL, Redis, and backend microservices:
1. Every admin page checks `import.meta.env.DEV` on fetch error.
2. In development mode without a live backend connection:
   - Pages seamlessly load comprehensive, realistic mock datasets (`MOCK_TRANSACTIONS`, `MOCK_EXCEPTIONS`, `MOCK_JOBS`, `MOCK_PROVIDERS`).
   - Allows UI design iteration, modal verification, and layout testing in pure offline browser environments.
