# Godance Admin Console - REST API Specification

> **Base Path**: `/v1/admin`  
> **Security Gate**: `AdminAuthorizationFilter` (Bearer JWT with Allowlisted Email)  
> **Transport**: JSON / HTTP/1.1 over TLS

---

## 1. Authentication & Security Policy

All endpoints under `/v1/admin/**` are strictly protected:
- **JWT Authorization**: Requests must include an `Authorization: Bearer <jwt>` header.
- **Email Allowlist Gate**: `AdminAuthorizationFilter` verifies that `jwt.claims["email"]` matches the configured administrator allowlist. Unauthorized requests receive `HTTP 403 Forbidden`.
- **Async Execution**: Netty event loops delegate blocking JPA transactions to `Schedulers.boundedElastic()`.

---

## 2. API Endpoints Catalog

### 2.1 Identity & Session
#### `GET /v1/admin/me`
Echoes the authenticated administrator's profile.
- **Request**: Empty body
- **Response `200 OK`**:
```json
{
  "email": "admin@godance.ai"
}
```

---

### 2.2 User Governance & Accounts

#### `GET /v1/admin/users`
Fuzzy searches users by email substring or exact UUID (capped at 20 rows).
- **Parameters**: `q` (string, optional)
- **Response `200 OK`**:
```json
{
  "users": [
    {
      "id": "b34c19ef-9a71-48ea-b2f0-c585ee715806",
      "email": "developer@agency.io",
      "fullName": "Alice Dev",
      "role": "USER",
      "active": true,
      "creditBalanceCents": 12500,
      "dailyCreditCapCents": 5000,
      "monthlyCreditCapCents": 20000,
      "apiKeyCount": 3,
      "createdAt": "2026-09-15T08:30:00Z"
    }
  ]
}
```

#### `GET /v1/admin/users/{id}/ledger`
Retrieves paged ledger transaction history for a specific user.
- **Parameters**: `page` (int, default `0`)
- **Response `200 OK`**:
```json
{
  "entries": [
    {
      "id": "e49281a0-1234-4b91-9e77-123456789abc",
      "deltaCents": 5000,
      "source": "STRIPE",
      "sourceRef": "cs_live_12345",
      "note": "Top-up 50 USD",
      "occurredAt": "2026-10-01T12:00:00Z"
    }
  ],
  "page": 0,
  "totalPages": 1,
  "totalEntries": 1
}
```

#### `POST /v1/admin/users/{id}/credit-adjust`
Manually adjusts user credit balance (positive credit or negative clawback).
- **Request Body**:
```json
{
  "amountCents": 2500,
  "note": "Customer support promo grant",
  "idempotencyKey": "ticket-99482"
}
```
- **Response `200 OK`**: Returns updated `UserView` and created `LedgerEntryView`.
- **Errors**: `400 Bad Request` (zero amount or empty note), `404 Not Found`.

#### `PATCH /v1/admin/users/{id}/status`
Freezes or reactivates a user account.
- **Request Body**:
```json
{
  "active": false,
  "reason": "Suspicious automated concurrent traffic detected"
}
```
- **Response `200 OK`**: Updated `UserView`.

#### `PATCH /v1/admin/users/{id}/caps`
Configures daily and monthly spending limits (cents).
- **Request Body**:
```json
{
  "dailyCreditCapCents": 10000,
  "monthlyCreditCapCents": 50000
}
```
- **Response `200 OK`**: Updated `UserView`.

#### `GET /v1/admin/users/{id}/usage`
Retrieves granular per-request invocation traces for a user.
- **Parameters**: `page` (int, default `0`), `size` (int, default `20`)
- **Response `200 OK`**:
```json
{
  "records": [
    {
      "id": "c0182394-bb99-4c12-8234-9182348123ab",
      "modality": "TEXT",
      "modelName": "claude-3-5-sonnet-20241022",
      "provider": "ANTHROPIC",
      "inputTokens": 1200,
      "outputTokens": 450,
      "costCents": 12,
      "region": "US_EAST",
      "cacheHit": false,
      "fallbackUsed": false,
      "requestId": "req-99182746",
      "recordedAt": "2026-10-05T14:22:10Z"
    }
  ],
  "page": 0,
  "totalPages": 5,
  "totalRecords": 98
}
```

