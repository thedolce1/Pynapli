# WEB_AND_PWA_SPEC.md

Plan for the Pynapli marketing site (`pynapli.com`) and companion PWA
(`web.pynapli.com`). Native iOS/Android apps remain the primary product;
this spec describes the web surfaces that share the existing Ktor backend.

This file is a planning artifact — no web code has been written yet. When
implementation starts, code lives in `/web` and follows the structure below.

> Strategic posture inherited from the broader product specs: server is
> authoritative, dark/restrained design language, "private members club —
> not nightclub flyer," 18+ social lifestyle audience. Public marketing is
> SFW-with-implication. The real positioning surfaces post-signup.

---

## 1. Architecture at a glance

```
pynapli.com         (marketing, SSR/SSG, fully indexable)
web.pynapli.com     (authed PWA, paid-tier-gated discovery)
        │
        ▼
  Next.js (App Router) on Vercel
        │  ── /api/* (BFF: attaches bearer from HttpOnly cookie)
        ▼
  api.pynapli.com  → Ktor :server  → Postgres / Redis
```

Both subdomains are served by **one** Next.js app in `/web`. A middleware
routes by host: `web.*` → app shell, everything else → marketing. One
deploy, one CI, one design system, one auth client.

The subdomain `web.pynapli.com` follows Grindr's convention rather than
Tinder's `/app` path. Reads more naturally for a web product, cleaner
cookie scoping, cleaner PWA install behavior.

## 2. Repo layout

Add a single `web/` folder to the monorepo (peer to `server/`, `shared/`,
`composeApp/`, `androidApp/`):

```
web/
  app/
    (marketing)/                    # pynapli.com routes
      page.tsx                      # /
      about/page.tsx
      pricing/page.tsx
      community/page.tsx            # community guidelines (public)
      privacy/page.tsx              # rendered from PRIVACY_POLICY_DRAFT.md
      terms/page.tsx                # from TERMS_DRAFT.md
      careers/
        page.tsx
        [slug]/page.tsx             # individual roles
      press/page.tsx                # press kit, screenshots, brand assets
      cities/[city]/page.tsx        # /cities/toronto, /cities/gta, etc.
      contact/page.tsx
    (app)/                          # web.pynapli.com routes (auth-gated)
      layout.tsx                    # PWA shell, auth guard
      grid/page.tsx                 # paid-tier
      swipe/page.tsx                # paid-tier
      activity/page.tsx
      messages/page.tsx             # free-tier
      messages/[threadId]/page.tsx  # free-tier
      profile/page.tsx
      profile/edit/page.tsx
      albums/page.tsx               # paid-tier
      playing-tonight/page.tsx      # paid-tier
      psync/page.tsx                # paid-tier
      settings/(billing|sessions|privacy|notifications)/page.tsx
    (auth)/
      login/page.tsx
      signup/page.tsx
      verify/page.tsx               # age verification handoff
      forgot/page.tsx
    api/                            # BFF — proxies to Ktor with cookie→bearer swap
      auth/(login|signup|refresh|logout)/route.ts
      [...proxy]/route.ts           # generic authed pass-through
    sitemap.ts
    robots.ts
    manifest.ts
  components/
    marketing/{Hero,Footer,Nav,FeatureGrid,Testimonials,CTA}.tsx
    app/{GridCard,SwipeDeck,MessageList,Composer,...}.tsx
    ui/{Button,Input,Modal,Sheet,...}.tsx       # shadcn-style primitives
  lib/
    api/client.ts                   # typed fetch against /api proxy
    api/types.ts                    # generated from Ktor OpenAPI
    auth/session.ts                 # cookie helpers (server-only)
    csrf.ts
    seo.ts
    designTokens.ts                 # mirrored from DESIGN_TOKENS.md
  middleware.ts                     # host routing + auth guard for (app)
  public/
    icons/                          # PWA icons (192, 512, maskable)
    og/                             # OpenGraph cards
  tailwind.config.ts
  package.json
  next.config.ts
```

Add `pnpm` workspace at the repo root. Doesn't conflict with Gradle.
Keep `web/` out of Gradle entirely.

## 3. Domain & routing strategy

