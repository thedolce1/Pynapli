# Pynapli Mobile App Architecture Spec

Last updated: 2026-04-24

## Purpose

This document defines the Kotlin Multiplatform + Compose Multiplatform client architecture for Pynapli.

Core rule:

> Share product logic, networking, state, and models across platforms. Keep platform-specific code limited to OS capabilities, permissions, billing, media, notifications, and secure storage.

For concrete module/folder layout and version baselines, use [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md) alongside this document.

## Client Stack

Recommended:

- Kotlin Multiplatform
- Compose Multiplatform
- Ktor Client
- Kotlinx Serialization
- Coroutines
- StateFlow / SharedFlow
- SQLDelight or equivalent local persistence
- Coil 3 / Kamel for image loading
- Firebase SDKs where platform support is needed

Platform-specific:

- Android location, notifications, billing, secure storage
- iOS Core Location, APNs/FCM bridge, StoreKit, Keychain
- Camera/photo picker
- App icon/discreet icon support
- Biometric/app lock

## Module Structure

Recommended top-level modules:

```text
composeApp
androidApp
shared
server
iosApp
```

Recommended shared structure:

```text
shared/src/commonMain/kotlin
  core/
    config/
    errors/
    logging/
    time/
    validation/
  network/
    ApiClient.kt
    RealtimeClient.kt
    dto/
  auth/
  profile/
  grid/
  swipe/
  activity/
  messages/
  albums/
  partner/
  psync/
  playingTonight/
  subscriptions/
  settings/
  safety/
  notifications/
  design/
  storage/
  platform/
```

Feature packages should generally contain:

```text
data/
domain/
presentation/
```

For example:

```text
grid/
  data/GridRepository.kt
  domain/GridCard.kt
  presentation/GridViewModel.kt
  presentation/GridUiState.kt
```

## Architecture Pattern

Use a pragmatic layered architecture:

- UI: Compose screens/components
- Presentation: ViewModels/state holders
- Domain: use cases and business rules
- Data: repositories, API clients, local cache
- Platform: expect/actual OS integrations

Do not over-abstract early. Add use cases where they remove real complexity or protect important rules.

## State Management

Use:

- `StateFlow` for screen state.
- `SharedFlow` for one-off events.
- Immutable UI state objects.
- Stable IDs for Compose lists/grids.

Example:

```kotlin
data class GridUiState(
    val cards: List<GridCardUiModel> = emptyList(),
    val filters: GridFiltersUiModel = GridFiltersUiModel(),
    val isRefreshing: Boolean = false,
    val nextCursor: String? = null,
    val error: UiError? = null
)
```

Rules:

- Do not expose mutable collections to UI.
- Do not keep product-critical authorization logic only in ViewModels.
- Do not block animations on network calls.

## Navigation

Bottom tabs:

- Grid
- Swipe
- Activity
- Messages
- Store

Global destinations:

- Profile/settings hub
- Full profile
- Chat thread
- Private album
- Partner link flow
- Psync session
- Playing Tonight setup/session
- Subscription/paywall
- Safety/report

Navigation rules:

- Profile/settings opens from header avatar.
- Each bottom tab preserves its own back stack/state.
- Opening a full profile from Grid must not destroy Grid state.
- Deep links wait for auth if required.
- Stale/deleted deep links show neutral empty state.

## Screen State Preservation

Must preserve:

- Grid cards, filters, cursor, and `LazyGridState`
- Swipe deck session and current card
- Activity tab/filter/cursor
- Messages list scroll position
- Store/paywall state

Grid profile open/close:

- Return to exact same scroll position.
- Do not refetch/reorder unless user refreshes or state expires.

Swipe app background/foreground:

- Preserve current card and queued actions where local persistence exists.
- Reconcile with server on resume.

## Networking

Use Ktor Client in shared code.

Responsibilities:

- Auth headers
- Request IDs
- Idempotency keys
- JSON serialization
- Retry policy
- Error mapping
- Token refresh
- App version/platform headers

Rules:

- Do not retry non-idempotent mutations unless idempotency key exists.
- Do not log message bodies, private media URLs, exact location, or sensitive tags.
- All API errors map to typed client errors.

## Realtime

Use Ktor WebSocket client.

Realtime events:

- Presence deltas
- Messages
- Receipts
- Activity deltas
- Matches
- Partner link updates
- Partner chat invites
- Psync updates
- Playing Tonight updates
- Album grants
- Entitlement updates

Rules:

- WebSocket is a convenience channel, not sole authority.
- On reconnect, refetch canonical state for affected screens.
- Do not stream full grid reorders.
- Do not expose partner Psync swipes.

## Local Persistence

Use local storage for:

- Auth session metadata, securely where needed
- User settings cache
- Last grid card snapshot
- Swipe deck action queue, once durable persistence is implemented
- Message draft text
- Recent messages cache
- Feature flags
- Notification preferences

Do not persist:

- Raw verification data
- Private album media by default
- Exact location history
- Sensitive logs
- Full message history unencrypted

## Offline / Poor Network Behavior

Grid:

- Show last cached grid briefly if fresh enough.
- Mark stale state subtly.
- Do not allow stale profile actions that server may reject without handling.

Swipe:

- Optimistically animate card.
- Queue limited number of actions.
- Cap unsynced actions.
- Reconcile with Ktor.

Messages:

- Show sending state.
- Retry failed sends.
- Respect server rejection/held-for-review state.

Albums:

- Do not cache private full media by default.
- If network fails, show controlled retry.

## Feature Architecture

### Grid

Client requirements:

- `LazyVerticalGrid`
- Stable keys by user ID
- Retained `LazyGridState`
- Minimal card model
- Filter diff animations
- Thumbnail preloading

Do not:

- Fetch full profiles for every tile.
- Reset scroll after profile close.
- Clear grid during filter refresh if old data exists.

### Swipe

Client requirements:

- Render only top 2-3 cards.
- Use deck session from server.
- Optimistic action queue.
- Physics-based gestures.
- Image preloading.

Do not:

- Render entire deck.
- Block card fly-out on network.
- Allow unlimited offline swipes.

### Activity

Client requirements:

- Newest-to-oldest list.
- Tabs.
- Gated rows.
- Activity badge handling.
- Pull-to-refresh.

Do not:

- Mix Activity into Messages.
- Count every view as nav badge noise.

### Messages

Client requirements:

- Thread list.
- Thread screen.
- Optimistic send.
- Media reveal.
- Partner invite UI.
- Block/report actions.

Do not:

- Silently add linked partner.
- Auto-share albums.

### Psync

Client requirements:

- Invite/review setup.
- Partner filter suggestion.
- Same ordered deck.
- Private swipes.
- Pause/leave/resume.
- Session recap.

Do not:

- Display partner's live swipes.
- Display Psync Score.

### Playing Tonight

Client requirements:

- Solo/together mode.
- Partner approval flow.
- Countdown.
- Zone/list display.
- Session end.

Do not:

- Expose precise location.
- Auto-share all interactions with partner.

## Platform Services Boundary

Use `expect/actual` or interface injection for:

- Location provider
- Notification permission and token
- Secure storage
- Billing
- Camera/photo picker
- Biometric/app lock
- App icon/discreet mode
- Deep links
- Haptics

Example:

```kotlin
interface SecureTokenStore {
    suspend fun saveTokens(tokens: AuthTokens)
    suspend fun getTokens(): AuthTokens?
    suspend fun clear()
}
```

## Firebase Usage On Client

Use Firebase client SDKs for:

- FCM token registration
- Crashlytics
- Analytics
- Remote Config
- App Check where applicable

Rules:

- Do not send sensitive lifestyle tags to Analytics.
- Do not log messages/private album URLs/exact location in Crashlytics.
- Remote Config can change UI defaults but not bypass server limits.

## Billing

Platform billing:

- Android: Google Play Billing
- iOS: StoreKit

Rules:

- Client starts purchase.
- Client sends purchase token/receipt to Ktor.
- Ktor validates and grants entitlement.
- Client updates from Ktor entitlement, not local purchase state.
- Restore purchases calls Ktor after platform receipt/token fetch.

## Permissions

Location:

- Ask after value explanation.
- Default while-in-use.
- If denied, show empty state and settings link.

Notifications:

- Ask after value explanation.
- If denied, app still works.

Photos/camera:

- Ask just-in-time.

Biometric/app lock:

- User-controlled.

## Error Handling

User-facing errors should be:

- Specific enough to act.
- Not revealing security thresholds.
- Neutral in tone.

Examples:

- "This action is temporarily limited. Try again later or verify your profile."
- "That profile is no longer available."
- "Album access has expired."
- "Your session ended."

## Security On Device

Requirements:

- Store tokens in Keychain/Keystore.
- Clear tokens on logout.
- Avoid screenshots on sensitive screens where platform allows.
- Do not cache private album full media by default.
- Redact logs.
- Avoid crash breadcrumbs with sensitive data.
- Support remote session revocation.

## Testing

Client test coverage:

- ViewModel state transitions.
- Repository API mapping.
- Grid scroll preservation.
- Swipe action queue.
- WebSocket reconnect handling.
- Partner invite flows.
- Permission denied states.
- Entitlement refresh.

Manual/device:

- Mid-range Android.
- Current and older supported iPhone.
- Low network.
- Location denied.
- Notification denied.
- Dark/light mode.
- Large text.

## Implementation Checklist

- [ ] Partial: shared module has domain/network packages, but not every feature is factored into shared packages.
- [x] Ktor Client configured in shared code.
- [ ] WebSocket client reconnects and refetches.
- [ ] Partial: session storage exists, but secure platform token storage expect/actual is not complete.
- [ ] Partial: location update flow exists, but platform expect/actual location provider abstraction is not complete.
- [ ] Partial: Android Play Billing bridge exists; iOS StoreKit bridge is missing.
- [ ] Partial: Grid preserves scroll state while the screen instance is alive; navigation/lifecycle regression coverage is still needed.
- [ ] Partial: Swipe action queue is capped in memory; durable local persistence across app process death is not implemented.
- [x] Activity and Messages are separate.
- [x] Partner invite consent flow implemented.
- [ ] Firebase analytics excludes sensitive data.
- [x] Entitlements come from Ktor.

## Do Not Implement

- Do not put product authorization in the client only.
- Do not trust local purchase state.
- Do not log sensitive data.
- Do not cache private full media by default.
- Do not destroy Grid state when opening profiles.
- Do not let Remote Config override server-enforced limits.
- Do not make platform-specific forks of product logic unless unavoidable.