#### `GET /v1/admin/users/{id}/keys`
Lists active and inactive API keys owned by a user.
- **Response `200 OK`**: Array of `AdminApiKeyView`.

#### `DELETE /v1/admin/keys/{id}`
Immediately deactivates and revokes an API key.
- **Response `204 No Content`**.

---

### 2.3 Payments & Exception Handling

#### `GET /v1/admin/payments`
Paged global payment transaction ledger across all users.
- **Parameters**: `source` (optional: `ALL`, `STRIPE`, `NOWPAYMENTS`, `REFUND`, `ADMIN_ADJUSTMENT`), `page` (int), `size` (int).
- **Response `200 OK`**:
```json
{
  "transactions": [
    {
      "id": "tx-881293",
      "userId": "b34c19ef-9a71-48ea-b2f0-c585ee715806",
      "userEmail": "developer@agency.io",
      "deltaCents": 5000,
      "source": "STRIPE",
      "sourceRef": "cs_live_992837",
      "idempotencyKey": "stripe-evt_12345",
      "note": "Stripe Checkout: 50 USD",
      "occurredAt": "2026-10-05T18:00:00Z"
    }
  ],
  "page": 0,
  "totalPages": 12,
  "totalTransactions": 234
}
```

#### `POST /v1/admin/payments/reconcile`
Manually credits a user for an un-webhooked or offline payment. Enforces strict idempotency.
- **Request Body**:
```json
{
  "userId": "b34c19ef-9a71-48ea-b2f0-c585ee715806",
  "amountCents": 5000,
  "paymentRef": "cs_live_992837",
  "note": "Manual reconciliation for delayed webhook"
}
```
- **Response `200 OK`**: Returns created `AdminPaymentTransactionView`.
- **Errors**: `400 Bad Request`, `409 Conflict` (if `paymentRef` was already credited).

#### `POST /v1/admin/payments/refund`
Processes a financial refund or credit clawback.
- **Request Body**:
```json
{
  "userId": "b34c19ef-9a71-48ea-b2f0-c585ee715806",
  "amountCents": 2000,
  "paymentRef": "re_live_12345",
  "reason": "Customer cancellation agreement"
}
```
- **Response `200 OK`**: Returns created `AdminPaymentTransactionView`.

#### `GET /v1/admin/payments/webhook-health`
Aggregates webhook health diagnostics over the past 24 hours.
- **Response `200 OK`**:
```json
{
  "totalRejected": 2,
  "totalIgnored": 14,
  "totalAutoRechargeFailed": 1,
  "totalDuplicate": 5,
  "recentExceptions": [
    {
      "id": "aud-123",
      "userId": null,
      "eventType": "billing.webhook.rejected",
      "eventDetail": {
        "reason": "Invalid signature header",
        "clientIp": "54.187.205.235"
      },
      "ipAddress": "54.187.205.235",
      "outcome": "FAILURE",
      "createdAt": "2026-10-06T02:15:00Z"
    }
  ]
}
```

---

### 2.4 Multimodal Generation Jobs

#### `GET /v1/admin/jobs`
Inspects async media generation tasks queue.
- **Parameters**: `status` (`QUEUED`, `RUNNING`, `DONE`, `FAILED`), `modality` (`IMAGE`, `VIDEO`), `page`, `size`.
- **Response `200 OK`**: Paged array of `AdminJobView`.

---

### 2.5 Security Audit Logs

#### `GET /v1/admin/audit-logs`
Queries chronological administrative and security audit events.
- **Parameters**: `eventType` (optional substring/type, e.g. `admin.user.status.updated`), `page`, `size`.
- **Response `200 OK`**: Paged array of `AdminAuditLogView`.
