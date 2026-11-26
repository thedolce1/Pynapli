# Pynapli Trust And Safety Spec

## Purpose

Pynapli's trust, anti-spam, anti-bot, abuse-prevention and risk-based friction model.

Core rule:

> Protect real users first. Slow down suspicious behavior before it causes harm. Hard-ban only when confidence is high or the violation is severe.

## Product Philosophy

Pynapli is a sensitive lifestyle community. Trust is not optional.

Bots, spam, fake accounts, scrapers, abusive users, and subscription exploiters damage:

- User safety
- Community trust
- Message quality
- Private album safety
- Subscription conversion
- App-store standing
- Long-term retention

Pynapli should not rely only on immediate bans. Instant bans teach attackers what triggered detection and create false-positive risk. The better approach is:

1. Score behavior continuously.
2. Apply targeted friction to risky actions.
3. Quarantine high-risk actions before delivery.
4. Give real users a path to recover.
5. Hard-ban confirmed severe abuse.

## Important Refinement

The older anti-bot notes describe aggressive "shadow degradation." The concept is useful, but Pynapli should avoid making the entire app mysteriously worse for borderline real users.

Use:

- Risk-based friction
- Action throttles
- Shadow review queues
- Discovery suppression
- Verification prompts
- Moderation review
- Transparent limits for likely real users

Avoid:

- Fake push notifications
- Fake matches
- Fake payment success
- Pretending subscriptions worked when they did not
- Random whole-app degradation
- Anything that could be considered deceptive commerce

Target abusable actions, not the entire app.

## Trust Score Model

Each account has a hidden risk score.

Recommended name:

- `RiskScore`

Avoid externally exposing the score.

Scale:

- `0`: trusted/clean
- `100`: confirmed bot, spammer, fraudster, or severe bad actor

Example storage:

```kotlin
data class AccountRiskState(
    val userId: String,
    val score: Int,
    val tier: RiskTier,
    val updatedAt: Instant,
    val reasons: List<RiskReason>,
    val lastReviewedAt: Instant?
)

enum class RiskTier {
    CLEAN,
    WATCH,
    FRICTION,
    QUARANTINE,
    TERMINATE
}
```

## Positive Trust Signals

Signals that reduce risk:

- Age verified
- Photo verified
- ID verified, if supported
- Completed profile
- Public photo approved
- Account age
- Normal browsing/session patterns
- Healthy message/reply ratio
- Low block/report rate
- Low ignore rate
- Good subscription/payment history
- Linked partner verified
- Event attendance with no reports
- Manual support contact with credible context
- Successful appeal/moderation review
- No rate-limit hits over time

## Photo Verification

Selfie photo verification (one still selfie in a server-chosen pose, reviewed by a person) is what the verified badge and the "Verified
only" filters mean. Server-owned end to end (erification/PhotoVerificationService.kt):

- The user asks to verify. The server picks a random pose from a fixed server-side list
  (PhotoVerificationPoses), valid for 10 minutes. Challenges are rate-limited (10/hour) and
  submissions too (5/day).
- The user uploads one selfie through the normal media upload path. It is a private
  erification asset: never in an album, never ready/approved, never returned to other users, never
  logged. Only the owner (upload URL) and senior admins (short-lived signed review URL) reach it.
- When Cloud Vision is configured, face detection runs on submit: zero faces or more than one face is
  rejected automatically (10-minute retry). One face still waits for a human. There is no automatic
  face matching against profile photos (Vision has no comparison API); approval is always a person.
- A senior admin with step-up MFA approves or rejects with a reason the user sees (24-hour retry).
  Approval writes profiles.face_photo_verified_at and erified_at; an admin revoke clears both.
- Decisions, reviewer id and timestamps stay in photo_verifications and dmin_audit_log. The selfie
  object and asset row are deleted 7 days after the decision (1 hour if never submitted) by the
  retention worker under JobLock.
- **Founder decision (defaulted):** replacing all profile photos does **not** remove verification. The
  spec was silent; an admin revoke is the tool when photos no longer match the person.
- This is not parity with other apps (2025-2026): Tinder's Face Check is a mandatory liveness video
  with ongoing matching against new photos, and Bumble added optional government ID verification.
  Because replacing photos keeps verification here, Pynapli has no equivalent of Tinder's ongoing
  photo matching.

## Photo pre-screening

New members' profile photos are screened automatically on upload so clean ones go live within
seconds instead of waiting for a person. Server-owned (`media/CloudVisionMediaModerationScanner.kt`,
`media/MediaUploadActions.kt`).

- **Rule it serves:** public photos (Grid, Swipe, profile) may be suggestive but not explicit
  (COMMUNITY_GUIDELINES, MODERATION_AND_AGE_VERIFICATION_SPEC "Public zone"). Private albums and chat
  media are consent-gated and may be explicit.
- **Flow:** upload-complete calls Google Cloud Vision SafeSearch on the photo's `gs://` object inside
  the request (8-second budget). The result decides the photo's state at once:
  - **Approved** (ready/approved, public immediately) only when adult ≤ `UNLIKELY`, violence ≤
    `POSSIBLE` and racy ≤ `LIKELY`, and all three are rated.
  - **Held for a person** (uploaded/pending, in `GET /v1/admin/media/pending`) for anything above a
    ceiling, an `UNKNOWN` or missing rating, any scanner error, a per-image error, a non-image, or a
    timeout. **Fail closed:** a scan we can't trust never approves.
  - **Never auto-rejected.** No SafeSearch result removes a profile photo or files a report or risk
    points against its owner; a moderator approves or rejects it.
- **Grid thumbnails only from approved photos (G2-2, 2026-11-26):** the server makes a 480 px
  thumbnail (GRID_IMPLEMENTATION_SPEC "Server-made thumbnails") only from the stored, screened
  original of an approved, live public profile photo. Held, pending, rejected, removed, deleted or
  out-of-album photos, private-album, chat and verification media never get one: eligibility is
  re-checked when the job claims the photo and again, in the same statement, when it marks the
  thumbnail ready; if the photo stopped being eligible meanwhile the written object is deleted. A
  reconciler deletes the thumbnail object of any row that is no longer eligible (every removal path,
  without hooking each), and the media purge keeps a row until its thumbnail is gone. Read paths serve
  a thumbnail URL only while the photo is approved and live, from the first moment a removal lands.
  The thumbnail is EXIF-stripped. A Cloud CDN edge copy can outlive a deletion only for holders of a
  still-valid signed URL (15 minutes), exactly as for originals.
- **What Vision sees (founder/legal decision, 2026-11-25):** only **public profile photos** (this
  scanner) and **verification selfies** (the separate face-count check above). Private-album and
  chat photos are intimate, consent-gated content and are **not** sent to Vision by default. The
  upload path names the target (`MediaModerationTarget`: profile photo, private album, chat media)
  and `MediaScanScope` decides; nothing is inferred from the asset.
  - Unscanned (default), a private-album photo is approved on upload and a chat photo sends, exactly
    what the manual and dev scanners always did for them: before this change, production ran
    `manual`, which held only profile photos and approved private-album and chat photos on upload
    with no scan.
  - `PYNAPLI_MEDIA_MODERATION_SCAN_PRIVATE_MEDIA=true` (default `false`) sends private-album and chat
    photos to Vision too, for the severe signal only: violence ≥ `LIKELY` removes a private-album
    photo and files a system safety report plus risk event, and refuses a chat send (no report).
    Adult and racy results never change private or chat media. A scanned chat send waits on the scan
    (up to 8 s). Turning it on is a founder/legal call and needs the privacy policy updated first.
  - The privacy policy must name Google Cloud Vision as a processor of profile photos and
    verification selfies (PATH_TO_TESTERS A9).
- **Decided defaults (founder deferred; the spec was silent on numbers):** the ceilings above. A
  lifestyle app's public photos are often racy, so racy is allowed up to `LIKELY`; adult content is
  held from `POSSIBLE`. They are config, validated at startup (a typo or `VERY_LIKELY` refuses to
  start): `PYNAPLI_MEDIA_MODERATION_MAX_ADULT` (default `UNLIKELY`), `_MAX_VIOLENCE` (`POSSIBLE`, and
  at most `POSSIBLE`, since `LIKELY` is the severe signal), `_MAX_RACY` (`LIKELY`). Tune only after the founder's live run on real images (PATH_TO_TESTERS A9).
