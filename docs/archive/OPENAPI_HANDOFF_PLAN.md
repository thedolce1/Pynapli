# Pynapli OpenAPI Handoff Plan

> **Archived 2026-11-09.** Superseded; kept for history only. Current guidance: [docs/README.md](../README.md).

Last updated: 2026-04-24

## Purpose

This document defines how Pynapli should turn the API contract into a machine-readable OpenAPI schema during implementation.

Core rule:

> The human API spec defines product intent. OpenAPI defines executable client/server contract.

## Source Inputs

Use:

- [API_CONTRACT_SPEC.md](../API_CONTRACT_SPEC.md)
- [DATA_MODEL_SPEC.md](../DATA_MODEL_SPEC.md)
- [BACKEND_ARCHITECTURE_SPEC.md](../BACKEND_ARCHITECTURE_SPEC.md)
- [MOBILE_APP_ARCHITECTURE_SPEC.md](../MOBILE_APP_ARCHITECTURE_SPEC.md)

## OpenAPI File

Recommended path once implementation starts:

```text
api/openapi.yaml
```

Version:

```yaml
openapi: 3.1.0
info:
  title: Pynapli API
  version: 0.1.0
```

## Generation Strategy

Preferred:

- Define DTOs in Kotlin with `kotlinx.serialization`.
- Generate OpenAPI from Ktor routes where practical.
- Validate generated schema against hand-authored examples.

Acceptable early-stage:

- Hand-author `openapi.yaml` for stable endpoints.
- Keep DTO names aligned with Kotlin.
- Add generation later.

## Required Endpoint Groups

Include:

- Auth
- Me/Profile
- Settings
- Location
- Grid
- Swipe
- Psync
- Playing Tonight
- Activity
- Messages
- Albums
- Partner Links
- Subscriptions
- Reports/Safety
- Verification
- Notifications

Admin API should be separate:

```text
api/admin-openapi.yaml
```

## Schema Rules

Every endpoint must define:

- Auth requirements
- Request body
- Response body
- Error responses
- Rate limit behavior where relevant
- Idempotency requirement where relevant

Shared schemas:

- `ApiError`
- `Page`
- `GridCard`
- `SwipeCard`
- `ActivityEvent`
- `Profile`
- `ChatThread`
- `Message`
- `AlbumGrant`
- `Entitlement`

## Security Schemes

Required:

```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
```

Optional:

- App Check header
- Admin MFA/role scheme in admin schema

## Error Responses

Standardize:

- 400 validation
- 401 unauthenticated
- 403 forbidden
- 404 not found
- 409 conflict/stale state
- 422 verification required/action limited
- 429 rate limited
- 500 server error

All errors use `ApiError`.

## Contract Tests

Required:

- Ktor route tests validate response bodies match OpenAPI.
- Client generated/typed DTOs compile against schema.
- Breaking changes require schema version update.

## Versioning

Initial:

- `/v1`

Rules:

- Additive fields are allowed.
- Removing/renaming fields is breaking.
- Changing meaning of fields is breaking.
- Deprecated fields should remain through at least one app release cycle.

## Documentation Examples

Every major endpoint should include examples for:

- Success
- Validation error
- Entitlement required
- Verification required
- Rate limited

## Do Not Implement

- Do not let OpenAPI drift from Ktor routes.
- Do not expose admin endpoints in consumer API docs.
- Do not document sensitive internal fields.
- Do not publish risk score schema to public clients.

