# 05 — Read-only admin feature status

## Outcome

An ADMIN can inspect which backend feature settings are configured on/off and understand why that is different from account eligibility, content readiness and provider availability. This is a **feature status** panel, not a switches editor.

Read `00-coordination.md`, `session-prompts.md`, `backend/CLAUDE.md`, backend `security/{AdminRoles,AdminAuthorizationFilter}.java`, `web/AdminConsoleController.java`, relevant bound properties, `web/AdminRolesIntegrationTest` under tests, and app `lib/admin.tsx`, `pages/admin/AdminLayout.tsx`, `App.tsx` and admin E2E fixtures.

## Only permitted backend addition

Add `GET /v1/admin/features`, ADMIN-only through the existing `@AdminRoles` pattern and existing active/verified/2FA checks. Use a deliberately fixed DTO, derived from already-bound property objects. Exact public shape:

```json
{
  "inputs": { "enabled": false, "allow_people": false },
  "community": { "enabled": false },
  "byok": { "enabled": false },
  "routing_policies": { "enabled": false, "allow_list_restricted": false },
  "referrals": { "enabled": false },
  "mcp": { "enabled": true }
}
```

Derivation:

- Inputs and people: existing InputsProperties configuration.
- Community: existing CommunityProperties enabled setting.
- BYOK: whether configured ByokProperties providers is nonempty; this does not mean a provider is healthy or every modality is supported.
- Routing: existing user-policy enabled boolean; restricted is whether its configured allowlist is nonempty, never membership or a count.
- Referrals: `ReferralProperties.enabled()` (either configured reward greater than zero).
- MCP: `McpProperties.enabled()` (on by default, but configurable off). Do not invent `MCP_ENABLED` environment wiring; read the actual bound configuration.

Verify exact class/accessor names in current source. Preserve all actual defaults. No serializing property beans, arbitrary maps of environment values, remote provider probes, writes, runtime overrides, persistence, migrations or new customer endpoint. Do not change existing billing/usage/conversation DTOs.

The response must contain no secret, credential, allowlist address/count, provider-key status/last4, internal URL, bootstrap/admin identity, arbitrary environment variable, or health/readiness assertion. The configured booleans are the whole response.

## Admin UI

- Add `/admin/features` using the existing console shell. Show its navigation entry only for role ADMIN, not SUPPORT/FINANCE or an unresolved role.
- Present configuration-only status and a clear explanation: customer eligibility comes from existing per-user endpoints; gallery/community content and marketing build flags are separate; changing configuration uses the existing deployment process/restart, outside this UI.
- No switches, Save/Apply buttons, enable links, embedded values, or remote write calls. A labelled Refresh may repeat only the GET.
- Handle loading, denied/expired session, failed fetch and retry without misleading all-green defaults. An unavailable field/request never means enabled.
- Keep existing admin 2FA enrollment/wait behavior and role-specific access. Backend authorization is the real boundary; hiding a link alone is insufficient.

Use a dedicated app feature-status API helper and page. This stream owns the new App.tsx route and admin navigation hunk; 01 does not edit them.

## Acceptance and evidence

1. ADMIN with required account/2FA state gets 200 with exactly the fixed schema; defaults match current bound settings, with tests for alternate values.
2. No session gets 401; USER/SUPPORT/FINANCE and API-key callers cannot access it; an active, verified ADMIN lacking 2FA retains the existing `two_factor_required` behavior. SUPPORT/FINANCE are denied by the role check, irrespective of their 2FA state. Assert actual repository error contracts.
3. Update `AdminRolesIntegrationTest`: it inventories every admin route and exact role annotations. The new route must be included, rather than weakening the inventory.
4. POST/PUT/PATCH/DELETE never succeed. GET/Refresh causes no mutation or provider/network probe.
5. Redaction tests populate synthetic secret-like configuration/allowlist values and prove none appear; assert exact response fields rather than testing just absence of one token. No real credentials are used.
6. Admin page/navigation work on desktop/mobile, light/dark; non-ADMIN and unresolved-role states show no feature-status entry, and direct routes enforce access.
7. Focused endpoint/role tests and full `mvn -B verify` pass; app lint/build and focused admin E2E pass; final latest-head CI is green. Include safe synthetic screenshots and response-shape evidence only.
8. Add concise route documentation in the backend API reference where that reference is expected to describe admin endpoints; do not expose operational configuration through public docs.

Opening move: inventory the property accessors and admin authorization contract, then post the 3–5 bullet plan and derivation table. Write the unauthorized/schema-redaction tests before adding the endpoint.