- **Environments:** staging and production default to `cloud_vision`; local/test default to `manual`
  (and local auto-approves uploads). Production refuses `manual` at startup
  (`MEDIA_MODERATION_MANUAL_FORBIDDEN`) unless `PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION=true`
  is set on purpose (for example during a Vision outage, accepting that every new photo waits for a
  person). Cloud Vision without the media service account fails startup
  (`MEDIA_SIGNING_CREDENTIALS_REQUIRED`) rather than degrading to manual: ops should notice, and a
  silent fallback would leave new members photo-less with no alarm.
- **Environment names are checked:** `PYNAPLI_ENV` must be `local`, `test`, `staging`, `prod` or
  `production` (`UNKNOWN_ENVIRONMENT`), so a typo can't run production without its guards.
- **Placeholder name:** a profile without a name reads "New member". Members can't choose it,
  "Pynapli"/"Pynapli User", any name containing "pynapli" plus a staff word (support, team, admin,
  moderator, staff, official), or a bare staff word: `DISPLAY_NAME_RESERVED`
  (`profile/ReservedDisplayNames.kt`). Matching is on a letters-only skeleton: NFKC, format and
  zero-width characters dropped, any Unicode whitespace as a space, `Locale.ROOT` lowercase,
  diacritics stripped, common Cyrillic/Greek/Latin confusables folded to Latin. So no-break
  spaces, zero-width characters, a Cyrillic "е", punctuation and suffixes ("New member 2") don't get
  round it. Digit look-alikes ("Adm1n") are not folded. Members who already carry such a name are
  not renamed; the check runs when a name is set.
- **What the member sees:** their own pending photo, labelled "Being reviewed · only you can see it",
  in the album manager, Edit profile, the onboarding photo step and their own profile preview. The
  server returns a pending photo only to its owner (owner album listing); every other viewer's query
  filters to ready+approved.
- **Completion is once per asset** (review finding, 2026-11-25). An upload slot records the album
  kind it was opened for (`intended_album_kind`). Upload-complete (`completeMediaAlbumUpload`, one
  transaction with the asset row locked) refuses an album of another kind
  (`MEDIA_UPLOAD_ALBUM_MISMATCH`), refuses an asset already in another album or already completed
  (`MEDIA_UPLOAD_ALREADY_COMPLETED`, 409), returns the existing item and state for a retry into the
  same album without calling Vision again (only an asset with no recorded decision,
  `moderation_scanned_at IS NULL`, is moderated on a retry), and inserts the item and
  moves the asset pending_upload → uploaded together. Without this, a held profile photo could be
  completed again into the unscanned private album, turn approved, and go public through its
  profile-album item. Defence in depth: every public photo query (Grid, Swipe, profile, peek,
  Activity, Messages, Playing Tonight incl. the session photo, match moment, partner link) also
  requires `intended_album_kind = 'profile'`, a Playing Tonight session photo must be a profile
  photo, and V128 makes the database hold one live album placement per asset (partial unique
  index on non-removed items). V128 never hard-deletes: a surplus placement is soft-removed
  (`removed_at`, restorable by ops) and each step logs its row count. An asset in both a profile and
  a private album keeps the album of its kind; a pre-V72 asset with no kind keeps the **profile**
  placement (a member must not silently lose a profile photo) and, like any approved profile asset
  that also sat in a private album, goes back to the human queue, so nothing from a private album
  becomes public without a moderator. A single placement in the other kind's album takes that
  album's kind; a duplicate across a deleted and a live album keeps the live one. Residual: if a
  moderator rejects such a requeued photo it is gone from the private album too (its soft-removed
  private placement can be restored by ops).
- **Screened bytes can't be swapped** (review finding, 2026-11-25). The GCS upload URL lives 15
  minutes, so it is signed create-only: `x-goog-if-generation-match: 0` is a signed header (GCS
  requires every `x-goog-*` header a signed request uses to be in the canonical headers) and is in
  the slot's `uploadHeaders`, which every client sends verbatim. A second PUT to the same URL gets
  412, so the bytes Vision approved are the bytes served (GCS signed GETs and the CDN both read
  `media/{assetId}`). The client treats a 412 on a create-only upload as "an earlier attempt already
  stored it". Not done, by choice: pinning the object generation at complete time (an extra GCS
  metadata call per upload) — overwriting now needs the service account itself. Local dev storage
  still allows overwrite (local only). Web uploads need the bucket's CORS `responseHeader` list to
  include `Content-Type` and `x-goog-if-generation-match` (PATH_TO_TESTERS A9).
- **Object key:** Vision is given `gs://{bucket}/media/{assetId}`. Production keys carry no owner id
  (`mediaObjectKey`); only the local/static dev signers use `users/{ownerId}/...`.
- **Logging:** asset id and error type only; never the object path, a signed URL or the response
  body.
- **Not covered:** SafeSearch is not a CSAM detector (hash matching is a separate provider, still
  open) and does not read text in images.
## Met in person

A member who has met someone in real life privately confirms it from their conversation; once enough
independent confirmations exist, the weekly batch gives a badge the person can choose to show. Founder
decisions: threshold 3, a couple badge in v1, opt-in display (off by default). Selfie verification says a
person is real; "Met in person" says several members met them. Neither says what anyone did, and the badge
is not checked by Pynapli. Code: `server/.../metinperson/`, migration V122.

The promise this design keeps: nobody learns who met whom. Not the subject (whatever they do), not a
voucher (whatever they toggle), not someone holding a copy of the database without the key K3.

### Who can confirm whom (write time, `MetInPersonRules`)

- Voucher and subject are both photo-verified (`profiles.verified_at`, the verified badge condition).
- The voucher's account is active and at least 30 days old. The voucher's risk tier is NOT checked here
  (the menu would reveal the tier to its owner); it is applied at the batch.
- Messages between the two of them only: each sent at least one visible message (`message_type =
  'user'`, `moderation_status = 'visible'`; held and removed do not count, a message the sender later
  deleted does) at or after the moment both were in the conversation (the later of their
  `COALESCE(invitation_responded_at, joined_at)`); the earliest such message is at least 24 hours old and
  the latest at most 90 days old. In a group, other people's messages never count. Messages are checked
  only when confirming, never at the batch.
- Never (at write time, facts the voucher knows): any `user_profile_links` row between the two, any
  status; a block by the voucher. A link invite the subject sends therefore stops future offers from that
  person too. That is not an oracle: the menu shows the voucher nothing about the subject's other
  confirmations, and the subject learns nothing from whether a given person can still confirm them.
- Once per directed pair, ever: A confirming B and B confirming A are separate rows. A repeat of a
  standing confirmation changes nothing, not even its date.
- At most 10 given per voucher per rolling 30 days, counting withdrawn and voided ones: taking one back
  never refunds its slot. A couple confirmation uses two. One voucher's writes are serialised by an
  advisory lock (keyed by a token), so concurrent confirms cannot pass the cap.
- Taking back: silent, within 30 days of giving, also after leaving the conversation, and also when the
  person now hides from the voucher ("Hide from people you know"): it works by pair token, without that
  filter, and its answer never carries a name. Only the voucher must have been in the conversation named;
  whether the person is in it never changes the answer, so naming different groups cannot locate someone
  hidden. A retry the same day answers the same (the voucher's own state). Nothing to take back is the
  uniform refusal. After taking it
  back, the same pair cannot give it again for 180 days (the same uniform refusal); after that, or after
  the 24-month expiry, it is the same row restored with a new date (the first-given day never moves).
- Only from the conversation overflow menu, at the voucher's initiative; the menu asks the server for
  the viewer's own options when it opens (`POST /v1/met-in-person/offers`, the conversation in the body:
  no id is ever in a Met in person path, since request logs record paths), never on open or refresh. The subject
  cannot request one and is never told, in any channel. Every refusal is `MET_IN_PERSON_NOT_AVAILABLE`.

### Couples

