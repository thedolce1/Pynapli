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
- Recently joined
- Available tonight
- Nearby now
- Traveling here
- Attending event

Rules:

- `Online now` should be a hard filter.
- `Active today` should include users active in the last 24 hours.
- `Available tonight` requires explicit user state, not merely online status.
- `Recently joined` should not override safety/trust rules.
- `Traveling here` should be based on declared travel/passport state.

### Who

Purpose: separate singles, partnered users, and linked users without creating couple cards.

Filters:

- Singles
- Linked
- Partnered
- Unlinked partnered
- Everyone

Rules:

- `Linked` means the user has a visible linked-partner edge.
- `Partnered` means the user declares being partnered.
- `Unlinked partnered` means partnered but no visible linked partner.
- These filters apply to individual users, not couple cards.

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
- Experienced
- Kink-friendly
- Vanilla/social

Rules:

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
- Linked partner verified
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

Rules:

- `Verified only` is hard.
- `Verified preferred` is ranking weight.
- Verification should be a trust signal, not a guarantee of safety.

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
- [ ] Partial: Grid filters are combinable and hard for implemented fields (Online, Singles, Linked, Partnered, Verified / Face photo verified / Age verified, distance radius, age range). The grid client uses an immutable `GridFilters` object — multiple filters can be active simultaneously and combine into a single server query (`GridStateHolderTest.togglingMultipleQuickFiltersCombinesIntoSingleRequest`). Trust filters are backed by `profiles.verified_at`/`face_photo_verified_at`/`age_verified_at` (V35 migration) with `IS NOT NULL` predicates and partial indexes; covered by `GridAndSwipeRepositoryIntegrationTest` "verifiedOnly excludes unverified" and "facePhotoVerifiedOnly / ageVerifiedOnly each filter to their column". Intent (F3), Lifestyle (F4), Logistics (F5), and Tonight/availability (F6) filter groups remain unimplemented; the in-app filter sheet labels them "Coming soon".
- [ ] Swipe filters support flexible vs dealbreaker behavior.
- [x] Linked does not automatically imply together.
- [x] No couple cards are generated from filters.
- [ ] Partial: basic intent/discovery controls exist for free users via the always-visible chip row (Online, Nearby radius, Singles, Linked) and the Filters sheet (Distance, Age, Presence, Who). Solo / Together / Open to both are not yet schema-backed and ship in F3.
- [x] Safety filters are not paywalled.
- [ ] Partial: lifestyle terms are documented, but glossary UX is not implemented.
- [ ] High-sensitivity terms are age-gated.
- [ ] Partial: implemented filters are stored as structured fields; the full lifestyle taxonomy is not modeled yet.
- [x] Profile text is not used as the primary filter source.
- [x] Empty states are neutral and useful: when filters are active and the feed is empty, the grid shows "No one matches these filters right now. Try widening distance or clearing one filter." per L481.
- [x] Filter changes on Grid animate diffs, not full refresh: `GridStateHolder` swaps cards atomically; stable `LazyVerticalGrid` keys + `Modifier.animateItem()` animate the diff. `GridStateHolderTest.filterChangeKeepsCardsVisibleUntilNewFeedLands` and `GridScreenScrollPreservationTest.filterChangeWithSameFeedKeepsScrollPosition` enforce non-clearing and scroll preservation.
- [ ] Partial: Swipe filter changes request a new deck, but true deck sessions are not complete.

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
