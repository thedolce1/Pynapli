# Pynapli Product Spec

Last updated: 2026-04-24

## Product Thesis

Pynapli is a location-based social lifestyle app for open-minded adults, singles, partnered people, and linked partners. The app should combine the best parts of Grindr and Tinder without becoming a clone of either:

- Grindr-style immediacy through a fast nearby grid, open messaging, strong filters, private albums, and live activity.
- Tinder-style intentional discovery through swipe, mutual matching, likes, super-like mechanics, rewind, boosts, and match-gated chat.
- Pynapli's core differentiator: autonomous individual accounts with optional partner linking.

The guiding product line:

> Grid for now. Swipe for intent. Links for trust.

Autonomy positioning line:

> Pynapli works the way real LS clubs and events work — every adult has their own voice.

## Positioning

Pynapli should be positioned as a social lifestyle app for adults. It should not market itself as an explicit hookup marketplace, escorting app, or sex-services platform.

The app provides social discovery, messaging, privacy tools, events, partner transparency, private albums, and user-controlled discovery. How adults choose to use the app is up to them, within safety rules and platform policies.

Recommended public framing:

> Pynapli is a location-based social lifestyle app for open-minded adults, singles, couples, and partnered people. Members control whether they connect solo, together, or socially.

## Core Product Rules

1. Every account represents one autonomous person.
2. There are no shared couple accounts and no primary couple cards.
3. Partner linking creates a visible relationship edge, not a merged identity.
4. A linked partner never controls another user's account, chats, media, blocks, settings, or identity.
5. Grid discovery is open and immediate.
6. Swipe discovery is match-gated and intentional.
7. Filters do heavy work before profiles appear, so cards can stay minimal.
8. Private albums belong to the individual user and are never automatically shared.
9. Safety controls are core product functionality, not luxury upsells.
10. Basic intent clarity must not be fully paywalled.

## Primary App Surfaces

Recommended bottom navigation:

- Grid
- Swipe
- Activity
- Messages
- Store

Profile and settings should live behind the user's avatar in the compact header, not as a bottom-tab destination.

## App Header

Use a compact Grindr-style top header across the app.

Common structure:

- Left: user's profile avatar
- Center: context pill, such as Current location, Discovery, Activity, or Inbox
- Right: optional quick action, such as filters, safety, premium, boost, search, or compose

Avatar opens the user menu:

- Edit profile
- Partner link
- Private albums
- Subscription
- Privacy
- Verification
- App settings
- Safety center
- Discreet app icon
- Logout

## Grid

The grid is the Grindr-inspired side of Pynapli. It answers:

> Who is relevant near me right now?

The grid should feel fast, dense, minimal, and live. It is a social discovery surface, not a compatibility resume.

### Grid Messaging Rule

Users can message from the grid without matching, subject to recipient message settings.

Allowed grid actions:

- View profile
- Message
- Flirt
- Request private album
- Grant private album access
- Block
- Report
- Invite linked partner after a chat exists

Recipient grid-message controls:

- Anyone can message me
- Verified users only
- People matching my intent filters
- Nearby only
- No first messages, flirts only
- Incognito / no grid messages

### Grid Card Design

Grid cards should be intentionally minimal, following the Grindr pattern.

Show only:

- Main photo
- Display name
- Online indicator
- Distance
- Tiny linked-partner avatar/icon, if linked

Do not show on the grid card:

- Long bio
- Intent chips
- Lifestyle tags
- Preference lists
- Compatibility scores
- Full verification stacks
- Album labels unless extremely subtle

The grid card should answer only:

1. Who is this?
2. Are they active?
3. How close are they?
4. Are they linked?

Everything else belongs in filters, profile tap, or chat.

### Linked Partner Display On Grid

Every grid card represents exactly one user.

If Alex is 1 km away and linked partner Jamie is 9 km away:

- Alex's card shows Alex at 1 km.
- Jamie's card shows Jamie at 9 km.
- Alex's card may show a tiny Jamie avatar/link icon.
- Jamie's card may show a tiny Alex avatar/link icon.
- The app must not merge them into a couple card.

Tapping or long-pressing the linked-partner icon can open a small preview:

- Linked with Jamie
- Partner activity state, if visible
- Partner distance, if visible and privacy-safe
- View profile

If the partner hides distance, show "distance hidden" or omit distance.

## Swipe

Swipe is the Tinder-inspired side of Pynapli. It answers:

> Who should I intentionally consider?

Swipe should feel slower, curated, and lower-noise than grid.

### Swipe Messaging Rule

Users cannot freely message from swipe unless there is a mutual match.