The voucher may tick "and [partner]" only when the subject's active linked partner is also an active
participant of the same conversation (a group chat, which only forms through a partner invite), the
profile's own linked-partner rule shows that partner to the voucher in both directions
(`CoupleVerifiedFilterSql.partnerShownToViewer`, shared with the couples filter: partner active, visible,
not incognito, not quarantined or terminated, not blocked either way with the viewer, not hidden by the
viewer, and each owner's partner visibility lets the viewer see the other), and the partner independently
passes every rule above with the voucher in that conversation (their own messages between the two of
them). Confirming each partner from separate direct chats gives two personal credits, not a couple credit.
A couple badge needs 3 independent vouchers who each confirmed both partners together under the couple's
current link. Unlinking records the end (`vouch_link_ends`) and turns the couple badge off at once; couple
confirmations dated before the latest end never count again, and a couple badge published before the
link's latest change never shows (link rows are reused on relink). Personal badges stay.

### What counts at the batch, and whose act each exclusion is

The rule: nothing the subject does may change which confirmations count. Every exclusion is classified:

| Exclusion | Whose act |
| --- | --- |
| Withdrawn | the voucher |
| The voucher blocked the subject | the voucher (a block by the subject is ignored entirely) |
| The voucher's account inactive, deleted, or not photo-verified | the voucher, or the service (ban, verification revoked) |
| The voucher quarantined or terminated | the service. A single person's reports are capped below quarantine (`ReportScoringPolicy`), so the subject alone cannot cause it; `friction`, which one report can reach, is deliberately not excluded |
| A partner link between the two ever accepted, or a link invite the voucher ever sent the subject (before or after the confirmation) | the voucher: an acceptance needs the voucher to have sent or accepted the invite (founder decision 2026-11-24). An invite the subject alone sent and the voucher never accepted never counts, so the subject cannot exclude anyone. Read from `profile_link_history` (V124, trigger-kept, never edited, removed only with an account: first invite per direction, first acceptance), so a re-invite that resets `accepted_at`, or a decline, cannot erase it |
| A device both used on or before the day given | fixed at that day (device rows created later are ignored) |
| Voided by an admin | the service |
| Older than 24 months | time |
| The voucher's token matches none of the subject's conversation partners | never happens in practice: participant rows are never deleted |

The subject's own state (active, photo-verified, not quarantined or terminated, read from the stored tier;
lazy decay is not applied in the batch, so a tier can be stale until the account's next risk read) turns
the whole badge off, never a single voucher; the profile also hides the badge at once for a quarantined or
terminated subject.

Independence (fakery):

- Vouchers ever linked to each other (any acceptance on record in `profile_link_history`) or that ever shared a device count as
  one voucher. A voucher who shared a device with the subject before confirming does not count.
- At least one counted voucher must be "clean": not confirmed by the subject first or on the same day
  (first-given days never move, so the subject confirming someone later cannot change it). A same-day
  clique of four mutual confirmers cannot badge itself.
- The couple badge needs three independent vouchers by the same rules, at least one clean for both
  partners.

### Counting, delay and display

- Only `MetInPersonBadgeBatch` writes earned badges. It runs weekly at a random moment inside each UTC
  Monday-to-Monday week (`SecureRandom`): claimed in its own transaction holding the
  `vouch_batch_schedule` row lock (due check and move to next week's random time together, so it runs
  once across replicas and deploys), then members in chunks of 500, each its own short transaction; it
  never loads every confirmation. It refuses to run when the key check fails. The pass in progress, its
  evaluation time and a cursor live on the schedule row (V125): each chunk is chosen, stepped and the
  cursor moved in one transaction under the row lock, so a crash or deploy mid-pass resumes the same
  pass at the next tick instead of skipping a week, and nobody is stepped twice in a pass. A chunk that
  fails 3 times at the same cursor (V126 `pass_failures`) is then taken member by member, each in a
  savepoint; members that fail are skipped for that week and logged at error by count only (no user id),
  so one bad member cannot stall every future pass.
  `recomputeNow` (an operator's look) writes nothing and never steps the hysteresis; only the weekly pass
  advances the delay.
- Hysteresis (`MetInPersonHysteresis`): each crossing, on or off, takes effect only after a random 1 to 4
  further batches, chosen per subject per crossing and stored (`vouch_badge_states`, keyed by the subject
  token); turning off also needs the count to stay below the threshold for 2 consecutive batches first; a
  crossing that reverses before it lands is cancelled. Resulting window: from the confirmation that
  completes a threshold to the badge appearing is the wait for the next weekly run (0 to 7 days) plus 1
  to 4 more weekly runs, so roughly 1 to 5 weeks, never at the batch that first sees it. From the
  confirmation (or withdrawal, void, block) that ends it to the badge going off is the wait plus 2 runs
  plus 1 to 4, roughly 3 to 7 weeks. A voucher toggling cannot make the badge follow them: taking back
  costs a slot and blocks giving again for 180 days, and the off delay outlasts any weekly toggle.
- The badge is two booleans on the profile sheet (`PublicProfileDto.metInPerson`, `metInPersonAsCouple`),
  shown only when the person turned on "Show my Met in person badge"
  (`discovery_settings.show_met_in_person_badge`, off by default); the couple badge needs both partners'
  opt-ins and the partner mini visible to this viewer. Never a count, not a filter, not on Grid or Swipe.
  An admin freeze hides both at once and the batch keeps them off. The subject sees their own badge state
  (profile preview, export) exactly when everyone else would, never earlier: only published state is ever
  read.
- The voucher learns nothing at once: confirm and withdraw answer with the voucher's own state only.
- Nothing is notified: no notifier, Activity, broadcaster or outbox dependency; tests assert no
  `notification_outbox`, `activity_events` or `match_side_effect_outbox` row appears.

### Storage, the key, and what a copy of the database shows

`vouches(id, pair_token UNIQUE, subject_token, partner_token, link_token, first_given_at, created_at,
withdrawn_at, voided_at, key_version)`: no user, conversation or link id. Tokens are HMAC-SHA256 under K3
(`PYNAPLI_MET_IN_PERSON_TOKEN_SECRET`; fixed local default; staging and production refuse to start
without a strong value distinct from every other server secret, the contact block K1/K2 included) with
one domain prefix per kind: pair (ordered voucher > subject),
subject, partner, link, audit-voucher, key check. Dates are whole UTC days. `vouch_badges` is keyed by user
id because the profile reads it, but holds only the published booleans (plus a freeze); its row is deleted
when both are off and the member is not frozen. `vouch_badge_states` (pending crossings) is keyed by the
subject token. `vouch_link_ends` records when a link row last ended. `met_in_person_key` holds a check
value (an HMAC of a constant): writes, the menu, the admin view and the batch refuse to run when the
configured key does not reproduce it, so a lost or rotated key cannot silently reset caps or turn badges
off; account deletion and data export refuse loudly too (error log, exception, the deletion rolls back),
rather than silently leaving or omitting the person's rows; and staging and production refuse to start
when a stored check value does not match the configured key (`verifyMetInPersonKeyAtStartup`, read only,
after migrations). `key_version` marks rows for a future re-tokenising rotation (not built).
`vouches.voided_with_voucher` (V123) records that a voucher-level void voided the row, so undoing it
restores only those rows; `voided_at` is a whole UTC day like every other date. `profile_link_history`
(V124, not a Met in person table: user ids, kept by a trigger, removed with the account on deletion and
exported as `partnerLinkHistory`) is the durable partner-link record the
evaluator reads.

Migration numbering: this feature's tables are V122 (V123 to V125 follow it). An earlier branch build
shipped them as V120, which main later used for contact blocks: any database that ran the old V120 must
be reset (Flyway cannot reconcile the two V120s), which only affects local and branch-test databases.

The voucher's own rows (cap, withdraw, void-all, deletion, export) are found by recomputing their pair
tokens over everyone they ever shared a conversation with; participant rows are never hard-deleted (no
code deletes `conversation_participants` or `conversations`), so no extra record was needed. The batch
and admin view recompute each member's subject token and the pair tokens over that member's partners.

Someone with SQL-level access to the live database (not just a copy) sees more than a copy shows: row
system columns (`xmin`, the writing transaction id) and the order and adjacency of writes can tie a
`vouches` insert to other writes in the same or a neighbouring transaction (for example the voucher's own
request touching other tables), and query logs or `pg_stat_statements` can carry bound values. Tokens
keep the row itself anonymous; transaction-level correlation is out of scope and needs database access
control, not this design.

A copy of the database without K3 shows: the number of rows, their days (given, first given, withdrawn,
voided), and which rows concern the same (unknown) subject, since a subject's token is stable. It shows
who has a published badge (`vouch_badges` by user id, which is public anyway when shown). It does not show
who any row is about, who gave it, which conversation, or that two rows are a mutual pair: in a mutual
confirmation the two rows share no token. For a couple confirmation, the two rows (one per partner) share
the same `link_token` and the same day, so a copy shows "one couple got a joint confirmation that day", not
which couple or from whom; `partner_token` on each is a partner-kind token, unequal to either row's subject
token. Correlation remains possible with outside knowledge (for example, a subject token whose row count
rises the week a known badge appears). With K3, everything can be resolved by recomputing tokens.

### Abuse and moderation

Admin (`AdminMetInPersonRoutes`, new routes under the existing admin gate; the frozen admin endpoints are
untouched): `GET /v1/admin/met-in-person/users/{userId}` (published state, setting, independent
qualifying count now, each confirmation with its voucher resolved through K3 and reciprocal, closed-group
and shared-device flags), `POST .../confirmations/{id}/void` (body `{subjectUserId, voided}`; false
un-voids), `POST .../vouchers/void` (body `{voucherUserId, voided}`; the id is never in the path, which
request audit rows store) and `POST .../users/{userId}/freeze` (`{"frozen": false}` unfreezes). All need a
senior admin, step-up MFA and `X-Admin-Reason`, and write `met_in_person.*` audit rows. An audit row never
holds a voucher id in clear: it carries `voucherRef` (an audit-voucher K3 token) and the confirmation ids,
so a key holder can tell whose confirmations were voided and lower roles cannot; a subject appears as
`subjectRef`, an audit-subject token in its own HMAC domain, so it never equals the `subject_token` stored
in `vouches`. The view's audit row holds no counts. Undoing a voucher-level void restores only the rows
that void voided; a row voided on its own stays voided. Unfreezing someone with no badge row writes none.
A void is invisible to the voucher.

Not closed:

- A ring of three real, verified, unlinked friends on different devices who did not confirm each other
  first can badge each other (or a fake couple). Larger collusion goes further: a clique that staggers its
  confirmations over days (each confirms someone before being confirmed back) passes the clean-voucher
  rule, and five or more mutual friends always can. The reciprocal and closed-group flags help a moderator
  see it; nothing prevents it.
- The clean-voucher rule treats a same-day confirmation by the subject as "first". A subject who happens
  to confirm someone on the exact day that person confirmed them makes that one voucher unclean; this
  only matters if that voucher was the only clean one, and the subject cannot see days.
- A subject who has met few people can guess roughly who confirmed when the badge appears; the delay only
  blurs it.
- A voucher whose confirmation completes the threshold learns nothing at once but may notice the badge
  weeks later.
- The batch reads stored risk tiers (no lazy decay) and the couple badge's write re-checks the link at
  write time; the display then also requires the badge to postdate the link's last change, which covers
  an unlink racing the batch.
- Disappearing messages (checked 2026-11-24): the purge hard-deletes a message only after its timer plus
  `PYNAPLI_EXPIRED_MESSAGE_GRACE_DAYS` (default 30), and the write-time rules read the rows (hidden from
  members) until then. So the 24-hour first-message rule is reachable with every timer (1 h, 24 h, 7 d,
  30 d) at the default grace. What shrinks: the "last message within 90 days" and "both wrote" rules see
  only messages younger than timer + grace, so with a timer the window to confirm is about 30 days after
  the last message rather than 90. With the grace set below a day and a 1 h or 24 h timer, Met in person
  becomes unreachable in that chat. No per-participant stamps were added: they would keep message
  metadata past the timer the members chose.

### Account deletion and export

Deletion, inside the deletion transaction: the confirmations the person gave (their pair tokens over
their conversation partners), the ones they received (their subject token), their pending state and their
badge are deleted, and couple credit with them is cleared from their partner's rows. Export: a voucher's
archive lists what they gave (the subject's display name at export time, the day, the day withdrawn, with
partner or not); a subject's archive holds only their published badge state and setting.

## Suspicious Signals

Signals that increase risk:

- Mass first messages
- Identical or near-identical messages to multiple users
- Link spam
- External contact/payment solicitation
- Excessive private album requests
- Excessive flirts
- Swipe-right rate over normal human patterns
- Very high profile-view velocity
- Perfectly regular request intervals
- Repeated rate-limit hits
- High block/report rate
- Very low reply rate
- Account-farm device/network signals
- Same device fingerprint as banned account
- Suspicious location jumps
- VPN/proxy patterns combined with other signals
- Rapid photo/profile changes after reports
- Repeated partner link/unlink cycles to exploit subscriptions
- Payment fraud/chargeback signals
- Scraping-like API usage

## Severe Hard-Ban Triggers

Some events bypass gradual friction.

Immediate lock/ban triggers:

- Confirmed CSAM or attempted CSAM
- Credible threat of violence
- Confirmed identity fraud
- Confirmed payment fraud
- Confirmed ban evasion after prior severe ban
- Law enforcement or legal escalation requiring immediate action
- Repeated severe harassment after warnings
- Verified scam operation

For severe cases, preserve evidence according to legal/privacy policy and restrict access immediately.

## Risk Tiers

### Tier 0: Clean

Score:

- 0-29

Behavior:

- Normal app experience.
- Normal rate limits.
- Normal discovery.

### Tier 1: Watch

Score:

- 30-49

Purpose:

- Increase observation and add light friction without harming real users.

Actions:

- Slightly lower first-message limits.
- Slightly lower flirt limits.
- Require cooldown between album requests.
- Increase logging for abuse signals.
- Add non-blocking verification nudges.
- Exclude from some high-visibility algorithmic boosts.

Avoid:

- Whole-app slowdown.
- Visible punishment language.

### Tier 2: Friction

Score:

- 50-69

Purpose:

- Limit harm while giving real users a recovery path.

Actions:

- Reduce first-message limits sharply.
- Rate-limit private album requests.
- Rate-limit flirts.
- Limit swipe velocity.
- Require verification for continued high-volume actions.
- Reduce grid/swipe visibility.
- Hold suspicious messages with links for review.
- Suppress suspicious Intro Notes.
- Disable Boost purchase eligibility until review.

User-facing copy when needed:

> Some actions are temporarily limited because your account activity looks unusual. Verify your profile or contact support.

### Tier 3: Quarantine

Score:

- 70-89

Purpose:

- Prevent harm while determining if the account is malicious.

Actions:

- Hide from other users' Grid/Swipe discovery.
- Hold outbound first messages in review queue.
- Disable private album requests.
- Disable Intro Notes.
- Disable link sending.
- Disable Boost/Super Boost.
- Disable partner-link beneficiary changes.
- Require verification or support review.
- Route account to moderation dashboard.

Important:

- Do not fake successful delivery.
- If a message is held, the sender can see "Sent" only briefly while delivery is pending, but backend must not claim recipient delivery/read state.
- If challenged, support should be able to explain that delivery was limited due to unusual activity.

### Tier 4: Terminate

Score:

- 90-100 or severe trigger

Purpose:

- Remove confirmed harmful actors.

Actions:

- Lock account.
- Revoke sessions/JWTs.
- Remove from discovery.
- Stop messages.
- Stop album access.
- Revoke active Boosts where allowed by policy.
- Flag device/payment/network indicators.
- Preserve moderation evidence.
- Send ban notice unless legally unsafe.
- Offer appeal only where appropriate.

No appeal:

- CSAM
- Confirmed fraud
- Law-enforcement-triggered severe restriction

Appeal allowed:

- Conduct bans
- Suspected spam
- False-positive automation

## Targeted Friction By Surface

### Grid

Risk controls:

- Limit first messages from Grid.
- Reduce profile visibility.
- Reduce ability to appear in "Online now" or "Tonight."
- Limit view velocity.
- Suppress scraping patterns.
- Require verification for high-volume browsing.

Do not:

- Reset innocent users' grid state.
- Randomly break browsing.

### Swipe

Risk controls:

- Limit swipe velocity.
- Limit Like/Super Like volume.
- Suppress suspicious Intro Notes.
- Exclude from Boosted placement.
- Prevent match creation when risk is high and pending review.

Do not:

- Create fake matches.
- Fake Likes You activity.

### Messages

Risk controls:

- First-message limits.
- Duplicate-message detection.
- Link blocking.
- Shadow review queue for suspicious first messages.
- Delivery delay for borderline messages.
- Recipient safety filters.

Message states should be honest internally:

- queued
- held_for_review
- delivered
- blocked
- removed

Client copy can be minimal, but backend state must not lie.

### Private Albums

Risk controls:

- Album request rate limits.
- Verification requirement after suspicious behavior.
- Disable requests in high-risk tiers.
- Revoke suspicious grants if account is locked.

Never paywall or block:

- Revoking album access.
- Reporting album abuse.

### Partner Links

Risk controls:

- Link/unlink cooldowns.
- One active subscription beneficiary.
- Fraud detection for cycling partners.
- Verification requirement if abuse detected.
- Server-side partner-link activation cooldown after recent unlink; unlink itself remains available.

Suspicious pattern:

- Linking with many accounts over short time.
- Switching beneficiary after each billing cycle.
- Account farms linking to one paid account.

### Activity

Risk controls:

- Suppress activity generated by quarantined accounts.
- Hide spam flirts.
- Remove activity from blocked/banned users.
- Do not let high-risk users farm Viewed Me visibility.

## Device And Account-Farm Signals

Use device and network signals carefully and privacy-consciously.

Possible signals:

- Device model/manufacturer
- OS version
- App version
- Install/session identifier
- App attestation/App Check signal
- Push token reuse
- Payment account reuse
- Network/IP reputation
- Velocity of accounts per device/network

Rules:

- Hash device fingerprints before storage.
- Do not store raw unnecessary identifiers.
- Treat fingerprint matches as risk signals, not always proof.
- Provide manual review for non-severe false positives.
- Document retention windows.

## Location Spoofing

Signals:

- Impossible travel velocity
- Large jumps combined with suspicious actions
- GPS/network/timezone mismatch
- Repeated travel-mode abuse
- Emulator/simulated location indicators where available

Actions:

- Require verification.
- Temporarily disable Travel mode.
- Reduce discovery visibility.
- Queue for review.

Do not hard-ban solely for one unusual location jump.

## Rate Limits

Rate limits should be Redis-backed and adjustable by risk tier.

Categories:

- Signup/login
- Password reset
- Grid query
- Profile views
- First messages
- Message sends
- Flirts
- Album requests
- Swipe actions
- Intro Notes
- Partner link requests
- Reports

Rate-limit hits should feed risk score, especially repeated hits.

Rules that must hold (implemented; numbers live in `RateLimitService.POLICIES`):

- **Sign-in can't be used to lock someone out.** Only failed sign-ins count, checked before the
  password: 10 failures per hour per account + client IP (the trusted proxy hop,
  `PYNAPLI_TRUSTED_PROXY_HOPS`) then `RATE_LIMITED`, so guessing from one address is bounded while
  the real user on another network still signs in. Past 100 failures per hour per account from all
  addresses, each attempt is delayed 3 s and a warning is logged with an account-hash prefix; it is
  never refused. Successful sign-ins spend nothing. The suspended-account appeal shares this budget.
- **Request-level limits** (`RequestRateLimitPlugin`, Redis-backed, fail open): bodies over 1 MB are
  refused 413 on the bytes actually read, so chunked or header-less bodies can't dodge the cap (the
  local media upload is exempt). Global per-minute ceilings on all traffic: 1,200 per client IP,
  120 per IP without a session, 600 per signed-in user (probes, `/metrics`, webhooks and the
  verification callback exempt). Route rules in `REQUEST_LIMIT_RULES`; signed-in rules are keyed by
  the user behind the token, so a fresh sign-in doesn't reset them. Per-IP rules and the global
  ceilings are off only when `PYNAPLI_ENV=local`.
- **Per-user route budgets** (hourly): message sends 600 per user across all conversations and 180
  per sender per conversation (text and media together, keyed by route + conversation id); profile
  fetches (`GET /v1/profiles/{id}`) 300; location updates 360. All keyed by the account, not the
  session.
- **GET routes and enumeration.** Rate-limited reads: `GET /v1/profiles/{id}`, the private-album
  read, admin per-person lookups (`/v1/admin/users/{id}`, `/v1/admin/subscriptions/support/{id}`,
  `/v1/admin/partner-links/{id}`: 300/h per IP), plus the service-level grid, travel-search and
  profile-view limits. Every other GET has only the global ceilings, by design: they return the
  caller's own data (`/v1/me*`, settings, blocks, hidden, reports, appeals, sessions, entitlement,
  activity, exports), or are membership-checked by an unguessable id (message threads, Psync
  sessions, export downloads), so walking ids reveals nothing.
