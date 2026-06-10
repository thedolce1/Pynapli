# Pynapli Moderation And Age Verification Spec

Last updated: 2026-05-13

## Purpose

Pynapli must be 18+, safety-forward, and app-store-safe from day one.

Core rule:

> Public surfaces are moderated before visibility. Private surfaces are consent-protected, reportable, and scanned only where legally/safety required.

Legal requirements change. Before launch, current age-verification, app-store, and jurisdiction-specific rules require legal review.

## Age Verification Model

Baseline all markets:

- Date of birth at account creation.
- Explicit 18+ confirmation at account creation and before age-verification review start.
- Terms/privacy acceptance at account creation.
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

Current MVP slice:

- Email signup requires DOB, explicit 18+ confirmation, Terms acceptance, and Privacy Policy acceptance before a session is created.
- Signup writes DOB/display age to the profile and writes acceptance timestamps/version markers to the account.
- Profile Hub exposes an Age verification entry.
- Ktor exposes requirements/status/start endpoints for authenticated users.
- Admin/provider paths can review or callback verification results.
- Results are stored in `age_verifications`; successful verification also sets `profiles.age_verified_at`.
- Raw ID, selfie, and biometric payloads are not stored by this slice.
- Still required before beta: provider selection, market-specific rules, legal review, social-auth parity, and production admin controls.

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

- [x] 18+ DOB and confirmation exist for email signup and profile-hub verification start.
- [x] Terms/privacy acceptance exists for email signup.
- [x] Client detects `AGE_VERIFICATION_REQUIRED` on gated surfaces (Grid lifestyle filters, saved grid/swipe filter presets, travel mode), reverts the blocked change, shows a "Verify your age" prompt, and routes into the Profile-Hub age-verification flow (A1).
- [x] Market-driven enhanced verification flags: server-side `AgeVerificationMarketPolicy` seam (baseline default flags nothing; ops/legal swaps a real policy via DI, mirroring `TravelRegionPolicy`). In a flagged market with a provider configured, `requirements` reports `enhancedRequired` and accepts only the provider method, and `start` rejects manual review with `ENHANCED_VERIFICATION_REQUIRED`; without a provider the flag fails open to manual review so misconfiguration cannot strand users. Which markets to flag (and the provider choice) remains an ops/legal decision.
- [x] Verification data minimization.
- [x] Public/profile media moderation is enforced before discovery visibility.
- [x] Private album grants and report flow exist; consent-gated private media stays hidden from non-grantees, removed media is filtered, and severe scanner decisions remove the asset before escalating.
- [x] Report categories implemented.
- [ ] Partial: reports/risk/message/appeal review queues and actions exist; full moderator console, roles, MFA, senior-review separation, and production admin separation are not complete.
- [ ] Partial: severe scanner decisions create system reports and risk events; CSAM/hash-matching provider integration, legal retention policy, and live provider tuning remain.
- [x] Appeals flow exists for user submission/status/withdraw plus admin queue decisions.
- [x] Public community guidelines URL: canonical `LegalLinks.COMMUNITY_GUIDELINES` (`pynapli.com/guidelines`) wired into the Settings Legal section (PR #109). Publishing the finalized document at that URL is founder/legal ops.
- [ ] App-store-safe screenshots and copy.

## Do Not Implement

- Do not allow unmoderated public photos.
- Do not store raw verification data longer than needed.
- Do not use verification data for ads.
- Do not hide report/block.
- Do not position Pynapli as a sex-services marketplace.
