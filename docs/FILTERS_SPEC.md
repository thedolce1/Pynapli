# Pynapli Filters Spec

Last updated: 2026-05-09

## Purpose

Filters are a core product feature for Pynapli. Bad filters kill lifestyle apps because they create mismatched intent, feed pollution, over-filtering, and wasted conversations.

This document is the canonical source for Grid and Swipe filtering.

Core rule:

> Grid filters are tactical and mostly hard. Swipe filters are strategic and mostly soft, with dealbreakers.

## Product Principles

1. Grid and Swipe must not use the exact same filter system.
2. Grid filters answer "who is relevant near me right now?"
3. Swipe filters answer "who should I intentionally consider?"
4. Linked does not mean looking together.
5. A user can be partnered/linked and still date solo.
6. A user can be single and still prefer couples or groups.
7. The app must support singles, partnered people, linked partners, couples, and social-only users without forcing everyone into couple logic.
8. Filters should reduce ambiguity, not create a questionnaire dump.
9. Basic intent filters must not be fully paywalled.
10. Safety filters should not be paywalled.

## Core Definitions

### Hard Filter

A hard filter must be respected. If a user selects it, excluded profiles should not appear.

Used mostly in Grid.

### Soft Filter

A soft filter influences ranking but can be relaxed when recommendations run low.

Used mostly in Swipe.

### Dealbreaker

A soft filter promoted to hard behavior by the user.

Used mostly in Swipe.

Example:

- Distance 25 km, flexible: show best matches slightly outside radius if deck runs low.
- Distance 25 km, dealbreaker: do not show anyone outside 25 km.

## Grid Filters

Grid filters are tactical, immediate, and mostly hard.

The grid is a live directory, not a recommendation deck. If the user applies a filter, the visible grid should strongly respect it.

### Grid Filter Groups

Recommended top-level filter groups:

- Presence
- Who
- Intent
- Lifestyle
- Logistics
- Media
- Trust
- Events
- More

### Presence

Purpose: show who is alive and relevant now.

Filters:

- Online now
- Active today
- Fresh (replaced "Recently joined", plan G4: account under 72 h or a new approved photo in 24 h,
  and active in 24 h; `freshOnly`, with `recentlyJoinedOnly` read as `freshOnly`)
- Available tonight
- Nearby now
- Traveling here
- Attending event

Rules:

- `Online now` should be a hard filter.
- `Active today` should include users active in the last 24 hours. It reads the same source as the
  card's activeAgo caption and Fresh (`GridActivitySql.activeToday`: `user_last_active`, or account
  creation for a brand-new account, within 24 h), so it keeps exactly the cards whose caption says
  "today" or sooner. It no longer reads `user_sessions`.
- `Visiting soon` (Grid): members whose announced planned trip reaches the viewer's area within the
  radius step and starts in the next 14 days. Every other Grid filter applies (`GridFilterSql`, the
  same fragment as the nearby rank), along with the Grid's visibility rules, blocks, hides and
  contact blocks. One row per member (their soonest qualifying trip). Ordered by arrival day, then
  the destination's L-08 order bucket, then id. Paged by keyset, and `visitingSoonOnly` is in the
  Grid cursor scope, so its cursors never continue Nearby. It never widens.
- `Available tonight` requires explicit user state, not merely online status.
- `Fresh` (was `Recently joined`) should not override safety/trust rules.
- `Traveling here` should be based on declared travel/passport state.

### Who

Purpose: separate singles, partnered users, and linked users without creating couple cards.

Filters (Grid sheet, as shipped 2026-11-26, G2-1):

- Singles
- Linked
- Everyone (neither selected)

Rules:

- Both filters come from the **visible partner link**, never from free text. `Linked` means the
  card shows this viewer a partner mini; `Singles` means it shows none. The gate is
  `LinkedPartnerFilterSql`, the couple-verified badge's partner rule
  (`CoupleVerifiedFilterSql.partnerShownToViewer`), plus contact blocks. It covers an active link,
  a partner who is active, visible, not incognito and not quarantined, no block either way, not
  hidden by the viewer, not hiding from the viewer, and the owner's `partner_visibility` (public;
  verified only when the viewer is verified; matches only when they matched; hidden never). So a
  partner the card hides is never revealed through either filter. A member who hides their partner
  from a viewer is a "single" to that viewer, exactly as their card reads.
- Singles and Linked are mutually exclusive states. Selecting both is their union, everyone; it
  does not reject the request.
- `Partnered` was dropped (merged into `Linked`). It matched the free-text
  `profiles.relationship_context`, which nothing writes a fixed vocabulary into ("partnered" was
  never written; "single" is just text), so it never matched reliably. The server folds the legacy
  wire tokens onto `linkedOnly` in `discoveryFiltersFrom`: `"single"` means not linked, and
  `"partnered"` / `"linked"` mean linked. Other values are ignored, and no query reads
  `relationship_context` any more. Old clients and saved presets keep working; a Grid preset carrying
  `partnered` restores as Linked. Swipe and Psync take the same tokens through the same parser.