Before match:

- Like
- Pass
- Super Like / Pineapple Like
- Rewind
- View linked-partner preview
- Report
- Block

After match:

- Chat
- Invite linked partner
- Share/request private album
- Move to group chat

Premium or a la carte "message before match" can exist, but it must be capped hard so it does not ruin swipe.

Possible names:

- First Note
- Intro Note
- Pineapple Note

### Swipe Card Design

Swipe cards can show slightly more than grid cards, but should still be restrained.

Show:

- Photo carousel
- Name
- Age
- Distance
- Linked-partner mini avatar, if linked
- One short bio line
- A very small number of expanded tags only after tap or expansion

Swipe action is on the individual user, not automatically on their linked partner. If needed, the UI can ask:

- Interested in Alex solo?
- Interested in Alex and linked partner?

Only show this if the user's own settings allow solo/together distinction.

## Activity

Activity is Pynapli's interest inbox and FOMO engine. It should be separate from Messages.

Activity should be ordered newest to oldest.

Activity captures lightweight signals that are not full conversations:

- Viewed you
- Flirted with you
- Liked you from swipe
- Matched with you
- Requested your private album
- Granted you album access
- Partner chat invites
- Repeat views / multiple views

### Activity Sections

Use tabs or segmented controls:

- All
- Views
- Flirts
- Likes
- Matches
- Albums

### Activity Row Design

Rows should be minimal:

- Avatar
- Name, or blurred identity if gated
- Action
- Timestamp
- Distance, if allowed
- Tiny linked-partner icon, if linked
- Contextual CTA, if allowed

Example rows:

- Alex viewed you - 4 min ago
- Jamie flirted - 12 min ago
- Morgan liked you in Swipe - 1 hr ago
- Taylor requested album access - Yesterday

### Activity Gating

Free:

- See counts
- See latest item
- Blurred list for older activity
- Open matches
- Respond to album requests only where otherwise allowed

Plus:

- See a limited recent activity list
- Open flirts
- Partial reveal of likes
- Basic viewed-you list

Premium:

- Full activity history, such as 7 or 30 days
- Open and message from Activity
- See repeat viewers
- Filter Activity
- Incognito/private viewing
- Full "who liked you" from Swipe
- See and manage album requests

### Activity Privacy

Users must be able to opt out:

- Do not show me in Viewed lists
- Do not show my distance in Activity
- Browse incognito
- Allow flirts from everyone / verified only / no one
- Allow album requests from everyone / chats only / matches only

If a user opts out of appearing in Viewed lists, they should also lose access to their own Viewed list and previous Viewed history should be deleted. This is a fair privacy tradeoff.

## Messages

Messages are full conversations. Activity should not become a duplicate inbox.

Message types:

- Grid-originated 1:1 chat
- Match chat
- Partner-invited chat
- Group chat
- Event chat

Messages should support:

- Text
- Photos
- Voice
- Video clips, if desired later
- Private album requests/grants
- Expiring photos
- Read receipts for paid tiers
- Typing status for paid tiers
- Unsend for paid tiers
- Block/report everywhere

## Partner Linking

Partner linking is Pynapli's primary differentiator.

Each person creates and owns their own account. A partner link is a mutual, revocable connection between two autonomous accounts.

### Linking Rules

- Both users must consent to link.
- Either user can unlink.
- Both users control whether the link appears publicly.
- Each user controls their own visibility, location, photos, albums, chats, blocks, and subscriptions.
- If users break up, no one fights over a shared account.
- Unlinking should be clean and non-punitive.

### Partner Visibility

A linked user can choose:

- Show linked partner on profile
- Hide linked partner
- Show partner only to matches
- Show partner only to verified users
- Show partner only when browsing together

### Partner Intent

Being linked must not automatically mean "couple mode."

Each user can declare:

- I date solo
- I date with my partner
- I date both solo and together
- I socialize only
- I am open to singles
- I am open to couples
- I am open to groups
- Ask me in chat

This prevents the common lifestyle-app failure where linked/partnered people are incorrectly categorized.

## Partner Chat Invites

Linked partners can be invited into a chat, but never silently added.

Flow:

1. User is in a chat.
2. User taps Invite Partner.
3. Linked partner receives an invite with context:
   - Who invited them
   - Who is in the chat
   - Whether it is 1:1, couple, or group
4. Partner can Accept, Deny, or Ignore.
5. Any participant can leave anytime.

Rules:

