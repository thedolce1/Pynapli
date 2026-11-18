# Pynapli Admin and Moderation Spec

The server-only admin API used to moderate Pynapli, and the rules any admin tool must follow. There
is **no console UI** and none is planned now; operators call `/v1/admin/*` directly. Policy:
[MODERATION_AND_AGE_VERIFICATION_SPEC.md](MODERATION_AND_AGE_VERIFICATION_SPEC.md),
[TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md).

> Admin tools exist to protect users, enforce policy and resolve edge cases. They are audited,
> role-limited and privacy-conscious, and never a casual user-browsing tool.

## Scope (2026-11-17)

- **Active:** moderation queues: reports, held-message review queue, appeals, risk review, age
  verification review, photo verification review, pending profile photos, and entitlement grant/revoke.
- **Frozen** (no consumer, no further investment): dashboard counts, user lookup, subscription support
  view, partner-link review, product and notification metrics endpoints.

## Access model

- Admin routes are server-only and disabled unless `PYNAPLI_ADMIN_API_TOKEN` is set. Calls send
  `X-Admin-Token`, `X-Admin-Id` and, for sensitive actions, `X-Admin-Reason`.
- **Roles** (`PYNAPLI_ADMIN_ROLES` = `id:role,...`, `admin/AdminRoles.kt`): operator and senior.
  Sensitive actions (PII reveals, entitlement grant/revoke, appeal decisions, reported-media removal,
  risk set-tier, account lock and ban, photo verification review and revoke)
  need the senior role (`requireSeniorAdmin`, 403 otherwise). Unconfigured = every admin is senior.
- **Step-up MFA** (`admin/AdminMfa.kt`): with `PYNAPLI_ADMIN_MFA_ENFORCED`, the same sensitive set needs
  a per-admin TOTP code in `X-Admin-MFA`, verified against `PYNAPLI_ADMIN_MFA_SECRETS`
  (`ADMIN_MFA_REQUIRED` → 403). Because the actor id is self-declared over a shared token, MFA is the
  real second factor.
- **Audit:** sensitive handlers write rich reason/target rows to `admin_audit_log`, and
  `AdminAuditPlugin` records every successful admin mutation under `/v1/admin` as an `admin_request`
  row. Listing held messages writes an audit row because it shows message bodies.
- Rules: least privilege; no shared admin accounts; audit every lookup and mutation; a reason code on
  severe actions; audit logs append-only. Full per-admin identity and enrollment is future work.
- **Admin routes and UI are never bundled into the consumer apps.**

## Queues and actions

| Queue | Routes | Actions (all audited) |
| --- | --- | --- |
| Reports | `/v1/admin/reports` | list, mark under review, resolve, resolve + remove reported media, dismiss |
| Held messages | `/v1/admin/messages/review-queue` | list (needs reason), release, remove |
| Appeals | `/v1/admin/appeals` | list, mark under review, decide: `upheld`, `reduced`, `restored`, `verification_required`, `escalated` |
| Risk | `/v1/admin/risk` | list states and events, clear, mark reviewed; set tier, lock, ban (senior + MFA; lock/ban also end the account's sessions and close its sockets at once) |
| Age verification | `/v1/admin/verification/age/{id}/review` | review with reason |
| Pending profile photos | `/v1/admin/media/pending` | list photos the scanner held, oldest first (paged); approve (ready/approved, now public) or reject with a reason (removed; owner gets a system-safety push). Operator-level |
| Photo verification | `/v1/admin/verification/photo` | list pending selfies oldest first (needs reason; audited view), approve, reject with a user-visible reason, revoke a user's verification (all senior + MFA) |
| Entitlements | `/v1/admin/subscriptions/entitlements` | grant, revoke (senior) |

Moderation priority: (1) underage / CSAM / imminent harm, (2) threats, violence, doxxing,
(3) non-consensual content, (4) explicit public content, (5) spam and scams, (6) impersonation,
(7) other.

## Rules per area

- **Reports:** protect the reporter's identity from the reported user. Moderators see enough evidence
  to act, no more.
- **Held messages:** only suspicious or risky messages are held; clean users' normal messages are
  never delayed by the queue.
- **Risk:** don't expose detection thresholds widely; show tiers rather than exact scores where
  possible; high false-positive patterns should raise an engineering alert.
- **Appeals:** reviewed by a senior moderator not involved in the original decision; target response
  7 business days; CSAM and confirmed severe fraud may be non-appealable per policy/legal review.
- **Photo verification:** the reviewer compares the selfie's pose with the issued instruction and its
  face with the profile photos. Selfie URLs are short-lived signed URLs; selfies are deleted 7 days after
  the decision. Rejections and revokes need a reason the user sees.
- **Age verification:** never show raw ID documents, raw biometric media, or provider data beyond
  what is needed.
- **Billing:** no silent permanent paid entitlements without elevated permission; every billing action
  needs a reason and an audit row.
- **Partner links:** admins don't change relationship state casually; users keep their own ability to
  unlink. No force-unlink action exists.
- **Private albums:** sensitive; never browsed without report/flag context; every access audited.
- **Playing Tonight:** show zone, never exact location.
- **Psync:** never show a partner's swipe-by-swipe history by default.
- **Notifications:** never send fake engagement notifications from admin tools.

## Privacy rules for any admin view

Private data hidden by default; a reveal reason for sensitive data (message content, private media,
verification data); no bulk export of sensitive content; watermark sensitive media views where
feasible; no exact location; no album media, message bodies, exact location or raw verification data
in lookups by default.

## Open items

- [ ] Senior-review separation for appeals beyond the role check.
- [ ] Reveal-reason flow for sensitive data outside the held-message queue.
- [ ] Per-admin identity and MFA enrollment; separated admin API deployment.

## Do not

- Allow shared admin accounts or unaudited admin actions.
- Expose private albums casually or exact user location by default.
- Let moderators change relationship links casually.
- Bundle admin routes or UI in the consumer app.
- Send fake notifications from admin tools.