- `Unlinked partnered` is not offered: without a structured relationship field it can't be told
  apart from Singles.
- These filters apply to individual users, not couple cards.
- Psync residual: a Psync deck is dealt once for both partners from the initiator's view. It uses the
  initiator's partner_visibility, hides and blocks, and no contact blocks (as for Psync candidates).
  Each partner's read then strips minis of people hiding from them. With Linked on, a card dealt to
  both may show one partner no mini. (The Partner filters and "No single men" are re-checked for both
  partners since G2-3; see "Partner". Linked and Singles are not.)
- Quick shortcuts in the Grid sheet: **Single women** / **Single men** set Singles plus that gender
  (the person's own declared gender, and no partner shown to the viewer). They add nothing on the
  server. A **No single men** chip sits beside them, the sheet twin of the quick-row chip.

### Partner

Purpose: let couples and singles say who they want the *other half* to be, without combined couple
cards and without the app guessing couple composition. Shipped 2026-11-26 (plan G2-3), Grid sheet
section under Who; on the wire for Grid, Swipe and Psync.

Filters:

- Partner's gender (multi-select, the same structured gender keys as Gender; self-describe is not an
  option and never matches)
- Partner's age range (18 to 99, like Age)
- Partner's orientation (multi-select, the same structured keys as Orientation)
- Partner photo verified
- Quick chip: **No single men**

Rules:

- Each filter tests the candidate's **visibly linked partner's own declared profile** (gender key,
  display age, orientation key, photo verification), never anything inferred from the candidate, and
  never a couple label. The app never says "MF", "FF" or "MM".
- One gate: every partner predicate runs inside `LinkedPartnerFilterSql.visiblePartnerExists`, the
  exact gate behind Linked and the tile's partner mini (owner's partner_visibility, the viewer's
  hides, contact blocks, blocks either way, the partner's status, visibility, incognito and
  moderation tier, an active link). A partner the viewer may not see contributes nothing, so the
  candidate behaves as "no visible partner" and a partner filter never matches through a hidden
  partner. Results always agree with what the tile shows. Fragment: `PartnerFilterSql`, used by
  `GridFilterSql` (nearby and Visiting soon), the Swipe deck rank (including the linked-only
  forced-id path) and Psync: the deck is ranked from the initiator's view, then the partner filters
  and "No single men" are re-run for both session partners, each with their own hides, blocks and
  contact blocks (`PartnerFilterSql.matchingCandidateIds`), and only cards passing for both are dealt.
- Partner filters imply Linked. With Singles alone they cannot match anyone, so the sheet's Partner
  section says "Partner filters need a visible partner" instead of offering chips, and the client
  drops them on apply, on save and in the request. With Linked, or no Who choice, they apply. The
  server does not reject Singles plus a partner filter; that just matches nobody.
- "Partner photo verified" needs only the partner verified. "Verified couples only" (Trust) needs
  both partners verified; the sheet says so under the chip.
- "No single men" drops people whose **own** declared gender is `man` and who have no linked partner
  shown to this viewer (same gate). Men visibly linked to a partner still show; people with no
  declared gender, and every other gender key, are kept. A man whose partner is hidden from the
  viewer reads as single on his tile, and is treated as single. Only the `man` key counts: `trans_man`
  is not included (founder may revisit; the app only uses what members declare). Quick row placement:
  last, after Verified (Filters · Online · Favorites · Fresh · Tonight · Verified · No single men),
  scrolling sideways.
- Per viewer: never served from the shared rank cache (like Linked); in both rank-cache keys, the
  Grid cursor scope, the Swipe deck-token scope, the persisted deck payload (all appended only when
  set, so older scopes keep their hash), both saved-filter services and the device filter store.
- Swipe: never relaxed, locked or not, like "Verified couples only". There is no dealbreaker key for
  them because they are always hard.
- Free (founder decision 2, 2026-11-26): not in `requiresAdvancedEntitlement`.
- Cost: off, each predicate short-circuits on its leading boolean. On, one EXISTS per surviving
  candidate: a probe of `idx_user_profile_links_active_user` (at most one active link) and primary-key
  lookups on the partner's users / profiles / discovery_settings / account_risk_states, plus the gate's
  own probes. No new index (V131 unused).

### Intent

Purpose: prevent the biggest lifestyle-app failure: mismatched expectations.

Filters:

- Social
- Chat first
- Dating
- Solo
- Together
- Open to both
- Events
- Open to singles
- Open to couples
- Open to linked partners
- Open to groups
- Ask in chat

Rules:

- `Solo` means the user is open to individual connection.
- `Together` means the user is open to connecting with their linked partner involved.
- `Open to both` means solo or together can be discussed.
- `Ask in chat` should not be treated as a match for users who set strict dealbreakers elsewhere.
- `Social` and `Events` should be available without implying sexual intent.

