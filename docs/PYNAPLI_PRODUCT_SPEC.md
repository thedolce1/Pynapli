# Pynapli Product Spec

## Product definition (founder, 2026-11-17)

Pynapli is for swingers, lifestyle couples and curious adults. It is:

1. A **Grindr-grade Grid**: fast, dense, live nearby discovery with open messaging.
2. A **Tinder/Bumble-grade Swipe**: intentional, match-gated discovery.
3. **Swinger-optimized filters** for each of Grid and Swipe.
4. **Travel mode** for both Grid and Swipe.
5. **Playing Tonight**: short-lived "available tonight" sessions, solo or with a linked partner.
6. **Psync**: linked partners swipe one shared deck together.
7. **2-for-1 couple linking**: two autonomous accounts linked as partners; one subscription covers
   both.

Plus the supporting systems those need (auth, onboarding, profile, media and private albums,
Activity, Messages, notifications, safety, moderation, age verification, subscriptions). Targets:
Android, iOS, Web. Desktop is a dev-only shell.

**This definition is the yardstick for new work.** A feature that serves none of the seven items or a
system they need is out of scope until the founder says otherwise; prefer pruning to adding.

> Grid for now. Swipe for intent. Links for trust.

## Positioning

A location-based social lifestyle app for open-minded adults, singles, couples and partnered people.
Members choose whether they connect solo, together or socially. Pynapli never markets itself as a
hookup marketplace, escorting app or sex-services platform.

## Core product rules

1. Every account is one autonomous adult. There are **no shared couple accounts and no couple cards**.
2. A partner link is a visible relationship edge, not a merged identity. A linked partner never
   controls the other's account, chats, media, blocks, settings or identity.
3. Grid discovery is open and immediate; Swipe discovery is match-gated and intentional.
4. Filters do the heavy work so cards stay minimal.
5. Private albums belong to the individual and are never shared automatically.
6. **Safety controls are free** and core product, never upsells. Basic intent clarity is never fully
   paywalled.
7. Linked does not mean dating together: each user declares their own intent.

## Navigation

Bottom tabs: Grid, Swipe, Activity, Messages. There is no Store tab: paywalls and a profile-menu
row open the store sheet (founder decision 2026-11-17; see [DESIGN_SPEC.md](DESIGN_SPEC.md)).
Profile and settings open from the avatar in the compact header and are never a bottom tab. The
avatar menu holds edit profile, partner link, albums, subscription, privacy, verification, app
settings, safety center, discreet app icon, and sign out.

## Grid

Answers "who is relevant near me right now?" A live directory, not a recommendation feed.

- **Cards show only:** main photo, display name, online indicator, distance, a tiny linked-partner
  icon. No bio, intent chips, lifestyle tags, preference lists, compatibility scores or verification
  stacks.
- Every card is exactly one user. Linked partners each have their own card, at their own distance,
  each showing a small icon for the other. Never merge them. If a partner hides distance, show
  "distance hidden" or nothing.
- **Users can message from the Grid without matching**, subject to the recipient's message settings
  (everyone, verified members only, matches only, or no one) and risk policy. Match chats are never
  affected by this setting.
- Grid actions: view profile, message, flirt, request or grant album access, block, report, invite a
  linked partner once a chat exists.
- Hard filters are respected exactly. Engineering: [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md).

## Swipe

Answers "who should I intentionally consider?" Slower, curated, lower noise.

- Before a match: like, pass, Pineapple Like (super like), Rewind, view linked-partner preview,
  report, block. **No messaging before a mutual match**, except an optional note on a Pineapple Like
  (Intro Notes were merged into it), capped hard.
- After a match: chat, invite linked partner, share or request album access.
- Cards: photo carousel, name, age, distance, linked-partner mini avatar, one short bio line, a few
  tags only on expand.
- A swipe acts on the individual, never automatically on their partner. There is no "solo or with
  their partner?" prompt after a Like (cut 2026-11-18): Psync and each person's declared discovery
  style already carry that intent.
- Ranking must not make users feel secretly throttled; hiding good matches to force payment destroys
  trust. Top Picks was removed (2026-11). Engineering: [SWIPE_IMPLEMENTATION_SPEC.md](SWIPE_IMPLEMENTATION_SPEC.md).

## Filters

Grid filters are tactical hard filters (presence, profile type, linked composition, intent,
lifestyle, logistics, media and trust, boundaries). Swipe filters are strategic soft filters with
per-filter dealbreaker toggles. Lifestyle options stay optional, broad and consent-oriented; never
infer composition from gender alone; let users declare how they want to be found. Bio is never a
filter source. Details: [FILTERS_SPEC.md](FILTERS_SPEC.md), [PROFILE_FILTER_SYNC_SPEC.md](PROFILE_FILTER_SYNC_SPEC.md).

## Travel mode

Discover (and be visible) in another city on both Grid and Swipe. Planned trips switch Travel mode on
and off by date, and "Visiting soon" shows announced trips (opt-in per trip). Region availability is
server policy (`PYNAPLI_TRAVEL_REGION_POLICY`).

## Playing Tonight

A short-lived session that says "available tonight", solo or together. Together mode needs both
partners' approval. Zone-level location only, no precise pins, no couple cards. Sessions
replaced the old "Available tonight" toggle. Details:
[PLAYING_TONIGHT_SPEC.md](PLAYING_TONIGHT_SPEC.md).

