package com.pynapli.server.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the platform-agnostic push-delivery decisions ([PushDeliveryConfig]) that drive the
 * per-platform FCM APNs/Android configs. These rules are what make the server APNs/Android-ready;
 * the native iOS shell and a live APNs round-trip are separate macOS/Xcode steps. The Firebase
 * config objects themselves are write-only builders, so the testable logic lives in PushDeliveryConfig
 * and the *Config builders are a thin translation that also compiles here.
 */
class FirebaseMessageConfigTest {
    @Test
    fun highUrgencyKindsResolveToImmediateDelivery() {
        listOf(
            NotificationKind.SYSTEM_SAFETY,
            NotificationKind.MESSAGE,
            NotificationKind.MATCH,
            NotificationKind.PARTNER_CHAT_INVITE,
        ).forEach { kind ->
            val config = PushDeliveryConfig.forKind(kind)
            assertTrue(config.highUrgency, "$kind should be high urgency")
            assertEquals("10", config.apnsPriority)
        }
    }

    @Test
    fun lowUrgencyKindsResolveToThrottledDelivery() {
        listOf(NotificationKind.FLIRT, NotificationKind.PROFILE_VIEW, NotificationKind.ALBUM_REQUEST).forEach { kind ->
            val config = PushDeliveryConfig.forKind(kind)
            assertFalse(config.highUrgency, "$kind should not be high urgency")
            assertEquals("5", config.apnsPriority)
        }
    }

    @Test
    fun collapseKeyIsTheKindAndFallsBackForBlank() {
        assertEquals(NotificationKind.MESSAGE, PushDeliveryConfig.forKind(NotificationKind.MESSAGE).collapseKey)
        assertEquals("notification", PushDeliveryConfig.forKind("").collapseKey)
    }

    @Test
    fun collapseKeyIsCappedAtApnsLimit() {
        assertTrue(PushDeliveryConfig.forKind("a".repeat(200)).collapseKey.length <= 64)
    }

    @Test
    fun configBuildersConstructWithoutFirebaseInit() {
        // Smoke check: the builders translate without throwing (no Firebase app needed).
        apnsConfigFor(NotificationKind.MESSAGE)
        androidConfigFor(NotificationKind.FLIRT)
    }
}