- No silent partner adds.
- Denying an invite should not create drama-heavy notifications.
- Leaving a chat should use neutral language, such as "Alex left the chat."
- Album access does not automatically transfer to the partner.
- If partners unlink, shared chats remain governed by each participant's own membership, blocks, and privacy settings.
- If one partner blocks someone, the app can suggest that the other partner review their own block decision, but it should not force it.

## Private Albums

Pynapli should include Grindr-style private albums with stronger user controls.

### Media Types

- Public photos: profile-visible and moderation-first
- Private albums: locked by default
- Expiring photos: premium or higher-tier feature

### Album Rules

- Private albums belong to individual users.
- A linked partner does not automatically get access.
- A user grants access per user or per chat.
- Access can be revoked anytime.
- User can see who currently has access.
- Access can expire after a chosen duration, such as 24 hours, 7 days, or until revoked.
- One-time or expiring photos can be premium.
- Public photos must stay app-store-safe.

### Album Actions

- Request access
- Grant access
- Deny request
- Revoke access
- Grant timed access
- Share expiring photo
- View access history

## Flirts

Pynapli should have a lightweight Grindr Tap-style feature.

Possible flirt types:

- Wave: friendly/social
- Spark: interested
- Pineapple: lifestyle interest

The purpose is to let someone signal interest without writing a full message.

Flirts should appear in Activity, not Messages.

## Filters

Bad filters kill lifestyle apps. Pynapli needs separate filter systems for Grid and Swipe because the user intent differs.

Grid filters are tactical and mostly hard filters.

Swipe filters are strategic and mostly soft filters, with dealbreaker toggles.

## Grid Filters

Grid filters should reduce noise immediately. They are about availability, logistics, intent, and trust.

### Presence

- Online now
- Active today
- Available tonight
- Recently joined
- Nearby now
- Traveling here
- Attending event

### Profile Type

- Singles
- Linked partners
- Unlinked partnered people
- Individuals looking solo
- Individuals looking together
- Groups/social circles
- Verified only

### Linked Partner / Couple Composition

Because Pynapli uses individual accounts, this is about the linked user's visible relationship context, not a combined card.

- Linked with partner
- Partner visible
- Partner hidden
- Partner active
- MF linked pair
- MM linked pair
- FF linked pair
- Nonbinary-inclusive linked pair
- Mixed-orientation linked pair

Do not infer too aggressively from gender alone. Let users declare how they want to be discovered.

### Intent

- Social only
- Chat first
- Dating
- Events
- Couple-to-couple
- Solo with partner aware
- Together only
- Open to singles
- Open to couples
- Open to groups

### Lifestyle Preferences

Keep these optional, broad, and consent-oriented:

- Soft swap
- Full swap
- Same-room
- Separate-room
- Watch/social
- Kink-friendly
- Vanilla/social
- New to lifestyle
- Experienced
- Party/event focused

### Logistics

- Hosts
- Can travel
- Hotel-friendly
- Club/event only
- Meet in public first
- Distance radius
- City/neighborhood
- Travel dates

### Media And Trust

- Has public photos
- Has face photo
- Has private album
- Photo verified
- Age verified
- Linked partner verified
- Vouches/references, possible later

### Boundaries And Safety

- Verified-only messages
- Allows partner invite to chat
- Accepts group chats
- Public meet first
- No unsolicited private album requests
- Health practice tags, if handled carefully

### Grid Filter Rule

If a user selects:

- Linked partners
- Available tonight
- Couple-to-couple
- Verified only
- Within 25 miles

The grid should respect that. The grid is a live directory, not a recommendation feed.

## Swipe Filters

Swipe filters should shape the recommendation pool without becoming a spreadsheet.

### Who I Want To See

- Singles
- Linked partners
- People dating solo
- People dating together
- Everyone

### Intent

- Social
- Dating
- Couple-to-couple
- Solo
- Together
- Events

### Basic Preferences

- Age range
- Distance
- Gender/orientation preferences
- Verified only
- Has photos
- Has linked partner

### Dealbreakers

Borrow the dealbreaker pattern:

- Age: flexible / dealbreaker
- Distance: flexible / dealbreaker
- Verified: flexible / dealbreaker
- Looking together: flexible / dealbreaker
- Open to couples: flexible / dealbreaker
- Open to singles: flexible / dealbreaker
- Events/social only: flexible / dealbreaker

### Swipe Ranking Signals

Swipe should rank by:

- Mutual intent
- Distance
- Activity
- Profile completeness
- Linked partner clarity
- Response likelihood
- Shared interests
- Event overlap
- Prior likes
- Similar-user behavior

The app should avoid making users feel secretly throttled. Trust is damaged when users believe the app hides good matches to force payment.

## Profile Design

