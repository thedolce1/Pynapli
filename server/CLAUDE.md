# server — rules

- Routes are thin, domain services enforce product rules, repositories handle storage.
- Subscription entitlements and trust/safety decisions are server-validated.
- Auth uses server-owned **opaque** access/refresh tokens, PBKDF2 password hashing, hashed email/token storage. Current location is stored server-side and is **not** echoed back to clients. Presence uses Redis TTL keys.
- WebSocket events are convenience-only; HTTP/state fetch remains authoritative.
- Flyway migrations (`src/main/resources/db/migration/V*.sql`, run at startup): add new ones with the next sequential number; **never edit applied migrations** — write a follow-up migration instead.
- Server tests use JUnit Platform (Kotest runner), so `--tests` filters apply. All IT files share one `PynapliTestDatabase` container — new IT files use `PynapliTestDatabase.reset()`, not their own container.
