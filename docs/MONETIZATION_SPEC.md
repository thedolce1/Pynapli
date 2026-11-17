# Pynapli Monetization Spec

## Purpose

This document defines Pynapli's launch monetization model: subscriptions, microtransactions, feature gates, linked-partner benefits, and anti-abuse rules.

Pynapli should borrow the best monetization mechanics from Grindr, Tinder, Bumble, and Feeld while staying simpler, cheaper, and more trustworthy as a new app.

Core rule:

> Free users create the network. Paid users buy convenience, reach, privacy, and insight.

## Launch Tiers

Locked launch structure:

- Pynapli Free
- Pynapli Social
- Pynapli Select

Launch monthly pricing:

- Free: $0
- Social: $4.99/month
- Select: $9.99/month

These prices are intentionally lower than major dating apps to reduce friction and help a new network grow.

## Naming Rationale

### Pynapli Free

Simple, clear, and expected.

### Pynapli Social

Accessible, app-store-safe, and aligned with Pynapli's positioning as an adult social lifestyle app.

### Pynapli Select

Premium without sounding cheesy, explicit, or nightclub-like.

Avoid:

- Gold
- Platinum
- XTRA
- Unlimited
- Majestic
- VIP
- Juicy
- Ripe
- Naughty
- Any pineapple-pun-heavy tier name

## Monetization Principles

1. Safety is free.
2. Basic intent clarity is free.
3. Free users must be useful to the network.
4. Paid tiers should feel additive, not punitive.
5. Select should contain the strongest FOMO and privacy features.
6. Microtransactions should enhance reach or intent, not replace subscriptions.
7. Linked partner benefit is a core differentiator.
8. All entitlements are server-authoritative.
9. Ads should not damage sensitive/privacy-heavy experiences.
10. Pricing should be simple at launch.

## What Must Stay Free

Never paywall:

- Block
- Report
- Safety center
- Basic profile
- Age gate
- Basic grid access
- Basic swipe access
- Basic partner linking
- Basic private album request/grant
- Basic distance/privacy controls
- Basic singles/linked/everyone filter
- Basic solo/together/social intent settings
- Account deletion
- Data export where required
- Basic notification privacy

## Pynapli Free

Price:

- $0/month

Purpose:

- Build network liquidity.
- Let users experience the core product.
- Create upgrade motivation through limits, not frustration.

Included:

- Create profile
- Public photos
- Basic private album
- Basic partner linking
- Grid access with limited volume
- Grid messaging, subject to recipient controls and rate limits
- Basic Grid filters
- Swipe access with daily Like limit
- Match chat
- Flirts with daily limits
- Activity counts
- Latest visible Activity item in some categories
- Basic album requests/grants
- Limited Playing Tonight sessions
- Block/report
- Safety center
- Basic privacy settings

Free limits:

- Limited grid volume
- Limited daily swipes/likes
- Limited flirts
- Limited private album requests
- Limited Activity reveal
- No full Viewed You list
- No full Likes You list
- No advanced filters
- No incognito
- No travel mode
- No Pineapple Like notes except possibly promo inventory

## Pynapli Social

Price:

- $4.99/month

Positioning:

> More room to connect, with fewer limits.

Purpose:

- The affordable mainstream paid tier.
- Remove friction.
- Give enough extra control to feel worth it.

Included:

- Everything in Free
- Expanded grid volume
- More swipe likes, or unlimited likes if launch economics allow
- Rewind
- More Grid filter combinations
- More Swipe filter control
- Saved filters
- More Activity reveal
- Open more flirts/views from Activity
- More private album requests/grants
- Basic timed album access
- Limited read receipts, if desired
- Basic travel preview
- More/larger Playing Tonight sessions
- Linked partner included

Suggested monthly inventory:

- 1 Pineapple Like per week
- Discounted Boosts

Do not overload Social. It should be clearly better than Free but leave premium FOMO for Select.

## Pynapli Select

Price:

- $9.99/month

Positioning:

> More privacy, more insight, more visibility.

Purpose:

- The power-user tier.
- Combines Grindr Unlimited-style privacy/visibility with Tinder Gold/Platinum-style FOMO.

Included:

- Everything in Social
- Full Activity reveal
- See who viewed you
- See who liked you
- Repeat viewers
- Advanced filters
- Incognito
- Travel mode
- Priority Likes
- Weekly Pineapple Likes
- Monthly Boost
- Notes on Pineapple Likes (any Pineapple Like can carry one)
- Full read receipts
- Typing status
- Message unsend
- Expiring photos
- Advanced private album controls
- Longer Activity history
- Boost result analytics
- Priority Playing Tonight placement
- Linked partner included

Suggested monthly inventory:

- 5 Pineapple Likes per week
- 1 Boost per month

The Select "Intro Notes" perk was removed with Intro Notes (2026-11-17); Select already includes
weekly Pineapple Likes, each of which can carry a note, so no separate perk replaces it.

Inventory should expire if unused at renewal or refresh windows, depending feature. Expiring inventory creates urgency but must be clearly disclosed.

## Linked Partner Benefit

This is a major Pynapli differentiator.

Rule:

> One paid subscription can grant the same tier to one active linked partner.

Behavior:

- Social subscriber can grant Social benefits to one active linked partner.
- Select subscriber can grant Select benefits to one active linked partner.
- Benefit ends when the partner link ends.
- Benefit does not merge accounts.
- Benefit does not share messages, Activity, albums, or settings.
- Benefit is server-authoritative.

Anti-abuse:

- One active beneficiary at a time.
- Cooldown after changing linked beneficiary.
- Verification requirement for benefit sharing, if abuse appears.
- Fraud monitoring for frequent link/unlink cycles.
- Subscription benefit should not apply retroactively to old linked partners.

Product copy:

> Linked partner included while your link is active.

Avoid:

- Couple subscription
- Shared account
- Two-for-one account
- Partner gets your account

## Microtransactions

Microtransactions should be optional accelerators.

### Boost

Purpose:

- Increase visibility in Grid and/or Swipe.

Recommended:

- 30-minute duration.
- Available to all eligible users.
- Included monthly in Select.
- Discounted for Social.

Rules:

- Does not bypass blocks, safety, privacy, age gates, or hard filters.
- Increases impressions, not guaranteed matches.
- Results should be reported honestly.

### Super Boost

Purpose:

- Stronger peak-time visibility.

Recommended:

- Select-only or high-trust-user eligible.
- A la carte.
- Used sparingly.

Rules:

- Higher price than Boost.
- Must still respect relevance and safety.
- Should not flood incompatible users.

### Pineapple Like

Purpose:

- High-intent swipe signal.

Recommended:

- Included weekly in Social/Select.
- Sold in packs.
- Scarce by design.

Rules:

- Prioritizes sender in recipient's Activity/Swipe.
- Can be dismissed.
- Does not guarantee match.
- Should not be unlimited.

### Pineapple Like note (formerly Intro Note)

Shipped 2026-11-17: Intro Notes are no longer a product. A Pineapple Like optionally carries a note
(`SwipeActionRequestDto.noteText`, trimmed, 1-140 characters, `PineappleLikeNote` in `:shared`).

- One Pineapple Like unit is spent either way, inside the swipe record transaction under the pair
  lock, and refunded on rewind like any Pineapple Like.
- A noted Pineapple Like is a positive swipe everywhere (match both orders, like-back from Activity,
  Psync).
- The recipient's `Allow notes with Pineapple Likes` toggle (`discovery_settings.allow_intro_notes`,
  wire field `allowIntroNotes`) refuses only the note: `PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED` (409),
  nothing spent; the client offers to send the Pineapple Like without it. The like is never blocked
  by the toggle. Over-long notes: `PINEAPPLE_LIKE_NOTE_TOO_LONG` (400).
- The note shows on the like in the recipient's Activity (`ActivityEventDto.noteText`); it never
  becomes a chat until matched.