Profiles should be restrained. Pynapli should not become a questionnaire dump.

Suggested sections:

- Photos
- Linked partner preview
- Short bio
- Looking for
- Lifestyle tags
- Availability/logistics
- Private album request/grant
- Safety badges
- Message / flirt / like / invite partner actions

Profiles should encourage conversation, not replace it.

Good profile tags:

- Social first
- Events
- Soft swap
- Hosts sometimes
- Open to couples

Avoid giant tag clouds and over-specific preference walls.

## Monetization

Pynapli should monetize visibility, convenience, and premium insight without paywalling basic safety or core intent clarity.

### Free

- Profile
- Basic grid
- Limited swipe
- Basic filters
- Grid messaging, subject to anti-spam controls
- Basic partner linking
- Basic private album request/grant
- Block/report
- Safety controls

### Plus

- Expanded grid
- Unlimited or higher swipe limits
- More filter combinations
- Rewind
- No ads
- Limited Activity reveal
- Linked partner included

### Premium

- Full Activity reveal
- See who liked/viewed/flirted
- Incognito
- Advanced filters
- Travel mode
- Read receipts
- Typing status
- Expiring photos
- Premium album controls
- Priority visibility
- Linked partner included

### A La Carte

- Boost
- Super Boost
- Pineapple Like / Super Like
- First Note / Intro Note
- Event spotlight
- Extra private album grants, if used carefully
- Extra curated picks

### Linked Partner Subscription Benefit

The linked-partner subscription benefit is a major differentiator:

- One paid subscriber can grant the same tier to one active linked partner.
- Benefit ends when the link ends.
- Each user remains an autonomous account and MAU.
- Prevent abuse with cooldowns, verification, and one active benefit link at a time.

## Privacy And Safety

Pynapli operates in a sensitive category. Safety must be designed into the product from day one.

Core requirements:

- 18+ onboarding
- Age assurance / verification path
- Block everywhere
- Report everywhere
- Moderation for public photos and bios
- Clear rules against compensated sexual arrangements, escorting, harassment, spam, abuse, and non-consensual content
- Private album access controls
- Location fuzzing
- Hide distance
- Hide from Viewed lists
- Incognito
- Discreet app icon
- Account deletion
- Data export where required
- Safety center

Basic safety features should be free.

## Technical Direction

Preferred stack:

- Mobile: Kotlin Multiplatform + Compose Multiplatform
- API: Ktor
- Database: PostgreSQL + PostGIS for location discovery
- Cache / presence: Redis
- Realtime chat: Ktor WebSockets or a dedicated chat service
- Firebase: FCM push, Crashlytics, Analytics, Remote Config, maybe Auth
- Billing: StoreKit + Google Play Billing with server-side entitlement validation
- Storage: S3/GCS-style object storage for images
- Moderation: image/text moderation pipeline before public surfacing

Firebase should support the app, not become the full backend. Discovery, partner links, subscriptions, moderation, safety rules, and chat permissions need server authority.

## MVP Scope

The first serious version should include:

1. Individual accounts
2. Age gate and verification path
3. Profile creation
4. Public photos
5. Private albums
6. Partner linking/unlinking
7. Nearby grid
8. Grid filters
9. Grid messaging
10. Swipe deck
11. Match-gated swipe messaging
12. Activity screen
13. Flirts
14. Messages
15. Partner invite to chat
16. Subscription tiers
17. Linked-partner subscription benefit
18. Boost
19. Block/report
20. Moderation basics
21. Location privacy controls

## Do Not Forget

- No couple cards as primary discovery objects.
- Every discovery card is one autonomous user.
- Linked partner icon is a small context signal, not a merged account.
- Grid can message without match.
- Swipe cannot message without match, except tightly capped premium Intro Notes.
- Activity is newest-to-oldest and designed for FOMO.
- Grid cards stay extremely minimal.
- Filters carry the complexity, not cards.
- Linked does not mean dating together.
- Private albums are individual-owned.
- Partner chat invites require explicit acceptance.
- Safety is free; premium is convenience, reach, and insight.

## Source References From Product Research

