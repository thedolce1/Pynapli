# Pynapli Admin And Moderation Console Spec

Last updated: 2026-04-24

## Purpose

This document defines Pynapli's internal admin and moderation console.

Core rule:

> Admin tools exist to protect users, enforce policy, resolve edge cases, and operate the business. They must be audited, role-limited, and privacy-conscious.

## Product Role

The admin console supports:

- Content moderation
- User reports
- Age verification review
- Account risk review
- Bot/spam quarantine
- Appeals
- Subscription support
- Partner-link abuse review
- Private album abuse review
- Playing Tonight review
- Psync support
- Push/notification investigation
- Audit logging
- Operational metrics

The admin console should not become a casual user-browsing tool. Every sensitive lookup and action must be justified and logged.

## Access Model

Admin users are separate from consumer users.

Roles:

- `Support`: user support, account lookup, limited subscription help
- `Moderator`: reports, content moderation, warnings, suspensions
- `Senior Moderator`: appeals, ban review, severe cases
- `Trust Safety`: risk scoring, bot/spam, account farms
- `Billing Admin`: purchases, entitlements, refunds support
- `Ops Admin`: feature flags, operational dashboards
- `Super Admin`: break-glass, role management

Rules:

- Use least privilege.
- Require MFA for all admin accounts.
- Require step-up auth for severe actions.
- Audit every user lookup and every mutation.
- No shared admin accounts.

## Console Sections

Recommended navigation:

- Dashboard
- Users
- Reports
- Moderation Queue
- Risk Review
- Appeals
- Verification
- Subscriptions
- Partner Links
- Albums / Media
- Playing Tonight
- Psync
- Notifications
- Admin Audit
- Settings

## Dashboard

Shows operational health:

- Open reports by priority
- Moderation queue size
- Median moderation SLA
- Accounts in risk tiers
- Held messages count
- Pending age verification reviews
- Appeals open
- CSAM/severe escalations
- Subscription support queue
- Active Playing Tonight sessions
- Psync session errors

Do not show sensitive content previews on the dashboard unless necessary.

## User Lookup

Search by:

- User ID
- Display name
- Email hash or verified support token
- Phone hash
- Subscription external ID
- Report ID
- Partner link ID

Show:

- Account status
- Profile summary
- Verification status
- Subscription/entitlement status
- Linked partner state
- Risk tier and reasons
- Recent reports
- Blocks/reports summary
- Active sessions/devices
- Recent moderation actions

Do not show by default:

- Private album media
- Message contents
- Exact location
- Raw verification data

Sensitive sections require explicit reveal reason and audit log.

## Content Moderation Queue

Moderation items:

- Public profile photos
- Public bios/prompts
- Public tags where needed
- Playing Tonight session photos/notes
- Reported private media
- Reported messages
- Flagged profile edits

Queue priority:

1. Underage / CSAM / imminent harm
2. Threats / violence / doxxing
3. Non-consensual content
4. Explicit public content
5. Spam/scam
6. Impersonation
7. Other policy issues

Actions:

- Approve
- Remove
- Warn
- Require edit
- Require verification
- Temporarily suspend
- Ban
- Escalate to senior moderator
- Escalate to legal/safety

## Reports

Report detail should show:

- Reporter
- Reported user
- Target type and target ID
- Category
- Optional note
- Relevant evidence snapshot
- Prior reports
- Block status
- Risk signals

Report categories:

- Underage user
- Harassment or threats
- Explicit public content
- Spam or fake profile
- Impersonation
- Solicitation/paid sexual services
- Non-consensual content
- Private info/doxxing
- Other safety issue

Rules:

- Reporter identity should be protected from reported user.
- Moderators should see enough evidence to act but not more than necessary.
- Actions must be logged.

## Risk Review

Risk review handles bots, spam, scraping, account farms, and abuse.

Show:

- Risk score tier, not necessarily exact score to all roles
- Risk reasons
- Message velocity
- Duplicate-message flags
- Profile view velocity
- Swipe velocity
- Album request velocity
- Flirt velocity
- Rate-limit hits
- Device/account-farm signals
- Linked-partner subscription cycling
- Location anomaly signals
- Held messages

Actions:

- Clear risk
- Lower risk
- Raise risk
- Require verification
- Release held messages
- Delete held messages
- Keep in quarantine
- Suspend
- Ban
- Add device/payment/network signal

Rules:

- Do not expose detection thresholds widely.
- Severe actions require reason code.
- High false-positive patterns should create product/engineering alerts.

## Message Review Queue

Used for suspicious outbound messages, especially first messages.

Show:

- Sender profile summary
- Recipient summary
- Message content
- Duplicate/similarity match
- Link presence
- Prior held/deleted messages
- Reports/blocks

Actions:

- Release
- Delete
- Warn sender
- Require verification
- Escalate to risk review
- Ban/suspend if confirmed spam/scam

Rules:

- Clean users' normal messages should not be delayed by this queue.
- Queue exists for suspicious/risky messages only.

## Appeals

Appeal detail:

- Original action
- Reason
- Evidence summary
- Moderator who acted
- User appeal text
- Account history
- Risk state
- Prior appeals

Actions:

- Uphold
- Reduce penalty
- Restore account
- Require verification
- Escalate senior/legal

Rules:

- Appeals reviewed by a senior moderator not involved in the original decision.
- Target response SLA: 7 business days.
- CSAM/confirmed severe fraud may be non-appealable according to policy/legal review.

## Age Verification

Show:

- Verification status
- Market requirement
- Provider
- Method
- Timestamp
- Pass/fail
- Retry count

Do not show:

- Raw ID documents
- Raw biometric media
- Provider data beyond what is needed

Actions:

- Retry allowed
- Manual review needed
- Mark support escalation
- Lock account pending verification

## Subscription Support

Show:

- Current tier: Free, Social, Select
- Platform: App Store, Play Store, Stripe
- Subscription status
- Entitlement source
- Linked partner beneficiary
- Purchase history summary
- Premium inventory
- Boost history

Actions:

- Restore entitlement after verified receipt
- Revoke promo/admin entitlement
- Grant limited support promo
- Inspect linked-partner benefit
- Flag billing abuse

Rules:

- Admin cannot silently create permanent paid entitlements without elevated permission.
- All billing actions require reason and audit log.

## Partner Link Review

Used for support and abuse.

Show:

- Active/past partner links
- Link/unlink history
- Beneficiary history
- Visibility settings
- Abuse flags
- Reports involving linked partner context

Actions:

- Force unlink only for safety/legal/support reasons
- Disable linked benefit if abusive
- Require verification
- Add cooldown

Rules:

- Admins must not change personal relationship state casually.
- Users retain normal ability to unlink themselves.

## Albums / Media Review

Show only when:

- Media is reported
- Media is flagged
- Legal/safety review requires it

Actions:

- Remove media
- Revoke grant
- Warn user
- Suspend album sharing
- Escalate severe content

Rules:

- Private albums are sensitive.
- Access must be audited.
- Moderators should not browse private albums without report/flag context.

## Playing Tonight Review

Show:

- Session owner(s)
- Mode: solo/together/social
- Session status
- Note/photo moderation status
- Zone, not exact location
- Reports/interactions

Actions:

- Remove session
- Remove session photo/note
- Warn
- Suspend Tonight access

## Psync Support

Show:

- Session ID
- Partner link
- Status
- Participants
- Deck size
- Started/completed/expired timestamps
- Recap summary
- Error states

Do not show:

- Private partner swipe-by-swipe history by default

Actions:

- Expire stuck session
- Resolve state mismatch
- Escalate bug

## Notifications Investigation

Show:

- Notification events
- FCM/APNs status
- User notification preferences
- Quiet hours
- Token state
- Deep link target status

Actions:

- Re-send only account/safety-critical notification where appropriate
- Mark stale
- Revoke invalid token

Do not:

- Send fake engagement notifications.

## Audit Log

Every admin action logs:

- Admin ID
- Role
- Action
- Target type
- Target ID
- Reason code
- Timestamp
- Before/after where relevant
- IP/device/session

Sensitive views log:

- Message content reveal
- Private media reveal
- Verification data reveal
- Exact support lookup

Audit logs should be immutable or append-only.

## Privacy Rules

Admin console must:

- Hide private data by default.
- Require reveal reason for sensitive data.
- Prevent bulk export of sensitive content.
- Watermark sensitive media views if feasible.
- Restrict exact location display.
- Redact analytics-style views.

## Admin API

Admin endpoints should be separate from consumer endpoints.

Suggested groups:

```http
/v1/admin/users
/v1/admin/reports
/v1/admin/moderation-cases
/v1/admin/risk
/v1/admin/messages
/v1/admin/albums
/v1/admin/appeals
/v1/admin/verification
/v1/admin/subscriptions
/v1/admin/partner-links
/v1/admin/playing-tonight
/v1/admin/psync
/v1/admin/notifications
/v1/admin/audit
```

Rules:

- Admin API requires admin auth, MFA, role check, and audit middleware.
- Admin API must never be bundled into the consumer mobile app.

Current bootstrap implementation:

- `/v1/admin/messages/review-queue` is server-only and disabled unless `PYNAPLI_ADMIN_API_TOKEN` is set.
- `/v1/admin/reports` uses the same server-only admin gate for report triage.
- `/v1/admin/appeals` uses the same server-only admin gate for appeal review.
- `/v1/admin/risk` uses the same server-only admin gate for account risk review.
- `/v1/admin/partner-links/{userId}` uses the same server-only admin gate for read-only partner-link review.
- Admin calls require `X-Admin-Token`, `X-Admin-Id`, and a reason header/body depending on the action.
- Listing held messages writes an `admin_audit_log` row because message body review is sensitive.
- Held message actions support release and remove, and both write audit rows.
- Report actions support mark under review, resolve, dismiss, and reported-media removal, and all write audit rows.
- Appeal actions support list, mark under review, and decision outcomes (`upheld`, `reduced`, `restored`, `verification_required`, `escalated`), and all write audit rows.
- Risk actions support list states, list events, clear, set tier, and mark reviewed, and all write audit rows.
- This bootstrap route is not a replacement for full admin accounts, MFA, role checks, and step-up auth.