| Host | Renders | Indexed |
|---|---|---|
| `pynapli.com` | Marketing (`(marketing)` group) | Yes |
| `web.pynapli.com` | PWA (`(app)` + `(auth)` groups) | `noindex` |
| `api.pynapli.com` | Ktor (existing `:server`) | n/a |

Reserved subdomains (DNS only, configure when needed): `help.pynapli.com`,
`status.pynapli.com`, `blog.pynapli.com`.

Defensive domain purchases recommended:
- `pynapli.app` (typo target, useful for universal links later)
- `pynapli.co` (typo target)
- `get-pynapli.com` (phishing target, cheap insurance)

Skip: `pynapli.xxx`, `pynapli.adult` — actively hurt the SFW-with-implication
positioning.

## 4. Marketing site (pynapli.com)

**Top nav** (sticky, restrained): Pynapli wordmark · How it works · Pricing
· Community · Sign in · **Create account** (pineapple-gold accent button)

**Homepage sections — SFW-with-implication tone:**

1. **Hero.** Single-line promise. Working line: *"A social app built for
   adults who want autonomy — solo, together, or socially."* Restrained
   dark surface, one CTA. Sub-line nods to partner linking.
2. **The three modes.** Grid (for now), Swipe (for intent), Links (for
   trust). Three cards using existing dark/charcoal/gold tokens.
3. **What makes it different.** Partner linking explained without naming
   the lifestyle community on the public surface. Headline: *"Two accounts.
   One link. Zero merging."*
4. **Privacy block.** Server-authoritative, opaque tokens, location never
   echoed back, private albums per-grant, incognito.
5. **City strip.** "Now in Toronto & GTA. More cities soon." Lays
   groundwork for `/cities/*` pages.
6. **FAQ.** 6–8 entries. Includes "Who is this for?" answered with
   "open-minded adults, singles and partnered people" — the boundary of the
   SFW posture.
7. **Footer** (see §5).
8. **Age-gate modal** on first visit (18+ confirm, persisted in a
   non-essential cookie).

**Other marketing routes:**

- `/about` — generic mission/values page (no founder bios). Sections:
  Why Pynapli exists, How we think about consent, How we make money,
  How to reach us.
- `/pricing` — three columns: Free / Social $4.99 / Select $9.99. Linked-
  partner benefit called out as a separate row. Add-ons (Boost, Pineapple
  Like, Intro Note) listed below.
- `/community` — public render of `COMMUNITY_GUIDELINES.md`.
- `/privacy`, `/terms` — rendered from existing draft markdown via MDX.
- `/careers` — index lists open roles. Each role at `/careers/[slug]`.
  Even one or two real roles signals "real company."
- `/press` — logo SVG/PNG pack, screenshots, founder boilerplate, brand
  color hex codes. Self-serve.
- `/cities/[city]` — Toronto and GTA real, others rendered as "Coming
  to ___" pages with city-specific waitlist form. SEO + footprint without
  dishonesty.
- `/contact` — form posts to `/api/contact`.

## 5. Footer

Five-column footer, dark surface, restrained. The load-bearing
"professional polish" element.

| Product | Company | Resources | Legal | Get the app |
|---|---|---|---|---|
| How it works | About | Community guidelines | Privacy policy | iOS (App Store) |
| Pricing | Careers | Safety center | Terms | Android (Play) |
| Cities | Press | Help / FAQ | Cookies | Open web app |
| For partners | Contact | Status | Acceptable use | |

Bottom strip: copyright · "Made in Toronto" · social icons (only ones
actually run) · 18+ badge.

## 6. PWA (web.pynapli.com) — tiered scope

Inspired by the leaders' opposite playbooks:
- **Tinder Web:** free for everyone, takes web payments — funnel + margin.
- **Grindr Web:** paid-subscriber-only, with web-exclusive discretion
  features (Escape Hatch, Office Mode) — monetization wedge + privacy
  premium.

Pynapli adopts a **hybrid**: free messaging-parity, paid discovery-parity,
plus Grindr-style discretion features web-exclusively.

### 6.1 Free-tier web surfaces

Available to any logged-in user.

- **Auth + onboarding** (`/login`, `/signup`, `/verify`) — mirror
  `ONBOARDING_AUTH_SPEC.md` 10-step flow with same skip behaviors.
  Age gate is hard, not soft.
