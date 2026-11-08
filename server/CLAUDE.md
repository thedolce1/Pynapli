# server — rules

- Routes are thin, domain services enforce product rules, repositories handle storage.
- Flyway migrations (`src/main/resources/db/migration/V*.sql`, run at startup): add new ones with the next sequential number; **never edit applied migrations** — write a follow-up migration instead.
- Server tests use JUnit Platform (Kotest runner), so `--tests` filters apply. All IT files share one `PynapliTestDatabase` container — new IT files use `PynapliTestDatabase.reset()`, not their own container.
- Auth/storage details (PBKDF2, hashed email/token storage, Redis TTL presence): `README.md`.