## Metrics

Track:

- Report volume by category
- Median first response time
- Median resolution time
- Queue backlog
- Moderator action counts
- Appeal success rate
- False-positive risk recovery rate
- Spam held/deleted/released
- CSAM/severe escalation count
- Private media reveal count by moderators

## Implementation Checklist

- [x] Admin roles exist (two-tier operator/senior via `PYNAPLI_ADMIN_ROLES` = `id:role,...`): sensitive actions — PII reveals (user lookup / subscription support / partner-link review), entitlement grant/revoke, appeal decisions, and reported-media removal — require the senior role via `requireSeniorAdmin` (403 for operators/unmapped actors). No-op when unconfigured (every admin treated as senior, backward compatible). Because the actor id is self-declared over the shared token, the real second factor for these actions is the step-up MFA gate (E2c).
- [x] Step-up MFA on sensitive actions: when `PYNAPLI_ADMIN_MFA_ENFORCED` is set, the sensitive set (same endpoints as the senior-role gate) requires a valid per-admin TOTP code (RFC 6238, dependency-free) in the `X-Admin-MFA` header, verified against `PYNAPLI_ADMIN_MFA_SECRETS`; missing/invalid → 403 (`ADMIN_MFA_REQUIRED`). Off by default. This is the real second factor on top of the shared token + self-declared actor id. (A full per-admin identity/enrollment system remains future work.)
- [x] Admin actions are audit logged two ways: sensitive queue handlers write rich reason/target rows, and a general audit middleware (`AdminAuditPlugin`) records every successful admin mutation (2xx POST/PUT/PATCH/DELETE under `/v1/admin`, keyed on `X-Admin-Id`) as an `admin_request` row so nothing slips through.
- [x] User lookup exists. (`GET /v1/admin/users/{userId}` — consolidated view: status, profile summary, verification, subscription tier/source, linked-partner state, risk tier/score + recent reasons, report/block counts, active session/device counts, recent moderation actions. Requires `X-Admin-Reason` and writes a `user_lookup` audit row; excludes album media/message bodies/exact location/raw verification by default.)
- [ ] Partial: reports, appeals, risk, and held-message queues exist, and a unified backlog dashboard (`GET /v1/admin/dashboard` — counts-only across all four queues + risk tiers, admin-token gated like the metrics routes) now surfaces "what needs attention" at a glance. The full operator console (web UI, roles/MFA, audit middleware, API separation) remains — tracked as E2.
- [x] Reports queue exists.
- [x] Reports triage actions exist.
- [x] Reports triage actions are audit logged.
- [x] Reported-media remove action exists for media reports and writes an audit row.
- [x] Risk review exists.
- [x] Risk review actions are audit logged.
- [x] Message review queue exists.
- [x] Held message release/remove actions exist.
- [x] Held message review actions are audit logged.
- [ ] Partial: appeal API workflow exists; full senior-moderator separation, roles, MFA, and appeal UI are not complete.
- [x] Appeals queue exists.
- [x] Appeals review actions are audit logged.
- [x] Subscription support view exists. (`GET /v1/admin/subscriptions/support/{userId}` — read-only consolidated view: resolved entitlement (tier/source/linked-partner benefit), owned store-subscription records (platform/tier/status/product/period/verification timestamps), and linked-partner benefits granted + received. Requires `X-Admin-Reason` and writes a `subscription_support_view` audit row; excludes raw purchase tokens/order ids. Distinct from the entitlement CRUD under `/v1/admin/subscriptions/...`.)
- [x] Partner link review exists. (`GET /v1/admin/partner-links/{userId}` — read-only current/past link metadata, partner visibility, recent unlink count, and report context. Requires `X-Admin-Reason` and writes a `partner_link_review` audit row; excludes private media/message bodies/exact location and does not expose force-unlink/benefit-disable actions.)
- [ ] Partial: held-message sensitive queue requires a reason; broader sensitive reveal reason flow is not complete.
- [x] Held-message sensitive queue view requires reason.
- [ ] Partial: admin routes are under `/v1/admin`; production API separation, roles, and MFA remain.

## Do Not Implement

- Do not allow shared admin accounts.
- Do not expose private albums casually.
- Do not expose exact user location by default.
- Do not allow unaudited admin actions.
- Do not allow moderators to change user relationship links casually.
- Do not bundle admin routes/UI in the consumer app.
- Do not send fake notifications from admin tools.