- **Realtime sockets.** Frames over 64 KiB close the socket (1009); the pre-auth token frame is
  refused unparsed over 4 KiB. One account may hold 5 sockets per stream per replica: a sixth closes
  the *oldest* (`TOO_MANY_STREAMS`), because refusing the newest would lock out a user whose old
  socket is still half-open after a network change. Sockets re-check their session at once when the
  account's sessions are revoked (ban, lock, sign out everywhere), on every replica.
- **Auth keeps working when Redis is down.** Sign-up, sign-in, refresh and password reset/change
  limits fall back to bounded in-process counters (`RateLimitOutageFallback`: 50,000 keys, LRU,
  per replica) only while Redis fails; a warning is logged when it takes over and an info line when
  Redis is back (fallback counters are then dropped). `/metrics` exports
  `pynapli_rate_limit_fallback_active` and `pynapli_rate_limit_fallback_checks`. Other per-feature
  limits still fail as before; request-level limits fail open.

## Recovery And Appeals

False positives will happen.

Recovery paths:

- Normal behavior over time decays score.
- Photo verification reduces score.
- Age/ID verification reduces score.
- Support contact creates review task.
- Moderator can reset or lower score.
- Appeal path for non-severe bans.

Recommended score decay:

- Risk score decays slowly toward 0 after normal activity.
- Severe reasons should not decay without manual review.