- **Profile** (`/profile`, `/profile/edit`) — view and edit your own
  profile, photos, prompts, tags, partner link.
- **Activity** (`/activity`) — gated reveal matches subscription tier
  (Free blurred, Social partial, Select full).
- **Messages** (`/messages`, `/messages/[threadId]`) — newest-first with
  `nextCursor` pagination, lightweight polling on the open conversation,
  draft validation client-side, separate moderation `status` from delivery
  `deliveryStatus`. WebSocket `/v1/realtime` upgrade as a phase 2
  enhancement.
- **Settings** (`/settings/*`) — billing/entitlement (read-only at launch,
  deep-link to App Store / Play; Stripe added in phase 2), active sessions,
  privacy, notifications, blocked users, account deletion, data export.

### 6.2 Paid-tier web surfaces (Social or Select required)

Free users see a soft paywall card on these routes that explains web
discovery is part of any paid tier and links to upgrade.

- **Grid** (`/grid`) — square-tile dense grid driven by `POST /v1/grid/query`.
  Recipient controls and filters surface on a sheet. Profile open opens a
  sheet/modal that returns to the same scroll position
  (`MOBILE_APP_ARCHITECTURE_SPEC.md` rule applies on web too).
- **Swipe** (`/swipe`) — keyboard-friendly (←/→/↑ for Pass/Like/Pineapple)
  is a real win on web. Pointer events otherwise.
- **Playing Tonight** (`/playing-tonight`) — 2-hour activation, individual
  or together-with-link.
- **Psync** (`/psync`) — bounded shared deck, both partners' devices,
  server-authoritative match resolution.
- **Albums** (`/albums`) — viewer for grants you've been given, manager for
  grants you've issued. Expiring photos enforced server-side.

### 6.3 Web-exclusive discretion features (phase 4)

Inspired directly by Grindr Web, with audience fit that may be even
stronger for Pynapli given how many users are partnered/married:

- **Escape Hatch.** Visible button + keyboard shortcut. Click → instant
  navigation away to a neutral site (Google), clears Pynapli session
  history from the tab, evicts service worker cache for `/grid`, `/swipe`,
  `/messages`, `/albums`. Cookie remains so re-login is one click.
- **Office Mode.** Toggle that switches the favicon, page title, and
  visual chrome to an unbranded "Document" / "Inbox" appearance. Photos
  blurred behind tap-to-reveal. Notification sounds muted. UI shifts to
  light theme to look mundane on a screen-shared call.

### 6.4 Things that stay native-only at launch

- Camera capture for media (web uses file picker).
- Push notification *registration* — web push is a separate provider
  (VAPID) from FCM, phase 2.
- Biometric session lock.

### 6.5 Navigation

Bottom-tab nav on small screens (Grid · Swipe · Activity · Messages · Me),
left-rail nav on ≥`lg`. Reuse design tokens from `DESIGN_TOKENS.md` so it
feels like the same product.

## 7. Auth — BFF cookie pattern

Web has no Keychain/Keystore equivalent. Use HttpOnly cookies via a
Next.js BFF so opaque tokens never touch JavaScript.

```
Browser (web.pynapli.com)
  POST /api/auth/login  { email, password }
  ↓
Next.js route handler
  → fetch api.pynapli.com/v1/auth/email/login
  ← { accessToken, refreshToken, expiresIn }
  Sets two HttpOnly, Secure, SameSite=Lax cookies on web.pynapli.com:
    pyn_at  (access,  ~15 min)
    pyn_rt  (refresh, ~30 days, scoped Path=/api/auth)
  Returns minimal { user } JSON to browser
  ↓
Subsequent calls: browser → /api/[...proxy] → Next reads pyn_at cookie,
  attaches Authorization: Bearer to api.pynapli.com call, streams response
  back. On 401 with WWW-Authenticate=expired, Next silently refreshes via
  pyn_rt, rotates both cookies, retries once.
```

Required pieces:

- **CSRF.** Double-submit cookie (`pyn_csrf`, JS-readable) checked by the
  BFF on every non-GET. Or Origin-header check (simpler, sufficient because
  cookies are SameSite=Lax).
