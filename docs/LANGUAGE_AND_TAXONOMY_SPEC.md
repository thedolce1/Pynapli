# Pynapli Language And Taxonomy Spec

## Purpose

Pynapli needs a language system, not decorative slang.

The app should sound native to the lifestyle, clear to newcomers, restrained enough for mainstream app stores, and respectful enough to avoid feeling cheesy, explicit, spammy, or exploitative.

Core rule:

> Speak like a respectful insider, not a nightclub flyer.

## Product Language Goals

Pynapli language should be:

- Clear
- Mature
- Consent-forward
- Lifestyle-literate
- App-store-safe
- Inclusive of singles, linked partners, couples, and social users
- Useful for filtering and intent matching
- Easy for newcomers to learn without embarrassment

Pynapli language should not be:

- Porny
- Cheesy
- Wink-wink
- Overly explicit in public surfaces
- Pineapple-pun-heavy
- Escort-adjacent
- Judgmental
- Couple-centric by default

## Positioning Language

Use broad, safe language in public/app-store-facing surfaces.

Approved positioning phrases:

- Social lifestyle app
- Adult social lifestyle
- Open-minded adults
- Lifestyle community
- Location-based social discovery
- Partner linking
- Privacy-first discovery
- Social connections
- Events and meetups
- Singles and partnered people
- Connect solo, together, or socially

Avoid in public/app-store-facing surfaces:

- Hookup marketplace
- Sex app
- Find sex tonight
- Find swingers near you
- Swap partners tonight
- Naughty couples
- Hot singles and couples
- Adult fun near you
- Anything that implies escorting, compensation, or transactional arrangements

Recommended app-store-safe description style:

> Pynapli is a privacy-first social lifestyle app for open-minded adults. Discover nearby people, connect through the grid or swipe, link with a partner while keeping your own account, and choose whether you socialize solo, together, or at events.

## In-App Tone

Use direct, calm product language.

Good:

- Looking together
- Dating solo
- Social first
- Open to couples
- Open to singles
- Meet in public first
- Events and clubs
- Private album
- Request access
- Grant access
- Revoke access
- Invite partner
- Leave chat
- Hide distance

Bad:

- Ready to get wild?
- Spice things up
- Get naughty
- Find your unicorn tonight
- Juicy matches
- Ripe connections
- Hot couples nearby
- Naughty albums
- Secret fun
- No strings fun

Pineapple branding can exist, but it should be restrained. Do not turn every button, feature, and empty state into a pineapple pun.

## Core Product Terms

These terms are recommended for primary app surfaces.

### Identity / Account Context

- Single
- Partnered
- Linked
- Linked partner
- Partner hidden
- Partner visible
- Dating solo
- Looking together
- Open to both
- Social only

### Discovery Modes

- Grid
- Swipe
- Activity
- Messages
- Store
- Current location
- Travel mode
- Events
- Tonight

### Intent

- Social
- Chat first
- Dating
- Solo
- Together
- Events
- Open to singles
- Open to couples
- Open to linked partners
- Open to groups
- Ask in chat

### Trust / Safety

- Verified
- Age verified
- Photo verified
- Verified-only messages
- Public meet first
- Hide distance
- Incognito
- Block
- Report
- Safety center
- Discreet app icon

### Media

- Public photos
- Private album
- Request access
- Grant access
- Revoke access
- Timed access
- Expiring photo
- Album access

### Lightweight Interest Signals

Recommended flirt terms:

- Wave: friendly/social interest
- Spark: interested
- Pineapple: lifestyle interest

Do not overuse Pineapple. It should be one high-intent/lifestyle signal, not the name of every action.

## Lifestyle Terms

These terms are useful inside profile setup, filters, glossary cards, and optional self-labels.

Use them when they reduce ambiguity.

### Recommended Lifestyle Terms

- Lifestyle
- ENM
- Consensual non-monogamy
- Open relationship
- Swinging
- Soft swap
- Full swap
- Same-room
- Separate-room
- New to lifestyle
- Experienced
- Couples
- Singles
- Unicorn
- Stag
- Vixen
- Hotwife
- Bull
- Bi
- Bicurious
- Kink-friendly
- Vanilla/social
- Events
- Meet & greet
- Club
- Takeover
- Lifestyle resort
- Host
- Travel
- Vetting
- Boundaries
- Consent
- Hard limits
- Soft limits
- Discretion

### Use Carefully

These are real lifestyle terms, but can be loaded, misunderstood, or alienating if pushed too hard:

- Unicorn
- Bull
- Hotwife
- Stag/Vixen
- Cuckold
- Kink
- Fetish
- Play
- Swap

Rules:

- Let users self-select these terms.
- Do not assign them automatically.
- Do not make them primary onboarding categories unless necessary.
- Provide glossary definitions.
- Keep public surfaces broader and safer.

## Glossary System

Pynapli should include an in-app glossary.

Users should be able to tap or long-press lifestyle terms in:

- Profile setup
- Filters
- Profile tags
- Discovery settings
- Event details
- Search results

The glossary should use short, neutral definitions.

Do not write definitions with jokes, innuendo, or moralizing.

## Glossary Definitions