Implemented (`safety/RiskDecayPolicy.kt`, `JdbcRiskRepository`, migration V109):

- The score is stored in two parts: `decayable_points` (minor, behavioural points) and the rest, which
  never decays. Only **rate-limit hits** (`rate_limit.*`) are decayable today; the list is an
  allow-list in `RiskDecayPolicy.DECAYABLE_SOURCES`. Reports, message-review holds, media moderation,
  location (impossible travel, repeated Travel switching), device/account-farm signals, verification
  events and every admin action are non-decaying, as is any source added later until it is classed
  deliberately. Whether message-review holds, repeated Travel switching or unconfirmed reports should
  also decay is an open founder question.
- Rate: 1 decayable point per day, linear (engineering choice; the spec gives no rate). A few
  rate-limit hits (2 to 6 points each) clear within a week.
- Lazy, no background job: due decay is applied under the account's row lock whenever its risk state
  is written or read through `RiskService.getState` / `RiskPolicyService`, the tier is recomputed from
  the new score, and each application is written to `account_risk_events` as `decay.minor`. Decay can
  take an account out of watch or friction (or further, if its score was all minor points). Discovery
  SQL and the admin queue read the stored tier, so an account untouched since its points fell due keeps
  the older tier there until its next read or write.
- Admin set-tier, lock and ban set the score absolutely and zero `decayable_points`, so decay never
  takes an account below an admin-set score; only minor points recorded after the admin action decay.
- Verification reductions come off the decayable part first, then the rest; the total drops by the full
  amount and is floored at 0, unless an admin floor applies (next item).
- **Verification never lowers an admin-set score (founder decision).** Admin set-tier, lock and ban
  write `admin_floor_score` (the score they set) in the same statement; mark-reviewed does not touch it.
  An age or photo verification reduction stops at that floor, and applies nothing when the score is
  already at or below it. Points added after the admin action can still be reduced, down to the floor.
  The ledger row is still written (so the photo credit counts as used); when the floor limited it, its
  `delta` is what actually applied (possibly 0) and the metadata carries `requestedDelta`,
  `adminFloorScore` and `limitedByAdminFloor`. An admin setting clean writes a floor of 0, which
  removes the protection. Migration V112 backfilled the floor from the most recent `admin_review.set_*`
  or `admin_enforcement.*` event's `score_after` and changed no score: accounts a verification had
  already taken below their admin score stay there (they show an age or photo verification event after
  the latest admin event with `score_after` below `admin_floor_score`).
