# Pynapli Moderation And Age Verification Spec

Last updated: 2026-04-24

## Purpose

Pynapli must be 18+, safety-forward, and app-store-safe from day one.

Core rule:

> Public surfaces are moderated before visibility. Private surfaces are consent-protected, reportable, and scanned only where legally/safety required.

Legal requirements change. Before launch, current age-verification, app-store, and jurisdiction-specific rules require legal review.

## Age Verification Model

Baseline all markets:

- Date of birth at account creation.
- Explicit 18+ confirmation.
- Terms/privacy acceptance.
- App store age signals where available/required.

Enhanced markets:

- Third-party age assurance where legally required.
- Possible video selfie age estimation.
- Possible ID + selfie verification.
- Market flags controlled server-side.

Privacy:

- Use verification data only for age assurance.
- Store only pass/fail, method, timestamp, and provider reference where needed.
- Delete raw verification data as soon as legally/product-wise possible.
- Do not use age-verification data for ads or profiling.

## Content Zones

Public zone:

- Grid thumbnails.
- Swipe cards.
- Public profile photos.
- Bios.
- Public tags/prompts.
- Playing Tonight public session photo/note.

Rules:

- No explicit content.
- Pre-publish moderation.
- App-store-safe.

Private zone:

- Private albums.
- Chat media.
- Expiring photos.

Rules:

- Explicit access grants.
- Reportable.
- CSAM/safety scanning where required.
- No automatic linked-partner access.

System/safety zone:

- Verification data.
- Reports.
- Moderator notes.
- Abuse evidence.

Rules:

- Strict access controls.
- Audit logs.
- Retention policy.

## Moderation

Automated moderation:

- Public photo classifier before publish.
- Public text scanning for prohibited solicitation, spam, threats, underage claims, and public explicit content.
- CSAM hash matching/scanning where legally required.
- Abuse behavior signals into Trust/Risk model.

Human moderation:

- Priority queue by severity.
- Actions: approve, remove, warn, suspend, ban, require verification, escalate.
- Senior review for appeals.

## Report Categories

- Underage user.
- Harassment or threats.
- Explicit public content.
- Spam or fake profile.
- Impersonation.
- Solicitation/paid sexual services.
- Non-consensual content.
- Private info/doxxing.
- Other safety issue.

Reports should be available from:

- Profile.
- Grid/swipe profile view.
- Chat.
- Message/media.
- Private album.
- Playing Tonight.
- Psync context.

## Prohibited Content And Behavior

Always prohibited:

- Minors in any sexual context.
- Threats of violence.
- Doxxing.
- Impersonation.
- Non-consensual intimate content.
- Solicitation of paid sexual services.
- Spam/scams.
- Harassment.
- Ban evasion.

Public surfaces additionally prohibit:

- Nudity/explicit content.
- Explicit sexual solicitation language.
- Graphic sexual descriptions.
- Escort-style promotion.

## Appeals And Legal Process

Appeals:

- 30-day appeal window for most suspensions/bans.
- Senior moderator review.
- Target response SLA: 7 business days.
- No appeal for confirmed CSAM or severe legal/fraud triggers where prohibited.

Legal requests:

- Require valid legal process.
- Emergency process for imminent risk.
- Audit all disclosures.
- Publish transparency commitment before scale.

## App Store Positioning

Use:

- Social lifestyle app.
- Open-minded adults.
- Privacy-first discovery.
- Partner linking.
- Community guidelines.
- Active moderation.

Avoid:

- Explicit hookup marketplace language.
- Sexual-service wording.
- Explicit screenshot content.

## Implementation Checklist

- [ ] 18+ DOB and confirmation.
- [ ] Market-driven enhanced verification flags.
- [ ] Verification data minimization.
- [ ] Public content moderation before visibility.
- [ ] Private album/report flow.
- [ ] Report categories implemented.
- [ ] Moderator queue and actions.
- [ ] CSAM/severe escalation policy.
- [ ] Appeals flow.
- [ ] Public community guidelines URL.
- [ ] App-store-safe screenshots and copy.

## Do Not Implement

- Do not allow unmoderated public photos.
- Do not store raw verification data longer than needed.
- Do not use verification data for ads.
- Do not hide report/block.
- Do not position Pynapli as a sex-services marketplace.