### Lifestyle

Purpose: let lifestyle users find aligned boundaries without forcing explicit detail everywhere.

Filters:

- Lifestyle
- ENM
- Swinging
- Soft swap
- Full swap
- Same-room
- Separate-room
- New to lifestyle
- Some experience
- Experienced
- Kink-friendly
- Vanilla/social
- Boundaries (hide people who said "not for me" to: photos or video, meeting the same day, meeting at someone's home, separate dates, group settings)

Rules:

- Lifestyle filters match "any of" inside a group (Swap, Room, Experience, other terms) and "all of" across groups.
- Experience is one value per profile (`profiles.experience_level`), never two tags.
- A boundaries filter hides only "not for me"; "ask first" and unset pass.
- Lifestyle terms should be optional.
- Do not require explicit lifestyle tags during onboarding.
- High-sensitivity filters should appear only after age-gated onboarding.
- Users must be able to hide or omit these tags from public profiles.

### Logistics

Purpose: reduce wasted conversations.

Filters:

- Hosts
- Travels
- Public meet first
- Club/event only
- Hotel-friendly
- Distance radius
- City/neighborhood
- Travel dates

Rules:

- `Hosts` and `Travels` should be self-declared.
- `Public meet first` should be treated as a trust/safety-friendly signal.
- `Distance radius` is a hard filter in Grid unless the user explicitly expands it.
- The radius is one of the server's fixed steps, 2, 5, 10, 25, 50 or 100 km (L-09,
  `DiscoveryRadiusSteps` in `:shared`, used by the server's `CoarseLocation`). The Grid slider stops
  only on those steps and its label ("Within 25 km") names the step the server searches. Any other
  value, from an old saved preset, stored filters or a crafted request, snaps up to the next step on
  both sides (26 km searches 50 km).

### Media

Purpose: help users choose how much visual context they need.

Filters:

- Has public photos
- Has face photo
- Has private album

Rules:

- `Has private album` does not imply access.
- Private album access must still be requested/granted.
- Public photo moderation must be separate from private album controls.

### Trust

Purpose: safety and confidence.

Filters:

- Verified
- Age verified
- Photo verified
- Linked partner visible
- Linked partner verified (shipped as "Partner photo verified" in the Partner section, G2-3)
- Verified couples only (both linked partners verified; see Swipe "Verified")
- Verified-only messages

Rules:

- Basic verified filtering should be available enough to support safety.
- Verification should improve ranking but not fully replace user judgment.
- `Linked partner visible` must not mean the partner is included in chat or discovery action.

### Events

Purpose: support lifestyle social discovery.

Filters:

- Attending this event
- Interested in events
- Club night
- Meet & greet
- Travel/takeover

Rules:

- Event attendance visibility should be user-controlled.
- Event filters should not reveal attendance to blocked users.

## Grid Filter Behavior

On Grid:

- Filters should be applied server-side.
- `Online now` is a hard filter applied after the SQL rank (presence lives in Redis, not the DB). To
  avoid under-filled online-only pages, the grid over-fetches a bounded larger candidate window when
  `onlineOnly` is set and rebuilds the keyset cursor from the last kept card, so an online-only page
  still fills to the requested size while pagination stays correct (`GridService`,
  `GridServiceTest.queryGridOverFetchesToFillOnlineOnlyPage`).
- Existing cards should remain visible while new filtered results load.
- The client should diff old vs new results and animate only necessary changes.
- Do not blank the grid on filter changes.
- Do not reset scroll unless location/context changed significantly.
- Active filter buttons should be visually obvious.
- Filter count should be visible.

Example:

If user selects:

- Online now
- Linked
- Together
- Verified
- Within 25 km

The grid should only show users matching those requirements.

## Swipe Filters

Swipe filters are strategic, lighter, and mostly soft.

Swipe is a recommendation deck. It should not feel like a database search.

### Swipe Filter Groups

Recommended top-level groups:

- Who
- Age
- Distance
- Intent
- Verified
- Dealbreakers
- Advanced

### Who

Options:

- Singles
- Linked people
- Partnered people
- Everyone

Rules:

- Default should be broad unless the user chooses otherwise.
- Linked people are still individual profiles.
- Do not show combined couple cards.

### Age

Options:

- Age range slider
- Dealbreaker toggle

Rules:

- If flexible, the deck may show slightly outside range when recommendations are low.
- If dealbreaker, do not show outside range.

### Distance

Options:

- Distance radius
- Dealbreaker toggle
- Current location
- Travel mode location

Rules:

- If flexible, deck may expand slightly.
- If dealbreaker, deck must respect the radius.
- Travel mode changes the recommendation context.

### Intent

Options:

- Social
- Dating
- Solo
- Together
- Open to both
- Events

Rules:

- Intent should strongly influence ranking.
- If marked as dealbreaker, it becomes a hard gate.
- Swipe should avoid showing someone whose intent conflicts with the user's declared discovery mode.

### Verified

Options:

- Verified only
- Verified preferred
- No preference
- Verified couples only (a separate toggle, combinable with the three above)

Rules:

- `Verified only` is hard.
- `Verified preferred` is ranking weight.
- Verification should be a trust signal, not a guarantee of safety.
- "Verified" means **selfie photo verification** approved by a reviewer (TRUST_AND_SAFETY_SPEC
  "Photo Verification"): profiles.verified_at and ace_photo_verified_at, both written on approval.
  "Face photo verified" is the same signal today; "Age verified" is the separate age check.
- The filter is **individual**: "Verified only" keeps a person who is verified, single or linked, so a
  linked person whose partner is unverified still matches. Couples show a separate **couple verified**
  badge only when both linked partners are verified and the partner is visible to the viewer
  (coupleVerified on Grid cards, Swipe cards and profile detail).
- **Verified couples only** (`verifiedCouplesOnly`, Grid and Swipe, and Psync decks through the Swipe
  sheet) keeps only people whose card shows that couple-verified badge for this viewer: they and their
  active linked partner are both photo-verified, and the partner is visible to the viewer (active,
  visible, not incognito, not quarantined or terminated, no block either way, the owner's partner
  visibility allows this viewer, and the viewer has not hidden the partner). One SQL predicate
  (`CoupleVerifiedFilterSql`) serves both rank queries. It implies a linked partner but does not set or
  change the separate `linkedOnly` filter. It is free, like "Verified only". It is a trust filter and
  **never relaxes** (founder decision 2026-11-18): a thin Swipe or Psync deck keeps it whether or not
  the `verified` lock is on, and the Grid's widen-on-empty step keeps it too. With nobody matching, the
  Swipe deck says "No verified couples nearby yet" and offers the usual ways out (widen filters, Travel
  mode). "Verified only" keeps its existing relaxation rule. It is viewer-specific, so a request
  with it on never uses the shared rank cache; it is also in both rank-cache keys, the Grid cursor scope
  and the Swipe deck-token scope.

### Dealbreakers

Dealbreaker-capable filters:

- Age
- Distance
- Verified
- Solo/together intent
- Open to singles
- Open to linked partners
- Open to couples
- Social/events only

Rules:

- Dealbreakers must be clear and reversible.
- The app should explain that dealbreakers may reduce recommendations.
- Do not silently override dealbreakers to fill the deck.

### Advanced

Advanced swipe filters may include:

- Has photos
- Has face photo
- Lifestyle experience
- Soft swap
- Full swap
- Same-room
- Separate-room
- Events/club focused
- Hosts/travels

Rules:

- Keep advanced filters collapsed by default.
- Advanced lifestyle filters should be optional and age-gated.
- Avoid making swipe feel like a lifestyle checklist.

## Swipe Filter Behavior

On Swipe:

- Filters should shape the deck before card generation.
- Soft filters influence ranking.
- Dealbreakers exclude candidates.
- The deck should be generated as a server-side session.
- The client should not continuously mutate the current deck for minor filter changes; it should request a new deck session.

If recommendations are low:

- Relax flexible filters.
- Show a clear empty/low-state.
- Suggest removing dealbreakers.
- Suggest widening distance.
- Suggest Grid for immediate discovery.

Do not:

- Violate dealbreakers.
- Fill the deck with irrelevant profiles.
- Reset user trust by showing obvious mismatches.

## Cross-Mode Differences

| Area | Grid | Swipe |
|---|---|---|
| Intent | Immediate discovery | Intentional matching |
| Messaging | Open, subject to settings | Match-gated |
| Filters | Dense | Light |
| Filter behavior | Mostly hard | Mostly soft |
| Dealbreakers | Less needed because filters are hard | Core pattern |
| Sort | Proximity + activity + trust | Compatibility + likelihood + intent |
| UI | Minimal cards | Richer cards |
| State | Live directory | Deck session |

## Filter Paywall Rules

Free users should get enough filters for the app to function respectfully.

Free:

- Distance
- Age
- Singles/linked/everyone
- Solo/together/social basics
- Verified basics
- Block/report/privacy controls

Social/Select:

- More filter combinations
- Advanced lifestyle filters
- Saved filters
- Travel filters
- Activity-based filters
- Popular/engagement filters
- Full expanded grid volume

Never paywall:

- Block/report
- Hide distance
- Basic safety filters
- Basic intent clarity
- Basic singles/linked filtering

## Saved Filters

Pynapli should eventually support saved filter presets.

Recommended presets:

- Nearby now
- Social first
- Looking together
- Dating solo
- Events tonight
- Verified only
- Traveling

Saved filters can be premium, but basic manually applied filters should remain usable.

Implementation status: server-side CRUD lives at `/v1/me/grid-filters` (GET/POST/DELETE) backed by `saved_grid_filters` (V39) and gated by `EntitlementPolicy.advancedFiltersAllowed` so free-tier users get an `ENTITLEMENT_REQUIRED` response. The client `SavedGridFilterStateHolder` loads on grid open, surfaces a "Saved" chip row at the top of the filter sheet, and exposes a name+save row. Free-tier users see a "Saved filter presets unlock with Social or Select." copy in the same slot. Manual filtering remains free. Cap is 12 presets/user, name length ≤ 60, names case-insensitive unique per user.

Swipe equivalent (shipped 2026-05-16): `/v1/me/swipe-filters` (GET/POST/DELETE) backed by `saved_swipe_filters` (V47). Same caps, same entitlement gate, payload is `SwipeDeckRequestDto` re-validated through the same `discoveryFiltersFrom` + `SwipeDealbreakerKey.ALL` checks the live `SwipeService.queryDeck` uses (so a stale preset whose dealbreaker keys or taxonomy tokens are later removed is rejected on save). Client `SavedSwipeFilterStateHolder` + `SwipeFiltersSheet` "Saved filters" chip row and save-current row mirror the Grid surface; free-tier copy reads "Saved swipe filter presets unlock with Social or Select."

## Empty States

Grid empty state:

> No one matches these filters right now.
> Try widening distance or clearing one filter.

Swipe empty state:

> No more recommendations match your dealbreakers.
> Try widening distance or switching to Grid.

Avoid:

- No one wants to play.
- Your filters are too picky.
- Things are quiet tonight.

## Backend Notes

Filter data should be structured, not parsed from profile text.

Recommended model concepts:

- `relationshipContext`
- `linkedPartnerVisible`
- `discoveryModes`
- `interestedIn`
- `lifestyleTags`
- `logisticsTags`
- `trustBadges`
- `availabilityState`
- `eventIds`

Do not rely on bios for filtering.

## Implementation Checklist

- [x] Grid and Swipe have separate filter models.
- [x] Grid filters are combinable and hard for implemented fields (Online, Singles, Linked, Verified / Face photo verified / Age verified, distance radius, age range, Intent, Lifestyle, Logistics, and Tonight). The grid client uses an immutable `GridFilters` object — multiple filters can be active simultaneously and combine into a single server query (`GridStateHolderTest.togglingMultipleQuickFiltersCombinesIntoSingleRequest`). Trust filters are backed by `profiles.verified_at`/`face_photo_verified_at`/`age_verified_at` (V35 migration) with `IS NOT NULL` predicates and partial indexes; covered by `GridAndSwipeRepositoryIntegrationTest` "verifiedOnly excludes unverified" and "facePhotoVerifiedOnly / ageVerifiedOnly each filter to their column".
- [x] Swipe filters support flexible vs dealbreaker behavior. `SwipeDeckRequestDto.dealbreakers: Set<String>` accepts canonical keys via `SwipeDealbreakerKey` (`age`, `distance`, `verified`, `discoveryStyles`, `intents`, `linkedOnly`); unknown keys throw `INVALID_SWIPE_FILTER`. `SwipeService.queryDeck` runs a relaxed second pass when the first-pass result is below `max(limit/3, 3)` AND any non-dealbreaker filter could relax. Non-dealbreaker fields are cleared on the retry and radius widens (2x, capped at `MAX_RADIUS_METERS`) when distance is not a dealbreaker; dealbreaker fields are preserved on both passes. Coverage in `SwipeServiceTest` "deckQueryRelaxesNonDealbreakerFiltersWhenResultIsLow", "deckQueryDoesNotRelaxWhenAllFiltersAreDealbreakers", "deckQueryDoesNotRelaxWhenResultMeetsThreshold", "deckQueryRelaxationPreservesDealbreakerFiltersOnRetry", "deckQueryRelaxationWidensRadiusWhenDistanceIsNotDealbreaker", "deckQueryRelaxesViaRadiusWhenDistanceIsNotDealbreakerEvenIfOtherFiltersAre".
- [x] Linked does not automatically imply together.
- [x] No couple cards are generated from filters.
- [x] Gender and orientation are discovery filters on both Grid and Swipe (founder decision 2026-06-14). The server filters `profiles.gender` / `profiles.orientation` with a hard match (`= ANY(selected)`), options sourced from the shared `ProfileTaxonomies` (self-describe is a free-text profile value and is not a filter option, so self-describe profiles never match a structured gender/orientation filter); on Grid the values feed the candidate-cache class. Surfaced as Gender/Orientation chip sections in both the Grid and Swipe filter sheets. Covered by `GridAndSwipeRepositoryIntegrationTest` "grid query with genders hard-filters to matching gender" + "...orientations..." + "swipe deck with genders hard-filters to matching gender", and client serialization round-trips (`GridStateHolderTest`, `SwipeFiltersTest`). This does not change the rule against inferring couple composition from gender alone.
- [x] Basic intent/discovery controls exist for free users via the always-visible chip row (Online, Fresh, Tonight, Verified, plus the Favorites view chip; G2-1 removed the dead Singles/Linked quick-filter ids) and the Filters sheet (Distance, Age, Presence, Who = Singles / Linked, Trust, Intent). The Intent group surfaces both `profiles.discovery_style` values (Solo / Together / Open to both / Social) and `profiles.intents TEXT[]` values (Dating / Chat first / Events / Open to singles / Open to couples / Open to linked partners / Open to groups, V40 migration). Server coverage in `GridAndSwipeRepositoryIntegrationTest` "discoveryStyles filters to matching style" + "intents filters to overlapping intent and excludes empty profiles"; client coverage in `GridStateHolderTest.applyingDiscoveryStylesSerializesToServerValues` + `applyingIntentsSerializesToCanonicalTokens`.
- [x] Safety filters are not paywalled.
- [x] Lifestyle terms are documented and filterable; the filter sheet exposes a "Glossary" affordance next to the Lifestyle section title that opens a modal listing the canonical definitions sourced from `LANGUAGE_AND_TAXONOMY_SPEC` (LIFESTYLE_GLOSSARY in `GridFiltersSheet.kt`).
- [x] High-sensitivity terms are age-gated. The Lifestyle filter group is hidden behind `me.profile.ageVerified` (derived server-side from `profiles.age_verified_at IS NOT NULL`); Ktor also rejects crafted live grid and saved-preset lifestyle filter payloads from unverified viewers with `AGE_VERIFICATION_REQUIRED`. Viewers without age verification see "Verify your age in profile settings to access lifestyle filters." Email signup captures DOB/18+/terms/privacy before access. Profile Hub has a verification entry that can start manual review and surface pending/verified/rejected state; production provider selection remains upstream work.
- [x] Implemented filters are stored as structured fields. Lifestyle (`profiles.lifestyle_tags TEXT[]`, V36), Logistics (`profiles.logistics_tags TEXT[]`, V37), and Intent (`profiles.intents TEXT[]`, V40) are indexed and validated. Tag groups use partial GIN indexes + `&&` overlap predicates against curated server allowlists. Coverage in `GridAndSwipeRepositoryIntegrationTest` "lifestyleTags / logisticsTags filters to overlapping tag", plus `GridStateHolderTest.applyingLifestyleTagsSerializesToCanonicalTokens` / `applyingLogisticsTagsSerializesToCanonicalTokens` / `togglingTonightSetsTonightAvailableOnlyOnRequest`. Saved presets are live via `/v1/me/grid-filters` (V39), capped at 12/user, and gated by `EntitlementPolicy.advancedFiltersAllowed`.

  The Tonight filter matches people with a live Playing Tonight session (PLAYING_TONIGHT_SPEC); there is no separate profile toggle.
- [x] Profile text is not used as the primary filter source.
- [x] Empty states are neutral and useful: when filters are active and the feed is empty, the grid shows "No one matches these filters right now. Try widening distance or clearing one filter." per L481.
- [x] Filter changes on Grid animate diffs, not full refresh: `GridStateHolder` swaps cards atomically; stable `LazyVerticalGrid` keys + `Modifier.animateItem()` animate the diff. `GridStateHolderTest.filterChangeKeepsCardsVisibleUntilNewFeedLands` and `GridScreenScrollPreservationTest.filterChangeWithSameFeedKeepsScrollPosition` enforce non-clearing and scroll preservation. The filter sheet body is scrollable so lower filter groups and Clear/Cancel/Apply remain reachable on Android; `GridScreenScrollPreservationTest.filtersSheetScrollsToActionButtons` protects that path.
- [x] Swipe filter changes request a new deck on a server-issued deck session. Deck sessions are signed (`SwipeDeckTokenCodec`), persisted (`swipe_deck_sessions` + `swipe_deck_cards`, V44/V45/V46 migrations), and support cursor pagination without duplicate cards. The Compose `SwipeStateHolder` auto-loads the next page when the visible stack drops below threshold and dedupes against visible/pending cards (`SwipeStateHolderTest`).
- [x] "Has photos" (L377) is implemented as an opt-in, free `withPhotosOnly` filter on Grid + Swipe: a hard `EXISTS` gate to at least one approved (image/ready/approved), visible profile-album photo. Threaded through `DiscoveryFilters` (in `hasAdvancedFilters`, not entitlement-gated — it is a basic quality/safety control, never paywalled), the grid rank SQL + all four swipe deck SQL functions, both rank-cache class keys, and client Grid/Swipe filter sheets ("Has photos" chip). Covered by `GridAndSwipeRepositoryIntegrationTest`, `GridRankCacheKeyTest`/`SwipeRankCacheKeyTest`, and client round-trip tests.
- [x] "Has private album" (Media, L195-201) is an opt-in, free `hasPrivateAlbumOnly` filter on Grid + Swipe: a hard `EXISTS` gate to owning at least one visible private album (`kind = 'private'`) with an approved (image/ready/approved) photo. Discovery signal only — it does NOT grant album access (albums are still request/granted, L199-201). Threaded through `DiscoveryFilters` (in `hasAdvancedFilters`, free), the grid rank SQL + all three swipe deck SQL files (incl. both forced-target functions), both rank-cache keys, both cursor/token scopes, both request DTOs + parsers + saved-filter services, and client Grid/Swipe filter sheets ("Has private album" chip). Covered by `GridAndSwipeRepositoryIntegrationTest` "grid and swipe query with hasPrivateAlbumOnly excludes profiles without a private album" + client `GridStateHolderTest.hasPrivateAlbumFilterSerializesAndRoundTrips` + `SwipeFiltersTest.hasPrivateAlbumSerializesAndRoundTrips`.
- [x] "Active today" (Presence) is a grid-only hard filter (`activeTodayOnly`): only surface profiles whose owner was active in the last 24h. Since G2-1 (2026-11-26) it is `GridActivitySql.activeToday` (`user_last_active` or account creation, the same source as the activeAgo caption and Fresh), no longer a `user_sessions` EXISTS. It is in `gridRankCacheKey` and the grid cursor scope; "Online now" stays Redis-live. Grid-only per the Presence group; Swipe ignores it (like Grid ignores `verifiedPreferred`). Also closed a latent gap: `withPhotosOnly`/`genders`/`orientations` were missing from the grid cursor scope hash, so a cursor could cross those filter values — all hard filters are now in the scope. Covered by `GridAndSwipeRepositoryIntegrationTest` `GridFilterDiscoveryTest` "grid query with activeTodayOnly matches exactly the cards whose caption says active today or sooner" + client `GridStateHolderTest.activeTodayFilterSerializesAndRoundTrips`.
- [x] Fresh (2026-11-24, plan G4) replaced "Recently joined": `freshOnly` (and `recentlyJoinedOnly` from older clients, read as `freshOnly`) = `GridActivitySql.fresh`, the same rule as the card's `fresh` flag (`GridFilterDiscoveryTest`). History of the replaced filter follows.
- [x] "Recently joined" + "Traveling here" (Presence, grid-only): `recentlyJoinedOnly` = the account (`users.created_at`) was created within the last 7 days (superseded by Fresh, above); `travelingHereOnly` = the candidate is near the viewer via an active `travel_locations` row (the card's `visiting` state, not their home GPS). Both are hard SQL gates in the grid rank query (also in `gridRankCacheKey` + the grid cursor scope); Swipe ignores them. Covered by `GridAndSwipeRepositoryIntegrationTest` "grid query with recentlyJoinedOnly excludes older accounts" + "grid query with travelingHereOnly returns only travel-mode visitors" + client `GridStateHolderTest.recentlyJoinedAndTravelingHereFiltersSerializeAndRoundTrip`.
- [x] Client 2026-11-24 (plan G4/G9): the Grid shows a **Fresh** chip (`freshOnly`, the server Fresh rule) in place of "Recently joined"; the client no longer sends `recentlyJoinedOnly`, and a saved preset carrying it restores as Fresh. Paid filters (Lifestyle; Logistics except "Public meet first") show a lock for Free viewers and clear only themselves on apply (`GridPaidFiltersAndPersistenceTest`).
- [x] **Filter-scope invariant (both surfaces):** a new hard filter must be added to BOTH the rank-cache class key (`gridRankCacheKey` / swipe `rankCacheKey`) AND the cursor/token scope (`DiscoveryCursorCodec.gridScopeHash` / `SwipeDeckTokenCodec.deckScopeHash`), or a cursor/deck token can carry across a filter change and misalign or replay a stale page. Both scopes now cover every set/order-defining filter (`genders`/`orientations`/`withPhotosOnly` on grid + swipe; `verifiedPreferred` on the swipe deck token; `activeTodayOnly` on grid). Guarded by `GridRankCacheKeyTest`/`SwipeRankCacheKeyTest` and `SwipeDeckTokenCodecTest.deckScopeHashDistinguishesEverySetOrOrderDefiningFilter`.
- [x] Swinger filters (Round 4): `LifestyleFilterSql` is the one predicate set for Grid rank, Swipe deck rank and the Playing Tonight feed (any-of within Swap / Room / Experience / other, all-of across; experience vs `profiles.experience_level`; `boundaryItems` hides `<item>:not_for_me`). `boundaryItems` is in both rank-cache keys, both cursor/deck-token scopes, `DiscoveryFiltersJson`, both saved-filter services and Psync. Swipe dealbreakers `swap` / `room` / `experience` / `boundaries` survive relaxation. Tonight feed takes `orientations` / `lifestyleTags` / `boundaryItems` in the body of `POST /v1/tonight/feed/query` (never the query string) with the Grid age-verification + advanced-filter gates; Swipe card tags include the experience level. Travel mode uses the same SQL. Covered by `SwingerFilterDiscoveryTest` (Grid + Swipe, home + Travel), `TonightFeedServiceIntegrationTest`, `SwipeDeckLifestyleRelaxationTest`, `GridRankCacheKeyTest` / `SwipeRankCacheKeyTest` / `DiscoveryCursorCodecTest` / `SwipeDeckTokenCodecTest`, `SwingerFiltersClientTest`.
- [x] "Verified couples only" (2026-11-18, Grid + Swipe + Psync decks): `verifiedCouplesOnly` on `GridQueryRequestDto` / `SwipeDeckRequestDto` (default false, so older clients and saved filters behave as before) means exactly the couple-verified badge for the viewer (`CoupleVerifiedFilterSql`, shared by `JdbcGridRankSql` and `JdbcSwipeDeckRankSql`, also on the linked-only forced-id Swipe path). Never served from the shared rank cache (like `linkedOnly`), in both rank-cache keys, the Grid cursor scope and the Swipe deck-token scope (appended only when on), `DiscoveryFiltersJson` (only when on), both saved-filter services and Psync validation and summaries. Free (in `hasAdvancedFilters`, not `requiresAdvancedEntitlement`). Never relaxes (founder decision 2026-11-18): `SwipeDeckRelaxation` neither counts it as relaxable nor drops it, locked or not, on the normal, linked-only and Psync paths, and the Grid's widen-on-empty keeps it; the Swipe empty deck then reads "No verified couples nearby yet" (`emptyDeckCopy`, `SwipeEmptyDeckCopyTest`); `verifiedOnly` relaxes as before (`VerifiedCouplesFilterIntegrationTest` thin-deck and widening cases). Client: a "Verified couples only" chip in the Grid Trust row and under the Swipe Verified choice; counts as active, clears with Clear all, round-trips through saved presets. Covered by `VerifiedCouplesFilterIntegrationTest` (filtered ids equal the ids whose badge shows, across pending links, hidden/verified-only/matches partner visibility, blocks, viewer hides, incognito, terminated and deleted partners, with `linkedOnly` and `verifiedOnly`, and shared-cache cross-viewer cases), `VerifiedCouplesFilterContractTest`, `GridRankCacheKeyTest` / `SwipeRankCacheKeyTest` / `DiscoveryCursorCodecTest` / `SwipeDeckTokenCodecTest`, `GridStateHolderTest` and `SwipeFiltersTest`. Playing Tonight's feed has its own three-field filter set and does not take it.
- [x] G2-1 (2026-11-26; written, not yet built or run): **Linked privacy fix.** `linkedOnly` on the Grid rank, the Swipe deck rank and `listLinkedCandidateUserIdsNear` now uses `LinkedPartnerFilterSql`, which is `partnerShownToViewer` plus the people hiding from the viewer (contact blocks, passed in by GridService and SwipeDeckQueryAction, and empty for Psync). Before, a member who hid their partner (partner_visibility, viewer hide, contact block) still matched "Linked". The proof is `LinkedFilterPartnerVisibilityIntegrationTest`: on Grid and Swipe, Linked == the ids whose tile shows a partner mini, and not-linked == the rest. It covers public, verified only (viewer verified and not), matches only (matched and not), hidden, viewer-hid-partner, contact-blocked partner, blocks both ways, incognito partner and pending link. Also in G2-1: Who from the visible link (`DiscoveryFiltersWhoTest`); distance steps (`GridRadiusStepsTest`); Visiting soon (`VisitingSoonDiscoveryTest`); one Active-today source (`GridFilterDiscoveryTest`). The Grid filter predicates now live in `GridFilterSql`, shared by the nearby rank and Visiting soon.

- [x] G2-3 (2026-11-26; written, not yet built or run): **Partner filters + "No single men"** (see
  "Partner"). `PartnerFilterSql` inside the Linked gate on Grid (nearby, Visiting soon), Swipe and
  Psync (strictest of both partners); off the shared rank cache; in cache keys, cursor and deck
  scopes, `DiscoveryFiltersJson`, both saved-filter services and the device store; never relaxed;
  free. Proof: `PartnerFiltersVisibilityIntegrationTest` (each filter x public, verified_only
  unverified/verified viewer, matches_only matched/not, hidden, viewer-hid-partner, contact-blocked
  partner, blocked either way, incognito partner, pending link: matches iff the tile shows the partner
  and the partner satisfies it; No single men; Psync), `PartnerFiltersContractTest`,
  `PartnerFiltersClientTest`. Open: build and tests, independent privacy review, emulator and browser
  walk, the Swipe sheet's Partner section (server and DTO ready; Swipe client not wired yet).

## Do Not Implement

- Do not use one universal filter drawer for Grid and Swipe.
- Do not treat linked users as couple accounts.
- Do not infer couple composition too aggressively from gender alone.
- Do not force users to declare explicit lifestyle preferences at signup.
- Do not show people outside swipe dealbreakers to fill inventory.
- Do not hide basic intent filters behind premium.
- Do not paywall safety-critical controls.
- Do not make grid cards carry filter complexity.
- Do not make swipe feel like a database query.