- Grid implementation details: [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md)
- Swipe implementation details: [SWIPE_IMPLEMENTATION_SPEC.md](SWIPE_IMPLEMENTATION_SPEC.md)
- Activity screen details: [ACTIVITY_SPEC.md](ACTIVITY_SPEC.md)
- Admin and moderation console details: [ADMIN_MODERATION_CONSOLE_SPEC.md](ADMIN_MODERATION_CONSOLE_SPEC.md)
- Analytics and metrics details: [ANALYTICS_AND_METRICS_SPEC.md](ANALYTICS_AND_METRICS_SPEC.md)
- API contract details: [API_CONTRACT_SPEC.md](API_CONTRACT_SPEC.md)
- Backend architecture details: [BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md)
- Community guidelines: [COMMUNITY_GUIDELINES.md](COMMUNITY_GUIDELINES.md)
- Data model details: [DATA_MODEL_SPEC.md](DATA_MODEL_SPEC.md)
- Design and navigation details: [DESIGN_AND_NAVIGATION_SPEC.md](DESIGN_AND_NAVIGATION_SPEC.md)
- Design tokens: [DESIGN_TOKENS.md](DESIGN_TOKENS.md)
- DevOps and infrastructure setup: [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md)
- Frontend design direction: [FRONTEND_DESIGN_DIRECTION_SPEC.md](FRONTEND_DESIGN_DIRECTION_SPEC.md)
- Grid and swipe filtering details: [FILTERS_SPEC.md](FILTERS_SPEC.md)
- Launch strategy (archived): [LAUNCH_STRATEGY_SPEC.md](archive/LAUNCH_STRATEGY_SPEC.md)
- Language and taxonomy details: [LANGUAGE_AND_TAXONOMY_SPEC.md](LANGUAGE_AND_TAXONOMY_SPEC.md)
- Messaging details: [MESSAGING_SPEC.md](MESSAGING_SPEC.md)
- Mobile app architecture details: [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md)
- Moderation and age verification details: [MODERATION_AND_AGE_VERIFICATION_SPEC.md](MODERATION_AND_AGE_VERIFICATION_SPEC.md)
- Monetization details: [MONETIZATION_SPEC.md](MONETIZATION_SPEC.md)
- Notifications details: [NOTIFICATIONS_SPEC.md](NOTIFICATIONS_SPEC.md)
- Official tech reference guide: [OFFICIAL_TECH_REFERENCE_GUIDE.md](OFFICIAL_TECH_REFERENCE_GUIDE.md)
- Onboarding and auth details: [ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md)
- Playing Tonight details: [PLAYING_TONIGHT_SPEC.md](PLAYING_TONIGHT_SPEC.md)
- Privacy policy draft: [PRIVACY_POLICY_DRAFT.md](PRIVACY_POLICY_DRAFT.md)
- Profile and settings details: [PROFILE_SETTINGS_SPEC.md](PROFILE_SETTINGS_SPEC.md)
- Profile/filter sync details: [PROFILE_FILTER_SYNC_SPEC.md](PROFILE_FILTER_SYNC_SPEC.md)
- Production readiness and quality gates: [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md)
- Project structure and dependencies: [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md)
- Psync linked-partner swipe details: [PSYNC_SPEC.md](PSYNC_SPEC.md)
- Testing strategy details: [TESTING_STRATEGY_SPEC.md](TESTING_STRATEGY_SPEC.md)
- Terms draft: [TERMS_DRAFT.md](TERMS_DRAFT.md)
- Trust and safety details: [TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md)
- Grindr Grid: https://help.grindr.com/hc/en-us/articles/12155355365011-The-Grid
- Grindr Filters: https://help.grindr.com/hc/en-us/articles/12155443240851-Filters
- Grindr Viewed Me: https://help.grindr.com/hc/en-us/articles/8187024252179-Viewed-Me
- Grindr Taps: https://help.grindr.com/hc/en-us/articles/1500008659062-Taps
- Grindr XTRA: https://help.grindr.com/hc/en-us/articles/1500008656241-Grindr-XTRA
- Grindr Unlimited: https://help.grindr.com/hc/en-us/articles/1500008656741-Grindr-Unlimited
- Tinder Discovery Settings: https://www.help.tinder.com/hc/en-us/articles/115003340963-Discovery-Settings
- Tinder Subscription Tiers: https://tinder.com/feature/subscription-tiers/
- Tinder Super Like: https://www.help.tinder.com/hc/en-us/articles/115004493543-Super-Like
- Tinder Top Picks: https://www.help.tinder.com/hc/en-us/articles/360005039092-Top-Picks
- Tinder First Impressions: https://www.help.tinder.com/hc/en-us/articles/360046358932-Message-Before-Matching-with-Super-Like
- Feeld Partner Linking: https://support.feeld.co/hc/en-gb/articles/13766764814108-Partners-and-Couples-on-Feeld
- Apple App Review Guidelines: https://developer.apple.com/appstore/resources/approval/guidelines.html
- Google Play Developer Program Policy: https://support.google.com/googleplay/android-developer/answer/16070163