- Legacy: V92 turned stored Intro Notes into Pineapple Likes with their note and unspent Intro Note
  inventory into Pineapple Likes. The retired Play product `pynapli_intro_notes_3` is no longer sold;
  a purchase made under it is still verified and credited as 3 Pineapple Likes. **Founder: deactivate
  `pynapli_intro_notes_3` in Play Console.**

### Extra Picks

Top Picks is frozen (2026-11-17): don't sell Picks packs or list daily Picks as a Select perk.

### Event Spotlight

Purpose:

- Boost visibility around a specific event, club night, or travel weekend.

Recommended:

- Later feature, not MVP-critical.
- Useful for lifestyle events.

Rules:

- Must respect event privacy and blocks.
- Should not expose event attendance against user settings.

### Private Album Add-Ons

Be careful.

Possible:

- More timed access presets
- Expiring photo inventory
- Extra album slots

Avoid:

- Making private album access feel transactional.
- Selling access to other users' albums.
- Paywalling revocation.
- Paywalling basic album privacy.

## Ads

**No ads (2026-11-17).** No ad network exists, so the fake "Sponsored" slot, the "ad-supported" Free
tier and the paid "No ads" perk are being removed. If ads are ever reconsidered, check store policy
for an adult app first, and keep these rules: no ads in private albums, chat threads, partner invite,
report/block, verification or safety flows; no ads implying sexual services or exposing lifestyle
context; no sensitive targeting.

## Paywall Placement

Good paywall moments:

- Tapping blurred Activity item
- Trying to see full Viewed You list
- Trying to see full Likes You list
- Applying advanced filters
- Running out of free likes
- Using Rewind after limit
- Trying Incognito
- Starting Travel mode
- Viewing Boost results details

Bad paywall moments:

- Block/report
- Deleting account
- Revoking album access
- Responding to partner invite
- Hiding distance
- Basic intent filters
- Viewing safety center

## Feature Matrix

| Feature | Free | Social | Select |
|---|---:|---:|---:|
| Basic profile | Yes | Yes | Yes |
| Basic grid | Yes | Yes | Yes |
| Expanded grid | 60-profile nearby depth | 600-profile nearby depth | Largest uncapped nearby depth |
| Grid messaging | Yes | Yes | Yes |
| Basic filters | Yes | Yes | Yes |
| Advanced filters | No | Some | Yes |
| Swipe likes | Limited | More/Unlimited | Unlimited |
| Rewind | No | Yes | Yes |
| Activity counts | Yes | Yes | Yes |
| Full Viewed You | No | Limited | Yes |
| Full Likes You | No | Limited | Yes |
| Incognito | No | No/Maybe | Yes |
| Travel mode | No | Preview | Yes |
| Pineapple Likes | Limited/promo | Weekly | More weekly |
| Notes on Pineapple Likes | With any Pineapple Like | With any Pineapple Like | With any Pineapple Like |
| Monthly Boost | No | Discounted | Included |
| Private album basics | Yes | Yes | Yes |
| Timed album access | Basic | Yes | Advanced |
| Expiring photos | No | Limited | Yes |
| Linked partner included | No | Yes | Yes |
| Safety controls | Yes | Yes | Yes |

## Pricing Strategy

Launch pricing:

- Social: $4.99/month
- Select: $9.99/month

Possible annual plans:

- Social annual: $39.99/year
- Select annual: $79.99/year

Possible intro offer:

- First month Social: $2.99
- First month Select: $6.99

Do not launch with confusing dynamic pricing. Simple pricing builds trust.

## Billing Architecture

Server-authoritative entitlements:

- Client purchases through App Store / Google Play.
- Ktor validates receipt/subscription state.
- Ktor stores normalized entitlement.
- Client reads entitlement from Ktor.
- Firebase/Remote Config may show paywall variants but cannot grant premium.
- Local/debug builds may use a Ktor-only dev purchase endpoint for emulator QA. This must stay disabled outside local/dev environments and must not be treated as production billing.
- A sandbox Play Billing verifier (`PYNAPLI_PLAY_STORE_SANDBOX_ENABLED`, never selected when `environment == production`) additionally lets the real Play Store `submit -> verify -> grant` path be exercised end-to-end in dev/QA without Google Play Developer API credentials: it only honors explicitly `sandbox-`prefixed purchase tokens (a `sandbox-invalid…` token exercises the reject path), and any other token falls through to the disabled/pending behavior. Production verification always wins when configured.