- Points recorded before V109 were not backfilled: they stay non-decaying.
- Photo verification reduces score: an approved selfie verification records `verification.photo_verified`
  (-20, the same as age verification; the spec gives no amount) at most once per account, so a revoke
  and re-approval never subtracts twice. Migration V110 backfilled the same reduction (same source,
  reason and delta, metadata `{"backfill":"V110"}`) for accounts that were photo-verified before it
  existed, applied the way a live approval applies it (decayable part first, floored at 0, tier
  recomputed; verified accounts with no risk row get a 0/clean row and the ledger row, as live does).
  It skipped any account with an `admin_review.set_*` or `admin_enforcement.*` event, consistent with
  the admin-floor rule above. Couple verification has no approval path of its own (it is
  derived from both partners' photo verification) and gets no separate reduction.

Support signal:

- A coherent support request should be treated as a false-positive signal, not proof, because some bad actors will also contact support.

## Contact Blocks ("Hide from people you know")

Built 2026-11-20 (V119, `server/.../contactblocks/`). A member A enters email addresses of people who
should never come across them. If a member X with a **verified** address owns one of them, and the
two had no prior interaction, X never sees A anywhere, and nothing A does reaches X.

**The acceptance rule.** Nothing A can observe may depend on whether an address belongs to a member:
no list, count, error, change in anything A sees, or request timing. So the rule is **one-way and
passive**: it is applied only to X's reads and to pushes and realtime hints addressed to X, never to
anything A sees. A two-way version (X also disappearing for A) is forbidden: a profile vanishing when
A adds an address would confirm whose it is.

### Requests (A's side)

`GET /v1/me/contact-blocks` → `{count, maxAddresses}`; `POST /v1/me/contact-blocks {email}` and
`POST /v1/me/contact-blocks/remove {email}` → `204`; `DELETE /v1/me/contact-blocks` (remove all) →
`204`. Each does the same work for any valid address: the per-request limit (`BLOCK_OR_HIDE`), for an
add the daily budget (100 adds a day, every attempt counts), the strict syntax check
(`EmailAddressRules.isStrict`, `INVALID_EMAIL`), normalisation exactly as `users.email_hash`
(`normalizeEmail`: trim, lower-case), one HMAC, and **one statement on A's own rows** (never `users`);
an add then only nudges the resolver (`ContactBlockResolutionTrigger`), it never resolves. A duplicate
add, or removing an address never added, answers the same 204. Cap: 1,000 addresses
(`409 CONTACT_BLOCK_LIMIT_REACHED`, from A's own count only). There is no readable list: the server
cannot show one (it keeps only a keyed code), so the count includes non-members by construction.

### Storage and keys

`contact_blocks(id, blocker_user_id, email_token, target_token NULL, created_at, released_at NULL)`
(V119, V120), unique on (blocker, email_token) among rows with `released_at` NULL (A's addresses;
tombstones, below, sit beside them). `email_token = HMAC-SHA256(K1, normalised address)`. `target_token` is NULL
while the address belongs to no verified member ("pending"), `HMAC(K2, "target:" + X)` once bound, or
`HMAC(K2, "exempt:" + X)` when the pair was exempt (decided once, kept). `users.contact_token =
HMAC(K2, "member:" + email_token)` for a verified member, written only when the server holds the
plaintext (sign-up verify, every successful email sign-in). It is deliberately not the row's own
`email_token`, so the two tables can't be joined without K2. K1 = `PYNAPLI_CONTACT_BLOCK_EMAIL_KEY`,
K2 = `PYNAPLI_CONTACT_BLOCK_TARGET_KEY`: fixed development values locally; staging and production
refuse to start without explicit, strong values distinct from each other and from the cursor/deck
secrets (`CONTACT_BLOCK_KEYS_REQUIRED`, `CONTACT_BLOCK_KEYS_MUST_DIFFER`) and from the "Met in person"
K3 (`MET_IN_PERSON_TOKEN_SECRET_REQUIRED`). Rotating a key orphans every
stored code (all rows go inert); no rotation path exists.

**What a leak reveals.**
- *The database alone* (both tables, no keys): how many addresses each member added and when, and that
  several members added the same (unknown) address; which rows are settled (bound or exempt look the
  same) and which pending. Not the addresses, not whom any row points at, not which members are
  hidden from whom.
- *With K1 only*: anyone can test a candidate address ("did any member add boss@example.com, and who?")
  against `email_token`. Not whom a settled row points at.
- *With K2 only*: `users.contact_token` can be joined to rows (`HMAC(K2, "member:" + email_token)`), and
  every settled row can be linked to its member by computing target/exempt tokens per user id: the full
  "A hides from X" graph, without the addresses.
- *With both*: everything above plus testing addresses. K1 and K2 must be stored separately from the
  database credentials (Secret Manager), and K2 especially: with K2 a database copy shows who hides
  from whom.
- Tombstones add, per row, when the address was removed.

### Interaction (the exemption; founder decision 2026-11-23)

A pair is exempt, and left completely alone, **only when X did something A could already observe**
(`ContactBlockInteractionSql`), at any time up to resolution:

- an Activity row X caused for A (a profile view A can see, a flirt, a like, a Pineapple Like, an album
  request or grant, a partner chat invite, a Psync or Playing Tonight row);
- a like or Pineapple Like by X on A (a pass produces no visible signal);
- a match between them that X could have seen: not one made inside an earlier bound period of the same
  pair (a Psync together match or a raced like while A hid; the tombstone's `created_at`..`released_at`
  window), so a release and re-add can't turn a match X was never shown into an exemption;
- a conversation holding both of them in which X has **written** (V121
  `conversation_participants.first_written_at`, set by a trigger on every visible message insert and on
  a held message's release, so no write path can miss it and the retention purge of disappearing
  messages can't erase it) or **read** (`last_read_at` moved past X's `joined_at`; also durable);
- a partner link invite from X, a private album request from X, an album grant by X;
- a Psync like by X on A.

Nothing A did toward X, before or after the add, ever exempts the pair: if X never did anything A could
see, binding changes nothing A observes, so there is nothing to protect. The common case therefore
works: A spots a coworker on the Grid, opens the profile to be sure (a view), then adds the address; the
pair binds, and the view, like everything else from A, disappears from X's views (filters apply to all
of A's rows, whatever their date). X's favourite of A is private to X and doesn't count; an empty
conversation X opened is invisible to A until X writes, so creating it doesn't count either; an
ordinary block by either is handled by the block itself. Known over-counts (they exempt, never leak):
a re-invite into a group X left resets X's `last_read_at`, which reads as "read".

**Reciprocal races.** The bind takes the pair advisory lock (`SWIPE_PAIR_LOCK_NAMESPACE`) and checks the
exemption under it, in its own transaction. Every path where X does something A sees calls
`ContactBlockVisibility.noteActToward` **after its own write committed**: Swipe like and Pineapple Like,
like-back, flirt, private album request, a message in a one-to-one conversation (sent visible), a Psync
like, a partner link invite. It takes the same pair lock in its own transaction. So either the bind's
check ran after X's act committed and saw it (exempt), or the bind held the lock first and
`noteActToward` waits for it, then finds the bound row and flips it. The flip turns the bound row into a
tombstone (`released_at` = now: what A did while hidden stays hidden from X) and inserts an exempt row
for the same address, so A's count doesn't move. Favourites are private and never flip; opening an
empty conversation doesn't flip (the first message does). Residual: a held first message released
later by moderation doesn't flip (it is caught only if released before the bind's check).

### Resolution (off A's request path)

- **A adds an address whose member already has a token:** the worker (`ContactBlockResolverWorker`, its
  own background scope, one replica per tick via `PostgresJobLock`) runs after a nudge or every minute,
  always after a random 5–30 s delay, in batches of 500, over pending rows from the last 30 minutes. At
  most once a day across replicas it pages through every pending row instead (rows missed while the
  server was down). `contact_block_job_state` records the claim (`last_full_sweep_at`, an attempt) and
  the success (`last_success_at`, V121) separately: a sweep that failed is claimed again after an hour,
  a successful one not for a day. Acts A makes toward X in those 5–30 s reach X and
  then vanish when the row binds; this window is accepted, since binding on A's request would put a
  membership-dependent lookup on A's request path.
- **X proves the address at sign-up verify** (and the local dev sign-up route, which does exactly what
  verify does, so seeded and demo accounts are verified and tokened): the token is stored and every
  pending row of the address is settled after the account is created and before the session is
  returned. **Every successful email sign-in** does the same (accounts verified between V118 and V119).
- **Settling there can never fail the request** (`ContactBlockSettling`): it can only fail when someone
  added the address, so a failure would be a signal and, at verify, a lockout (the code is already
  spent). It logs the exception class only, queues the address on the worker, and returns the session.
  The settle transaction (and the token write before it) runs with `SET LOCAL lock_timeout = '2s'`, so
  a held user row or pair lock fails fast into that path instead of stalling the sign-in.
  The window when it fails: until the worker's next tick (5 to 30 seconds), that address's rows are not
  yet applied to X's reads. The retry queue is **in memory, per instance** (at most 10,000 entries): a
  restart in the window loses the retry, and another replica doesn't see it; X's next sign-in settles
  again, and so does the daily full sweep (pending rows of an address whose member has a token). The
  30-minute recent pass looks at recently *added* rows only; picking up rows of recently *signed-in*
  addresses would need a per-sign-in record (an extra write on every sign-in), so it isn't done.
- **Unverified accounts are never bound.** There are no real users yet; any account created without a
  confirmed email (before V118) needs the verify-on-next-sign-in step (ONBOARDING_AUTH_SPEC, not built)
  before launch, or it is never covered.

### Removing an address (tombstones)

Removing a bound row (or remove-all) does not delete it: it gets `released_at`. A appears for X again
from then on, but nothing A did before `released_at` ever reaches X (Activity rows by their first
occurrence, messages by time, conversations A started before, link invites, album grants and requests,
matches, export rows), so a removal can't dump a backlog on X whose timing would line up with it. A
pending row is deleted. The remove is one statement either way, and A's count drops by one either way.
An exempt row's tombstone matches no viewer lookup and changes nothing. A re-added address gets a new
row beside its tombstone and binds again under the same rule. Deleting A's account deletes tombstones
too; deleting X's deletes tombstones pointing at X.

**Growth.** Every release of a pair (remove, remove-all, race flip) keeps the four newest older
tombstones of that (blocker, target) pair plus the new one and deletes the rest, in the same statement
(`TOMBSTONES_KEPT_BEFORE_NEW`). For hiding acts the newest tombstone subsumes older ones (it hides
everything dated before a later time); the few kept only preserve recent bound windows for the match
rule above. So add/remove cycling one address leaves at most five rows for the pair however long it
runs; more rows need distinct members, which the add caps (1,000 active, 100 adds a day) bound. Beyond
five cycles an older bound window is forgotten, so a match made in it would count as an interaction
(exempt: a failure to hide from X, never a signal to A).

### Where X could come across A, and how it's suppressed

The rule (`ContactBlockVisibility`: one HMAC, one indexed lookup) is applied after the shared rank
caches, which stay free of per-viewer exclusions.

| Surface | Code | Suppression | Test |
| --- | --- | --- | --- |
| Grid (incl. Visiting soon, travel) | `GridService` | dropped with hides; partner mini and couple badge stripped; out of "Verified couples only" | two-worlds, binding (partner, rank cache) |
| Swipe deck, rewind, like-back, submit | `SwipeDeckQueryAction`, `SwipeRewindAction`, `SwipeService.likeBack`, `SwipeSubmitAction` | excluded before ranking (after the cache); minis stripped; rewind refuses; like-back `LIKE_BACK_NOT_AVAILABLE`; stale card `INVALID_SWIPE_TARGET`; a raced like flips the row to exempt | two-worlds, binding |
| Psync decks and submits | `PsyncDeckCoordinator`, `PsyncActiveSessionActions`, `SwipeService.queryDeckForPsync` | **per person**: the shared deck still holds A (so X's partner Q sees and answers A, and A's world is the same either way); X's view leaves A's card out and X's submit on it is `PSYNC_INVALID_CARD`; every `deckSize` X gets (deck, action response, session DTOs: create/accept/start/pause/resume/get/current/leave) drops by one per hidden card X hasn't answered, while a card X answered before the bind stays counted (so X's count and acted count agree and nobody finishes early); each partner finishes at their own count | Psync, edges |
| Psync together matches | `PsyncMatchResolution` (rows kept), `MatchCelebrationService`, `MessagingService.openMatchConversation`, inbox listing, export | A–X match rows stay; no moment for X (the hidden half of a pair moment drops, P's own moment stays); no hint (subjects of the change); the A–X conversation is never listed; X's export leaves the match out | Psync |
| Playing Tonight feed and map zones | `TonightFeedService` | dropped like a blocked owner; map zones come from the feed rows; there are no server-side aggregate counts | binding (Tonight) |
| Favorites | `FavoriteService` | can't be starred (`PROFILE_NOT_FOUND`), dropped from the list, minis stripped | two-worlds |
| Profile fetch by id, partner on a profile | `ProfileService.getPublicProfile` | `PROFILE_NOT_FOUND` (no view recorded); partner removed | two-worlds, binding |
| Activity feed, counts, badge | `JdbcActivityFeedSql`, `JdbcActivitySummarySql`, `ActivityFeedActions` | all of A's rows filtered in SQL (tombstones: rows first seen before the release); pair names stripped | two-worlds, binding (coworker, tombstone) |
| Activity acts toward A | `ActivitySignalActions` | X's flirt/album request `*_NOT_ALLOWED`; A's realtime hints suppressed; a raced act flips to exempt | two-worlds |
| Pushes and the notifications list | `NotificationEnqueueActions` | outbox write skipped when the actor hides from the recipient (the gate read still runs) | two-worlds, Psync |
| Messages inbox, thread, preview, unread | `listConversationIds`, `JdbcMessagingMessageReadSql`, `MessagingConversationGuard`, `MessagingDtoMapper` | listed by the same rule as opening: a conversation where every other participant hides from X is not X's (any call `CONVERSATION_NOT_FOUND`); a group with any visible participant is listed with A stripped from participants and invites; A's messages are left out of pages, preview and unread and never make the thread visible; nothing A causes moves it up (`conversations.updated_by_user_id`, V121, records who caused the last bump: a message, a deletion, leaving, a timer change, an invite naming them; when that's A, X's sort time falls back to X's last visible message); "read" counts only participants X can see; A's timer notice is shown with the nil sender (the timer is a property everyone in the thread sees, so the change is explained, attributed to nobody); a match made while A hid never lists the conversation nor flags a new match (`MATCHED_DIRECT_CONVERSATION_FOR_VIEWER_CLAUSE`, `loadNewMatch`) | two-worlds, conversations, edges |
| Quoting or replying to A's message | — | no quote or reply-to feature exists | — |
| Met in person (options, confirm, take back, export) | `MetInPersonService.snapshot` (reuses `ContactBlockVisibility.hiddenFrom` and messaging's `isConversationHiddenFor`), `JdbcMetInPersonSql.conversationPartnersVisibleTo` (`ContactBlockSql.notHiddenFromViewer`) | a conversation whose every current co-participant hides from X answers `MET_IN_PERSON_NOT_AVAILABLE`, exactly like an unknown conversation (options, confirm and take back alike); in a group A is never offered, confirmable or named as a partner; X's export leaves out confirmations X gave A. The weekly badge batch is untouched: it reads only stored confirmations, which only the guarded endpoint writes, and its output (A's badge) is shown only where A's profile is, which already hides A from X | `MetInPersonContactBlockIntegrationTest` |
| Message realtime (deltas, typing) | `MessagingRealtimePublisher` | no hint to anyone the actor, an invited partner, or a subject of the change hides from | two-worlds, Psync |
| Partner chat invites, bring-your-partner note | `MessagingDtoMapper`, `JdbcMessagingPartnerOfferSql` | invites naming A stripped; A never named or considered in the offer | conversations |
| Partner link invites | `ProfileLinkService` | not in X's summary (tombstones: invites sent before the release); accept/decline `PROFILE_LINK_INVITE_NOT_FOUND`; X→A `PROFILE_LINK_TARGET_NOT_FOUND`; admin view unfiltered | two-worlds |
| Private albums | `MediaPrivateAlbumAccessActions` | open/request `MEDIA_ALBUM_NOT_FOUND`/`INVALID_ALBUM_REQUEST_TARGET` | not integration-tested |
| Presence stream | `PresenceStreamRoutes` | no presence of A | not integration-tested |
| X's data export | `AccountDataExportArchiver` | Activity, album grants/requests received, conversations whose every other participant hides from X (the inbox rule), link invites, matches with A left out (tombstones: those dated before the release) | two-worlds, Psync |
| Search | — | no people search exists | — |

Not affected, on purpose: reports and ordinary blocks, moderation and admin tooling, risk and safety
signals, account deletion. A cannot hide from moderators or from someone who has reached out to them.

Other `matches` readers are permission predicates, not surfaces: the "matches only" messaging and
grid-message policies (`JdbcMessagingConversationPolicySql`, `JdbcProfileSql`), the "matches only"
partner visibility (`JdbcGridHydrationSql`, `JdbcProfileSql`, Swipe hydration), and the likes-you
exclusion of matched pairs (`JdbcSwipeActionSql`). While A's row is active A never reaches X there.
After a release, a match made while A hid still grants X what A's "matches only" settings grant any
match (A accepted those for that match from A's own view), so A's world is unchanged; X could infer a
match they were never shown from being allowed to message A. Accepted residual.

**Cost on the hot paths.** The repositories that apply the rule in SQL (Messages, Activity) first run
one indexed `EXISTS` on `idx_contact_blocks_target` (`viewerTokenIfTargeted`); when no row, active or
released, targets the viewer they bind the empty token, with which every per-row subquery is skipped
(`v.token <> ''`) or matches nothing in an index probe. Grid, Swipe, Tonight, Favorites, Profile,
Psync and notifications load the viewer's hidden set once per request (one HMAC + one indexed range
scan, empty for nearly everyone) and filter in Kotlin; per row that is a set lookup.

### Linked couples

A's block hides A, not A's partner P (P can add the address too). A's face never appears on P's card
for X: the partner mini and couple badge come off, as for a partner whose visibility is hidden.

### Deletion and export

Blocker deleted: all their rows, tombstones included, are deleted. Target deleted: active rows pointing
at them go back to pending (they bind again only if the address re-registers and is verified),
tombstones pointing at them are deleted, and their contact token is cleared. A's export shows only
`created_at` per address (`hideFromPeopleYouKnow`). X's export leaves out what A caused. The token
parameter of the deletion and export classes is required, so neither can be built without the keys.

### What it cannot do, and what stays inferable

- It can't undo what X has already seen (X may remember a view). It does not hide A from anyone who has
  already reached out to A (liked, messaged, matched, viewed, requested), from a different address or a
  second account; it does not hide A's partner or X from A; it never applies to accounts without a
  confirmed email.
- **A throwaway comparison:** a contact who compares a real account with a throwaway can learn that A
  knows their address. Unread is ambiguous, not proof: A's message stays `sent`, like one nobody
  opened; a like that never matches and an invite never answered look the same.
- **Removal:** X starts seeing A again from the removal; nothing older surfaces, so the moment of removal
  lines up with nothing X can see except A's reappearance in discovery.
- **X-side signals:** X's verify or sign-in does a few more indexed writes when someone added X's
  address (timing). In a group with a hidden A, X's sort time falls back to their last visible message
  when A caused the last bump, losing earlier non-message bumps by others; a timer notice by nobody
  says someone X can't see changed the timer, and the effective timer is still the strictest of all
  active participants, A included. In a Psync session X's deck is one card short of the requested size
  per unanswered hidden card.
- **A-side residuals:** for a push or realtime hint A causes, the skipped write or emit for X is one
  insert or one in-memory emit, the same as for a recipient with no device or the kind turned off. A's
  acts in the 5–30 s before a bind reach X and then vanish.
- **Contacts picker:** not built (needs platform code per shell). Typing is the only way in.

## Transparency Policy

Use selective transparency.

For low/medium risk:

- Friction can be subtle.
- Avoid telling attackers exact triggers.

For likely real users:

- Show generic action limits.
- Offer verification.
- Offer support.

For bans:

- Provide a clear account notice unless doing so would create legal/safety risk.

Do not expose:

- Exact risk score.
- Exact detection thresholds.
- Device fingerprint details.
- Moderator-only signals.

## Implementation Notes

- Ktor stays authoritative; clients never make risk decisions. Risk enforcement lives in the domain
  services (routes stay thin): outbound social mutations consult
  `RiskPolicyService.isSocialActionRestricted` (quarantine/terminate → `RISK_RESTRICTED`, 403).
- Redis holds rate-limit counters and short-window risk counters only. Authoritative risk state, risk
  events, appeals, bans, device-link hashes and the audit trail live in Postgres. Never keep risk
  state only in Redis.
- Device ids are hashed server-side (SHA-256) before storage (`account_device_links`); raw device ids
  are never stored.
- Admin queue actions are specified in [ADMIN_MODERATION_CONSOLE_SPEC.md](ADMIN_MODERATION_CONSOLE_SPEC.md).

## Testing Requirements

Mass first-message bots are throttled; duplicate-message spam enters review; album-request spam is
blocked; swipe automation and profile scraping are rate-limited; quarantined users are hidden from
discovery; blocked users disappear from Activity; a false-positive verified user recovers; partner
subscription cycling triggers a cooldown; a device/account-farm signal raises risk; a severe trigger
locks the account immediately; an appeal can restore an account. Risk checks must not materially slow
clean users, and the review queue must never delay clean messages.

## Metrics

Track:

- Risk tier distribution
- New accounts entering Watch/Friction/Quarantine
- Hard bans by reason
- False-positive recovery rate
- Appeal success rate
- Held messages released/deleted
- Spam reports per 1,000 active users
- Album request abuse rate
- Message reply rate by risk tier
- Rate-limit hits
- Account-farm detections
- Partner subscription abuse cases

Important:

- High false-positive rate means the system is too aggressive.
- High spam reports means the system is too weak.

## Implementation Checklist

- [x] Risk score model exists.
- [x] Risk event table exists.
- [x] Redis-backed rate limits exist.
- [x] Risk enforcement across the Ktor surface.
- [x] Tier policies exist.
- [x] Domain risk gates restrict quarantined/terminated social actions.
- [x] Quarantined/terminated accounts are suppressed from discovery-facing surfaces.
- [x] Message duplicate detection exists.
- [x] Message review queue exists.
- [x] Held first-message review supports audited release/remove actions.
- [x] Reports queue supports audited triage actions.
- [x] Severe media scanner decisions create system safety reports and media-moderation risk events.
- [x] Photo pre-screening (2026-11-25): Cloud Vision SafeSearch on by default in staging/prod, approve-or-hold only for profile photos (ceilings adult ≤ UNLIKELY, violence ≤ POSSIBLE, racy ≤ LIKELY, configurable), fail closed on error/timeout/unknown, production refuses manual without `PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION`, owner-only "Being reviewed" label; Vision sees profile photos and selfies only unless `PYNAPLI_MEDIA_MODERATION_SCAN_PRIVATE_MEDIA=true`. Tested with Vision faked. Open: the founder's live run with real images and any threshold tuning (PATH_TO_TESTERS A9).
- [x] Media reports support audited target-media removal.
- [x] Risk review queue supports audited clear/set-tier/review actions.
- [x] Appeal API supports user submission/status/withdraw and audited admin decisions.
- [x] Album request throttles exist.
- [x] Swipe velocity detection exists.
- [x] Profile view velocity detection exists.
- [x] Device/account-farm hash signals exist.
- [x] Reports can't be weaponised into automatic enforcement (`ReportScoringPolicy`): a reporter counts toward a target's risk score once per reason per 30 days (repeats are stored and accepted at zero weight); one distinct reporter caps the report-driven score at 69 (below quarantine), two or more at 89; reports alone never reach terminate, which needs a moderator.
- [x] Reporting someone takes them out of the reporter's discovery.
- [x] Travel mode abuse controls exist. Every tier is capped at 6 Travel mode destination switches per rolling 24 hours.
- [x] Location spoofing signals exist.
- [x] Partner subscription abuse cooldown exists.
- [x] Selfie photo verification: server pose challenge, private selfie, optional face-count check, senior-admin decision with audit, revoke, rate limits and selfie retention.
- [x] Verification lowers risk where appropriate: a verified age-verification outcome (admin review or provider callback) publishes a -20 risk event; an approved photo verification publishes a -20 risk event once per account; the account score stays floored at 0 and never drops below an admin-set score (`admin_floor_score`).
- [x] Risk score decay: rate-limit points decay 1 per day (lazily, ledgered as `decay.minor`); severe, report, other signal and admin-set points never decay.
- [ ] Partial: appeal review flow, admin roles and step-up MFA exist; support review workflow, account notices and senior-review separation remain.
- [x] Moderator dashboard requirements are tracked.
- [x] False-positive metrics exist.
- [x] Severe hard-ban triggers exist.
- [x] Fake/deceptive degradation is not implemented.
- [x] "Met in person": private confirmations stored only as K3 tokens, a weekly randomised batch with per-subject hysteresis, independence and subject-proof exclusions, opt-in person and couple badges, admin view and actions with undo (see "Met in person").

## Do Not Implement

- Do not immediately ban every suspected bot.
- Do not let suspected spam reach real users while waiting for review.
- Do not make the whole app randomly unusable for borderline users.
- Do not fake payment success.
- Do not send fake push notifications.
- Do not create fake matches.
- Do not use fake user pools as if they are real.
- Do not expose exact risk scores to users.
- Do not hard-ban solely from one weak signal.
- Do not store raw device identifiers unnecessarily.
- Do not trust client-side risk decisions.
- Do not block safety actions due to risk tier.
- Do not build payment flows that appear to work but are not processed, or any deceptive commerce behavior.
