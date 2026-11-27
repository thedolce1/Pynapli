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
- Every filter (founder decision 2, 2026-11-26: no filter is paid); no saved filter presets
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
- Saved filter presets (every filter itself is free since founder decision 2)
- Incognito
- Travel mode
- Priority Likes
- 5 Pineapple Likes every week
- 1 Boost every month
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

## Included Allowances (implemented 2026-11-19)

The "Suggested monthly inventory" above is implemented, per period, server-side (V117):

| Tier | Pineapple Likes | Boosts |
|---|---:|---:|
| Free | 0 | 0 |
| Social | 1 every week | 0 (Boosts are discounted, not included) |
| Select | 5 every week | 1 every month |

Rules:

- **Who:** whoever holds the tier right now under the normal entitlement resolution: own subscription,
  admin or promo entitlement (same allowance as the tier), or the linked-partner benefit. Each person
  gets their own allowance; inventory is never shared between partners.
- **Periods:** a week is a 7-day window and a month a 30-day window, laid end to end from the person's
  allowance anchor. The anchor is set once, the first time the person is granted anything, to the
  start of the paid entitlement in force at that moment (for a subscription, its current
  billing-period start; for a covered partner, the subscriber's), clamped to now. It is stored in
  `included_allowance_anchors` and never moves. So people do not all reset at the same moment, and
  cancelling, resubscribing, upgrading or relinking never opens a new window early.
- **Lazy, exactly once:** nothing runs on a schedule. The current period's units are granted when the
  balance is read (`GET /v1/subscriptions/inventory`) or about to be spent (Swipe Pineapple Like, Psync
  Pineapple Like, Boost activation), in the same transaction as the spend. One row per person, type
  and period (`premium_inventory`, `source = 'subscription'`, unique on
  `(user_id, inventory_type, allowance_period_start)`), so concurrent requests grant once.
- **Use it or lose it:** an included unit expires at its period end (`expires_at`); nothing carries
  over or stacks.
- **Included before purchased:** a spend takes the current period's included units first; purchased
  units are only touched once those are gone, and no rollover, downgrade or lapse ever removes a
  purchased unit. A rewound or refunded included unit goes back to its own period.
- **Tier changes in a period:** usable included units = max(0, what the current tier includes - what
  was used this period). An upgrade tops up to the higher tier's amount; a downgrade caps at the
  lower amount; a lapse or unlink makes the unused units unusable (kept as `allowance_withheld`, so
  they come back if the tier comes back within the period). No sequence of tier changes, links or
  unlinks makes more than the period's highest entitlement usable.
- **Display:** the Store's balances include included units; the soonest expiry shown is the period
  end. Plan cards say "1 Pineapple Like every week", "5 Pineapple Likes every week", "1 Boost every month",
  from the same constants the server grants with.

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

Current implementation (server rule, `JdbcSubscriptionEntitlementSql.kt` partner branch +
`SubscriptionRepository.findBestActiveEntitlement`):

- Any paid tier (Social or Select) carries it. Only the subscriber's own row in `subscriptions`
  (status `active` or `grace`, inside its current period, any platform) reaches the partner; promo
  and admin grants in `entitlements` never do.
- The link must be `active` with `unlinked_at` null, and the subscriber's account must be active and
  not deleted. One partner only: a user has at most one active link (V16/V17 unique indexes).
- The partner gets the subscriber's tier (the whole tier policy). If the partner already has a
  higher plan of their own, the higher one wins.
- It ends when the link ends or the subscription lapses, with no stored grant to clean up (the
  entitlement cache holds a result for 5 seconds). The cooldown is the partner-link one: after an
  unlink, neither person can link again for 7 days (`ProfileLinkService`, `PROFILE_LINK_COOLDOWN`).
  The `linked_partner_benefits` table is never written, and the other anti-abuse rules above
  (verification requirement, link/unlink fraud monitoring) have no benefit-specific code yet.
- Entitlement is per account, so being covered applies on every client (Android, iOS, web). Buying
  a plan is Android-only today: the server has a Google Play verifier and no App Store one
  (StoreKit 2 is still open), and web is read-only.
- `SubscriptionEntitlementDto.linkedPartnerIncluded` is true for every paid entitlement, promo and
  admin included, so clients must not read it as "your partner is covered"; use `source`.

Product copy (in app since 2026-11-18; `store/StorePartnerBenefitCopy.kt`):

- The Store sheet opens its plans section with the benefit, and every plans paywall lands on it.
  Free viewer: "One subscription covers you both", then "Link your partner and they get the same
  plan at no extra cost." plus "Solo? Every plan works just as well for one." when not linked, or
  "Choose Social or Select and [partner] gets the same plan at no extra cost while you're linked."
  when linked.
- Own paid plan: "Your plan covers you and [partner]" (or "Your plan can cover your partner too"
  with a link invitation). Covered by a partner: "You're covered by [partner]'s plan".
- The current-plan card says who the plan covers ("Covers [partner] too", "Covered by [partner]'s
  plan", "Covers your account only" for promo/admin). Paid tier cards list "Covers your linked
  partner too" first.
- The partner's display name is used only when it is on the client already (the profile-links
  summary) and is 20 characters or fewer; otherwise the copy is generic.
- The Travel mode and advanced-filter paywall banners carry one quiet secondary line about it.

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

Current implementation:

- One active Boost per person: activation is serialised by an advisory lock, and a partial unique
  index (V101, `status = 'active'`) backs it up. Activation first expires the person's lapsed Boosts
  in the same transaction, so a Boost that ran out never blocks the next one.
- Swipe: a boosted person who passes every filter, block, risk and privacy rule and is inside the
  viewer's radius step is pulled onto the first page of the deck even when the ordinary order would
  reach them pages later, and placed near the top: at most one boosted card in every four (positions
  0, 4, 8, ...), unless no one else is left to show. Already-swiped people and cards dealt earlier
  in the deck session are never re-dealt. See SWIPE_IMPLEMENTATION_SPEC "Boost / Super Boost".
- Grid: boosted people sort first within the queried radius (unchanged).

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
tier and the paid "No ads" perk were removed. If ads are ever reconsidered, check store policy
for an adult app first, and keep these rules: no ads in private albums, chat threads, partner invite,
report/block, verification or safety flows; no ads implying sexual services or exposing lifestyle
context; no sensitive targeting.

## Paywall Placement

Good paywall moments:

- Tapping blurred Activity item
- Trying to see full Viewed You list
- Trying to see full Likes You list
- Saving a filter preset (applying a filter never opens the paywall since founder decision 2)
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
| Expanded grid | 60-profile nearby depth, then locked tiles to 600 | 600-profile nearby depth, then locked tiles to 1,200 | Largest uncapped nearby depth |
| Grid messaging | Yes | Yes | Yes |
| Basic filters | Yes | Yes | Yes |
| All filters (incl. lifestyle, logistics, partner) | Yes | Yes | Yes |
| Saved filter presets | No | Yes | Yes |
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
- Saved-preset paywall conversion
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
- [x] Every filter is free (founder decision 2, 2026-11-26, G2-4); saved filter presets are gated. Lifestyle filters need age verification (safety, not payment).
- [x] Rewind is gated to Social/Select for latest eligible Like/Pass.
- [x] Boost inventory exists.
- [x] One active Boost per person, enforced by the database (V101) as well as the activation lock.
- [x] A Boost lifts the person onto the first Swipe deck page, capped at one boosted card in four.
- [x] Pineapple Like inventory exists.
- [x] Included allowances are granted: Social 1 Pineapple Like/week, Select 5 Pineapple Likes/week + 1 Boost/month (lazy, once per period, included before purchased, no stacking; V117).
- [x] Intro Notes merged into Pineapple Like as an optional note (V92; legacy Play SKU credited as Pineapple Likes).
- [x] Fake ad slot and ad claims removed.
- [x] Safety features remain free.
- [x] Paywall copy uses Social/Select naming.
- [x] The Store leads with the linked-partner benefit, worded for the viewer's link and plan source (2026-11-18).

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

## Store listing draft (not published)

**DRAFT awaiting founder and legal review. Not wired into any store listing, `web/` or metadata.**
Written 2026-11-18 to be literally true per the code at that date (sources in brackets). It avoids
"hookup", sexually explicit wording and any promise about meeting people (Apple guideline 1.1.4;
Google Play sexual-content listing policy). Re-check every line against the code before use.

- App Store subtitle (30 max, this is 24): **One plan covers you both**
  (Hold until iOS can sell a plan: StoreKit 2 is not built and the server has no App Store
  verifier, so an iOS listing that leads with a plan would point at something iOS can't sell yet.)
- Google Play short description (80 max, this is 79), on one line so it copies as counted:

  **For lifestyle couples and singles. Link your partner: one plan covers you both.**

Long description, first three bullets:

- **One subscription, two people.** Link your partner's account and your Social or Select plan
  covers them too, at no extra cost, for as long as you stay linked. Your accounts stay separate.
  [Linked Partner Benefit, "Current implementation" above.]
- **Verified couples.** A couple shows as verified only when both partners have photo-verified, and
  every member can filter to verified couples only. [`CoupleVerifiedFilterSql`; the filter is free:
  `DiscoveryFilters.requiresAdvancedEntitlement` gates only lifestyle and advanced logistics tags.]
- **Your location stays yours.** Other members never see your exact location or exact distance:
  distances are rounded, and you can hide yours. [`DistanceRounding.kt` coarse cells and rounding,
  applied with `showDistance` in the Grid, Swipe, Activity and profile-peek mappers and SQL;
  Tonight uses cell-to-cell distance and coarse zones. The server does store your location to run
  discovery, so do not claim otherwise.]