Requirements:

- Restore purchases
- Handle renewals
- Handle cancellations
- Handle grace periods
- Handle refunds/chargebacks
- Handle linked-partner beneficiary changes
- Idempotency keys for purchase processing

## Metrics

Track:

- Free to Social conversion
- Free to Select conversion
- Social to Select upgrade
- Subscription churn
- Partner benefit usage
- Boost purchase conversion
- Pineapple Like usage
- Pineapple Like note rate (share of Pineapple Likes sent with a note) and note-to-match rate
- Activity paywall conversion
- Advanced filter paywall conversion
- Ad revenue per free user
- ARPU / ARPPU
- Report/block rates by tier

Avoid tracking sensitive lifestyle terms in analytics unless privacy-reviewed and aggregated.

## Launch Experiments

Safe experiments:

- Social includes unlimited likes vs high daily cap.
- Select includes 3 vs 5 Pineapple Like notes/month.
- Select includes 1 monthly Boost vs discounted Boost.
- Activity reveal count for Free users.
- Social Activity reveal depth.
- First-month promo pricing.
- Annual plan discount.

Do not experiment with:

- Safety access.
- Block/report access.
- Basic privacy controls.
- Age verification requirements.
- Deceptive fake activity.

## Implementation Checklist

- [x] Free, Social, Select tiers exist.
- [x] Launch pricing is set at $4.99 and $9.99 monthly.
- [x] Entitlements are server-authoritative.
- [x] Linked partner benefit is implemented server-side.
- [x] One active linked partner beneficiary rule exists.
- [x] Subscription cooldown/abuse detection exists.
- [ ] StoreKit validation exists.
- [x] Google Play validation is server-authoritative end-to-end (`submit -> verify -> grant`, plus RTDN renewal/cancel/refund reconciliation and a sync worker), and is provable in dev/QA via an env-gated sandbox verifier (`PYNAPLI_PLAY_STORE_SANDBOX_ENABLED`) without prod creds. Remaining: real Play Console service-account credentials/config + product setup (deployment/ops step).
- [x] The store sheet (no Store tab since 2026-11-17; opened from every paywall, scrolled to what it sells, and from the profile menu's Plans and store row) displays Free/Social/Select, current entitlement, linked-partner benefit, accelerator products, and server-backed inventory balances.
- [x] Local/dev Store purchase endpoint exists for emulator QA and is environment-gated.
- [x] Connected Android Store QA proves login -> Grid -> Profile -> Plans and store, structural Store screenshots, Restore, dev Boost purchase, balance refresh, and no app error text.
- [x] Restore purchases exists.
- [x] Activity gating is implemented.
- [x] Advanced filters are gated.
- [x] Rewind is gated to Social/Select for latest eligible Like/Pass.
- [x] Boost inventory exists.
- [x] Pineapple Like inventory exists.
- [x] Intro Notes merged into Pineapple Like as an optional note (V92; legacy Play SKU credited as Pineapple Likes).
- [ ] Fake ad slot and "ad-supported"/"No ads" claims removed (in progress).
- [x] Safety features remain free.
- [x] Paywall copy uses Social/Select naming.

## Do Not Implement

- Do not copy Tinder/Grindr tier names.
- Do not launch with high major-app pricing.
- Do not paywall safety.
- Do not cripple Free so badly the network cannot grow.
- Do not make Pineapple Likes unlimited.
- Do not let Boost bypass safety or filters.
- Do not sell access to other users' private albums.
- Do not make linked-partner benefit a shared account.
- Do not trust client-side premium flags.
- Do not use deceptive fake Activity to sell upgrades.
- Do not put ads in private albums, safety, partner invites, or reports.