## Psync

Linked partners swipe one shared deck; matches happen together. No compatibility score; recaps are
aggregate; one partner's individual swipes stay private. Details: [PSYNC_SPEC.md](PSYNC_SPEC.md).

## Couple linking (2-for-1)

- Both users consent to link; either can unlink cleanly and without penalty.
- Each user controls whether the link is shown (profile, matches only, verified only, hidden) and
  keeps their own visibility, location, photos, albums, chats, blocks and subscription.
- Each user declares intent independently: solo, together, both, social only, open to singles /
  couples / groups, ask me in chat.
- **Subscription benefit:** one paid subscriber grants the same tier to one active linked partner.
  The benefit ends when the link ends; each user remains an autonomous account. Cooldowns,
  verification and one active benefit link prevent abuse.

### Partner chat invites

A linked partner can be invited into a chat but **never added silently**. The partner sees who
invited them and who is in the chat, and can accept or decline; the third party must consent too.
Anyone can leave at any time, with neutral copy ("Alex left the chat"). Album access never transfers
to the partner. If one partner blocks someone, the app may suggest the other review their own block,
never force it.

## Activity

The interest inbox, separate from Messages, newest first: viewed you, flirted, liked you in Swipe,
album requests and grants, partner/Psync/Tonight invites. New matches show in the Messages
new-matches rail (the Activity Matches tab was merged into it). Gating teases value without
being scammy. Privacy controls: hide me from Viewed lists (which also hides your own Viewed list and
deletes its history), hide my distance, browse incognito, who may flirt, who may request albums.
Details: [ACTIVITY_SPEC.md](ACTIVITY_SPEC.md).

## Messages

Full conversations: Grid-originated, match, partner-invited. Text and photos, album requests and
grants, disappearing messages and view-once media, paid read receipts and typing, block and report
everywhere. Details: [MESSAGING_SPEC.md](MESSAGING_SPEC.md).

## Private albums and flirts

- Public photos are moderation-first and app-store-safe. Private albums are locked by default and
  owned by the individual.
- Access is granted per user (or per chat), can be timed (24h, 7 days, until revoked), can be revoked
  at any time, and the owner can see who has access. A linked partner gets no automatic access.
- Flirts are a lightweight tap that lands in Activity, never in Messages.

## Monetization

Monetize visibility, convenience and insight; never safety or basic intent clarity. Tiers are Free,
Social and Select, plus add-ons (Boost, Pineapple Likes). Details: [MONETIZATION_SPEC.md](MONETIZATION_SPEC.md).

## Privacy and safety

18+ onboarding and an age-assurance path; block and report everywhere; moderation of public photos
and bios; clear rules against compensated arrangements, escorting, harassment, spam, abuse and
non-consensual content; album access controls; location fuzzing and hide distance; hide from Viewed
lists; incognito; discreet app icon (Android); account deletion and data export; a safety center. All
of these are free. Details: [TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md),
[MODERATION_AND_AGE_VERIFICATION_SPEC.md](MODERATION_AND_AGE_VERIFICATION_SPEC.md).

### Location privacy

- Every distance, list position, radius test and Playing Tonight zone another member can observe is
  computed between coarse cells (a 0.01-degree grid, about 1.1 km north-south), never between real
  positions ([GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md#location-privacy-load-bearing)).
- The service never stores a precise position, and current app versions never send one. They snap
  the device fix to its cell before sending it and send no accuracy figure. Older versions still
  send the precise fix; the server snaps it on write and discards it. Whatever a client sends, only
  the cell centre is stored (`user_locations.geom`, V119; `accuracy_meters` is always NULL).
- The device itself still reads the precise fix when the user grants precise location. Only the
  cell leaves the device.
- Precise points stored before V119 remain in database backups and write-ahead logs until those are
  rotated. This is an operational fact for the founder; code cannot remove them.
- Travel mode destinations are places picked from a place search, not the device's position. They are stored as given and read through the same cells.

What we can truthfully say (use this for any public wording):

| Statement | Status |
| --- | --- |
| "Other members never see your exact location or your exact distance" | True. |
| "Our servers never receive or store your exact location" | True for the current apps since V119. Points stored earlier remain in backups until those are rotated. |
| "We never know your exact location" | Do NOT claim. The app on the device reads the precise fix when the user grants precise location. |
| "Your position in lists cannot be used to find you" | Do NOT claim. A determined viewer who moves their own reported location can narrow someone down to roughly a kilometre, never finer. |

## Do not forget

- No couple cards; every discovery card is one autonomous user.
- The linked-partner icon is a small context signal, not a merged account.
- Grid can message without a match; Swipe cannot (except the capped Pineapple Like note).
- Grid cards stay minimal; filters carry the complexity.
- Linked does not mean dating together.
- Private albums are individual-owned; partner chat invites need explicit acceptance.
- Safety is free; paid tiers sell convenience, reach and insight.

## Spec index

[docs/README.md](README.md) lists every spec. Store policy references:
[Apple App Review Guidelines](https://developer.apple.com/appstore/resources/approval/guidelines.html),
[Google Play Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/16070163).