- **Logout.** Next clears cookies and forwards to `POST /v1/auth/logout`
  so the server invalidates the refresh token (existing remote-logout-per-
  device support per spec).
- **Server-side token storage** stays exactly as-is. The web client looks
  identical to a mobile client from Ktor's perspective except for the
  `X-Platform: web` header.
- **Bot prevention.** Cloudflare Turnstile in front of signup,
  message-send-from-grid, and media upload. Free, GDPR-clean, fills the
  App Check gap mobile clients don't have on web.

## 8. Backend changes needed (`:server`)

Small, scoped:

1. **CORS allowlist.** Add `https://pynapli.com`, `https://web.pynapli.com`
   (and localhost variants for dev) to the HTTP plugin in
   `server/src/main/kotlin/com/pynapli/server/plugins/HTTP.kt`. Don't enable
   `Access-Control-Allow-Credentials` since the BFF means the browser never
   sends cookies to Ktor directly.
2. **`X-Platform: web` recognition.** Extend whatever already gates
   `X-Platform: ios|android` so analytics, rate-limit buckets, and feature
   flags can split web from mobile.
3. **Session metadata.** Store user-agent and IP for the web session in the
   existing active-sessions list so users can revoke a "Chrome on Mac"
   session from any device.
4. **Rate-limit review.** Web sessions stay open in browser tabs; the
   existing presence heartbeat cadence may need a different value for web
   vs. mobile to avoid false-online states. Add a `:web` rate-limit bucket
   if mobile buckets are tight.
5. **OpenAPI publish.** Finish `OPENAPI_HANDOFF_PLAN.md` so
   `web/lib/api/types.ts` can be generated from the live schema. Single
   highest-leverage server task for web.
6. **Phase 2: Stripe entitlement source.** Add Stripe webhook handler that
   normalizes into the same entitlement table as App Store and Play.
   Server stays single source of truth for tier.

No schema migrations needed at launch. No new endpoints needed for v1.

## 9. SEO plan

- **Metadata API** in every marketing route — title, description,
  canonical, OpenGraph, Twitter card. Per-page OG image generated via
  Next's `ImageResponse`.
- **Static/SSG** for `/`, `/about`, `/pricing`, `/community`, `/privacy`,
  `/terms`, `/cities/*`, `/careers`, `/press`. ISR (24h) for any future
  blog/news.
- **Schema.org** — `Organization` on `/about`, `Product`
  (SoftwareApplication) on `/`, `JobPosting` on each `/careers/[slug]`.
- **Sitemap + robots.** Generated. `web.pynapli.com` has its own
  `robots.txt` with `Disallow: /` and `noindex` headers from middleware.
- **Internal linking.** City pages cross-link, footer links from every
  page, "How it works" anchors deep-linkable.
- **Speed budget.** LCP < 1.8s, CLS < 0.05, JS payload on `/` < 90KB
  gzipped. Achievable with App Router + `next/image` + zero-client-
  component hero.
- **Honest expectations.** Adult-adjacent positioning means Google may
  down-rank. The win is brand-name ("pynapli") and city queries
  ("toronto lifestyle social app"), not generic competitive terms.

## 10. PWA specifics

- **`manifest.ts`** — `display: standalone`, `start_url: /grid` (or
  `/messages` for free-tier users), theme color `#050505`, background
  `#050505`, icons (192, 512, maskable). Name: "Pynapli."
- **Service worker** via `@serwist/next` — runtime caching for static
  assets, network-first for `/api/*`, never cache responses with
  `Authorization`/Set-Cookie. Background sync for queued message sends
  (phase 2).
- **Install prompt** — custom UI on `/grid` second visit, dismissible,
  never on the marketing site.
- **Web Push** — phase 2. Different infra path than FCM-for-Android; uses
  VAPID keys against the user's browser push service. New table in
  Postgres for `web_push_subscriptions`. Don't ship at launch.
- **Offline** — read-only Activity/Messages cache. No offline send (per
  the no-retry-non-idempotent-mutations rule).

## 11. Design system bridging

