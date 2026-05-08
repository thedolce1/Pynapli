# @pynapli/web

The Pynapli marketing site (`pynapli.com`) and companion PWA
(`web.pynapli.com`), served by a single Next.js 15 app. Talks to the
existing Ktor `:server` over the `/v1` REST API through a Backend-for-
Frontend (BFF) layer that swaps an HttpOnly cookie for a bearer token, so
opaque session tokens never touch JavaScript.

See `../WEB_AND_PWA_SPEC.md` for the architecture, scope, and phasing.

## Stack

- Next.js 15 (App Router) on Node.js 20+
- React 19
- Tailwind CSS 4 (CSS-first config in `app/globals.css`)
- TypeScript 5
- pnpm workspace at the repo root

## Phase 0 status

What's wired up:

- Marketing homepage stub at `pynapli.com/`
- Login + signup at `web.pynapli.com/login`, `/signup`, with a hard
  age gate on signup
- BFF auth proxy: `/api/auth/{login,signup,refresh,logout,csrf}` and a
  generic `/api/[...proxy]` that forwards everything else to Ktor
- HttpOnly access + refresh cookies, JS-readable CSRF cookie
  (double-submit pattern)
- Host-based middleware: `web.*` → app shell with auth gate +
  `noindex`; `pynapli.com` → marketing
- Restrained dark design tokens mirrored from `../DESIGN_TOKENS.md`
- Server CORS allowlist updated in
  `../server/src/main/kotlin/com/pynapli/server/plugins/HttpPlugin.kt`

What's stubbed (intentionally, ships in later phases):

- Marketing pages other than `/` (Pricing, About, Community, Privacy,
  Terms, Careers, Press, Cities, Contact) — Phase 1.
- Authed surfaces other than `/messages` and `/profile` placeholders —
  Phases 2-3.
- Service worker, manifest, install prompt — Phase 2.

## Local development

### One-time setup

```bash
# At repo root:
corepack enable
corepack prepare pnpm@latest --activate

# Install web deps:
cd web
pnpm install
cp .env.example .env.local
```

The `web/.env.local` defaults assume Ktor is running on
`http://localhost:8080` (i.e. `./gradlew :server:run` from the repo root,
with Docker Compose up — see the root `README.md`).

### Run the dev server

```bash
# In web/:
pnpm dev
```

- Marketing site: <http://localhost:3000>
- App surface: <http://web.localhost:3000>

`web.localhost` is automatically resolved to `127.0.0.1` by most
browsers, so the host-based middleware works without editing
`/etc/hosts`. If your browser doesn't, add:

```
127.0.0.1  web.localhost
```

### Type check + lint

```bash
pnpm typecheck
pnpm lint
```

## Auth flow

```
Browser                           Next.js BFF                       Ktor
───────                           ───────────                       ─────
POST /api/auth/login    ──────►   POST /v1/auth/email/login  ──────►
                                   200 { accessToken, refreshToken, user }
                        ◄──────   Set-Cookie: pyn_at, pyn_rt, pyn_csrf
                                   Body: { user }

GET /api/me             ──────►   reads pyn_at cookie
                                   GET /v1/me with Authorization: Bearer
                                                              ──────►
                                                              ◄──────
                        ◄──────   200 { ... }

(after 15 min)
GET /api/me             ──────►   reads expired pyn_at
                                   GET /v1/me → 401
                        ◄──────   401  (client should refresh and retry)

POST /api/auth/refresh  ──────►   reads pyn_rt cookie
                                   POST /v1/auth/refresh    ──────►
                                                              ◄──────
                        ◄──────   Set-Cookie: pyn_at, pyn_rt (rotated)

POST /api/auth/logout   ──────►   POST /v1/auth/logout      ──────►
                        ◄──────   clears cookies
```

CSRF: every non-GET to `/api/*` (except the auth endpoints, which use
SameSite + cookie-only payloads) must include the value of the
`pyn_csrf` cookie in an `X-CSRF-Token` header. Use `/api/auth/csrf` to
mint one if you don't have it yet.

## Deploying to Vercel (Phase 0)

1. Connect the repo to a new Vercel project.
2. Set the **Root Directory** to `web`.
3. Add domains in Vercel:
   - `pynapli.com` (production)
   - `www.pynapli.com` (redirect to apex)
   - `web.pynapli.com` (production)
4. Set environment variables:

   | Name | Value (prod) |
   |---|---|
   | `PYNAPLI_API_BASE_URL` | `https://api.pynapli.com` |
   | `PYNAPLI_COOKIE_DOMAIN` | `.pynapli.com` |
   | `NEXT_PUBLIC_SITE_URL` | `https://pynapli.com` |
   | `NEXT_PUBLIC_APP_URL` | `https://web.pynapli.com` |

5. Deploy.

## Structure

```
web/
  app/
    (marketing)/        # pynapli.com surfaces (root /)
    (auth)/             # /login, /signup, /verify, /forgot
    (app)/              # web.pynapli.com authed surfaces
    api/
      auth/             # /api/auth/{login,signup,refresh,logout,csrf}
      [...proxy]/       # generic authed BFF proxy
  components/
    marketing/          # Nav, Footer, AgeGate
    ui/                 # Button, Input
  lib/
    api/upstream.ts     # server-only Ktor caller
    auth/cookies.ts     # cookie config
    auth/csrf.ts        # CSRF helpers
    designTokens.ts     # mirror of DESIGN_TOKENS.md
  middleware.ts         # host routing + auth gate + noindex on app host
```
