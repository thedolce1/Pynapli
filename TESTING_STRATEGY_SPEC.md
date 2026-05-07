# Pynapli Testing Strategy Spec

Last updated: 2026-04-24

## Purpose

Testing is part of the product architecture. Grid speed, swipe feel, partner consent, safety, and billing must be verified continuously.

Core rule:

> If a critical path cannot be tested, it is not ready for users.

For production release gates, load targets, security matrices, and CI/CD quality gates, use [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md) alongside this document.

## Test Layers

- Unit tests: domain rules and pure logic.
- Integration tests: Ktor API with real database containers.
- E2E tests: mobile user journeys.
- Performance tests: grid, swipe, messages, location, battery.
- Security/abuse tests: bots, spam, blocks, moderation.

## Critical E2E Journeys

- Signup to first grid.
- Login to grid.
- Grid profile view and return to exact scroll position.
- Grid filter apply without blank reload.
- Grid message send/receive.
- Swipe like/pass/match/chat.
- Partner link request/accept/unlink.
- Partner invite to chat accept/deny/leave.
- Private album request/grant/revoke.
- Psync session start/complete/recap.
- Playing Tonight approval/live/expire.
- Subscription purchase/restore/linked partner entitlement.
- Block/report removes visibility.

## Performance Benchmarks

Targets:

- First network grid render: under 1.5s on normal mobile network.
- Grid filter apply: no blank grid; animation under 300ms after response.
- Swipe animation: 60fps target.
- Swipe action: no animation blocked by network.
- Message delivery online: target under 1-2s.
- Push notification: target under 30s.
- Profile update to discovery: within 60s.
- Ban/suspension removal: under 5s.

## Load Tests

Use k6 or equivalent for:

- Grid query.
- Swipe deck.
- Swipe action.
- Message send.
- Activity feed.
- Auth.
- Album grant/revoke.

Use PostgreSQL `EXPLAIN ANALYZE` for slow query regression.

## Security/Abuse Tests

- Mass first-message bot throttled.
- Duplicate-message spam held.
- Album request spam blocked.
- Swipe automation rate-limited.
- Scraping profile views throttled.
- Quarantined account hidden from discovery.
- Blocked users removed from Activity/Messages/Grid/Swipe.
- False-positive recovery through verification/support.
- Partner subscription cycling triggers cooldown.

## Release Checklist

- [ ] Critical E2E tests passing on Android.
- [ ] Critical E2E tests passing on iOS.
- [ ] Partial: Ktor/server integration tests with Testcontainers exist and pass locally; full release-grade DB container coverage is not complete.
- [ ] Performance benchmarks under thresholds.
- [ ] Partial: core abuse/risk tests exist and pass; release-grade abuse suite is not complete.
- [ ] Manual QA on mid-range Android and older supported iPhone.
- [ ] Location denied path tested.
- [ ] Partial: notification denied logic has unit coverage; manual device QA is not complete.
- [ ] Low-network path tested.

## Do Not Implement

- Do not release without tested block/report.
- Do not release without tested partner consent flows.
- Do not release if grid scroll state is lost after profile view.
- Do not release if subscription entitlement is client-trusted.