`web/lib/designTokens.ts` mirrors `DESIGN_TOKENS.md` exactly: same
charcoal `#050505`, graphite surface, `#F5F5F5` text, `#FFC928` pineapple-
gold accent, `#18D77A` online green, 8dp grid → `tailwind.config.ts`
spacing scale, Inter as the only font. `components/ui/*` are shadcn-style,
restyled to the dark-first palette. **No purple gradients, no neon, no
nightclub clichés** — `FRONTEND_DESIGN_DIRECTION_SPEC.md` rules apply on
web identically.

Office Mode is the one explicit exception: it intentionally adopts a
mundane light-theme "Document" appearance for discretion.

## 12. Phasing

| Phase | Scope | Rough effort |
|---|---|---|
| **0 — Foundation** | `/web` scaffold, Tailwind + tokens, BFF auth, login/signup, age gate, footer + nav, deploy to Vercel, CORS update on `:server` | ~1 week |
| **1 — Marketing** | Home, About, Pricing, Community, Privacy, Terms, Careers, Press, Contact, sitemap/robots, OG images, age-gate modal | ~1 week |
| **2 — PWA free tier** | Profile, Messages (incl. polling), Activity, Settings, manifest + service worker, install prompt, Cloudflare Turnstile | ~2 weeks |
| **3 — PWA paid tier** | Grid, Swipe, Playing Tonight, Psync, Albums, soft-paywall cards on free-tier views, sessions panel | ~2 weeks |
| **4 — Discretion + depth** | Escape Hatch, Office Mode, city pages, OpenAPI-generated types, real Lighthouse pass, web push (optional), realtime WebSocket upgrade | ~1 week |
| **5 — Web payments** | Stripe checkout, webhook → entitlement normalization, web-tier upgrade flow, "save when you sign up at pynapli.com" copy | 3–6 months post-launch |

## 13. Open questions / risks

1. **Age verification vendor.** If using a third-party verifier (Yoti,
   Persona, Veriff) for enhanced markets, the web SDK is a separate
   integration from iOS/Android. Plan one vendor account across all three
   platforms.
2. **`/v1/auth/email/signup` enumeration.** Both Tinder and Grindr always
   succeed silently and send a "you already have an account" email if the
   address is taken. Confirm `:server` follows this pattern before web
   signup ships.
3. **Image hosting.** If user uploads go through `/v1/media/uploads`
   direct to S3/GCS, web works the same. If it's a Firebase-backed bucket,
   the web uploader needs a Turnstile gate as the App Check substitute.
4. **Stripe at launch?** Recommend deferring to phase 5. Web users see
   pricing, get a free account, are nudged to install iOS/Android to
   upgrade. Avoids paywall conflict and keeps entitlement single-source
   per user during early ops.
5. **Web push at launch?** Recommend deferring to phase 4. FCM works on
   Android only for the mobile case; web push is a separate provider
   integration and a separate table.
6. **Domain acquisition.** `pynapli.com` owned. Recommended additional
   purchases: `pynapli.app`, `pynapli.co`, `get-pynapli.com`. Skip
   `pynapli.xxx` and `pynapli.adult`.

## 14. Reference: how the leaders do it

Captured here so future contributors don't have to re-research.

| | Tinder Web (tinder.com) | Grindr Web (web.grindr.com) | Pynapli (planned) |
|---|---|---|---|
| Web access | Free for everyone | Paid subscribers only | Free signup + messaging; paid for discovery |
| Domain | `tinder.com/app` | `web.grindr.com` subdomain | `web.pynapli.com` subdomain |
| Web payments | Direct credit card (avoids 30% app store cut) | None — perk of existing app sub | Phase 5: Stripe |
| Login | Phone OTP primary | Email/password, Apple, Google | Email/password, Apple, Google |
| Web-exclusive features | None notable | Escape Hatch, Office Mode | Escape Hatch, Office Mode (phase 4) |
| Bot prevention | Arkose Labs | (similar) | Cloudflare Turnstile |
| Age verification | DOB + 18+ self-attest; third-party vendor in regulated markets | Same | Same |

The strategic intent for Pynapli is to combine Tinder's funnel (free web
signup widens the top of the funnel) with Grindr's monetization wedge
(paid web discovery makes a paid tier tangible) and Grindr's discretion
features (Escape Hatch + Office Mode are arguably *more* relevant for
Pynapli's partnered audience than for Grindr's).