The in-app glossary copy lives in `grid/GridLifestyleGlossaryDialog.kt`. Definitions are short and
neutral: no jokes, innuendo or moralizing.

## Profile and Filter Taxonomy

The taxonomy is code: `shared/src/commonMain/kotlin/com/pynapli/shared/profile/ProfileTaxonomies.kt` (profile fields: relationship context,
discovery style, interested in, experience, lifestyle and logistics tags, intents) and
`shared/src/commonMain/kotlin/com/pynapli/shared/profile/DiscoveryTaxonomies.kt` (filter options). The server validates every key against the
same lists. Grid and Swipe filter behavior: [FILTERS_SPEC.md](FILTERS_SPEC.md).

## Chat And Activity Language

Use neutral and low-drama copy.

Good:

- Alex viewed you
- Jamie sent a Wave
- Morgan sent a Spark
- Taylor requested album access
- You matched with Sam
- Alex invited you to join a chat
- Jamie left the chat
- Album access revoked

Avoid:

- Alex is watching you
- Jamie wants you badly
- Taylor wants your private pics
- Things are heating up
- Your partner joined the fun
- Someone is thirsty

## Partner Link Language

Use autonomy-first language.

Good:

- Link partner
- Invite partner
- Linked with Jamie
- Partner visible
- Partner hidden
- Unlink partner
- Share subscription with linked partner
- Invite partner to chat
- Accept invite
- Deny invite
- Leave chat

Avoid:

- Couple account
- Add spouse
- Add your other half
- Merge profiles
- Shared profile
- Bring your partner into the fun
- Package deal

## Private Album Language

Keep private album language functional and controlled.

Good:

- Private album
- Request access
- Grant access
- Revoke access
- Timed access
- Access expires
- Expiring photo
- View access list

Avoid:

- Naughty album
- Secret photos
- Spicy pics
- Unlock their private side
- See more skin
- Adult gallery

## Empty States

Keep empty states useful and non-desperate.

Good:

- No one matches these filters right now.
- Try widening distance or turning off a dealbreaker.
- No new Activity yet.
- New views and flirts will appear here.
- No matches yet.
- Keep swiping or check the grid nearby.

Avoid:

- It's lonely here.
- No one wants to play yet.
- Your pineapple is waiting.
- Things are dry.

## Notification Language

Notifications must be subtle and privacy-conscious.

Good:

- You have new Activity.
- Someone viewed your profile.
- You have a new match.
- Alex sent a message.
- Jamie invited you to a chat.

Avoid:

- A hot couple wants you.
- Someone wants to swap.
- Your private album got attention.
- Lifestyle action nearby.
- Don't miss tonight's fun.

Because users may have privacy concerns, notification copy should avoid explicit lifestyle terms unless the user opts into detailed notifications.

## Safety And Consent Language

Safety copy should be direct and nonjudgmental.

Approved phrases:

- Consent can change at any time.
- Respect boundaries.
- Meet in public first if you prefer.
- Do not share photos or details without permission.
- Report behavior that violates community rules.
- Block removes mutual visibility where possible.
- Private album access can be revoked anytime.

Avoid:

- Play safe, naughty people.
- Be spicy but respectful.
- Don't be creepy.
- Drama-free only.

## App Store And Policy-Safe Language

Public metadata should emphasize:

- Adult social networking
- Privacy
- Partner linking
- Events
- Discovery
- Messaging
- Safety controls
- Verification

Avoid explicit sexual terminology in:

- App title
- Subtitle
- Screenshots
- App store description
- Push notification examples
- Public website hero copy

In-app settings can use more precise lifestyle terms after age-gated onboarding.

## Inclusive Language Rules

Pynapli is not only for heterosexual couples.

Use:

- Partner, not husband/wife by default
- Linked partner, not spouse
- People, members, users
- Singles and partnered people
- Linked people
- Couples only where the user explicitly selected that context

Avoid assuming:

- MF couples as default
- One man + one woman
- Couples always date together
- Linked means non-single in discovery mode
- Women are unicorns
- Men are bulls

## Taxonomy Governance

Each term has: internal key, display label, short definition, category, sensitivity level, and
whether it may appear in public copy, in push notifications, as a self-label only, or as a filter.
Changes to keys are reviewed, because profiles, saved filters and the server allow-lists depend on
them.

## Initial Sensitivity Levels

Low:

- Lifestyle
- Social
- Partnered
- Linked
- Open-minded
- Events
- Meet & greet
- Boundaries
- Consent
- Discretion

Medium:

- ENM
- Open relationship
- Swinging
- Soft swap
- Same-room
- Separate-room
- Kink-friendly
- Unicorn
- Stag
- Vixen

High:

- Full swap
- Hotwife
- Bull
- Cuckold
- Fetish

High-sensitivity terms should generally be in-app only, behind age-gated onboarding, and not used in public metadata or push notifications by default.

## Do Not Forget

- Language is a UX feature.
- Keep public copy broad and safe.
- Use lifestyle terms only where they improve clarity.
- Define terms in-app.
- Avoid cheesy pineapple puns.
- Never assume linked means together.
- Never assume couples are heterosexual.
- Never make private album copy sound explicit or voyeuristic.
- Never make Activity or notification copy feel invasive.
- Let users self-label loaded terms.
