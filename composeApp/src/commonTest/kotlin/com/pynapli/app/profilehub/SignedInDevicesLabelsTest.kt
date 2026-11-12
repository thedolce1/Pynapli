package com.pynapli.app.profilehub

import com.pynapli.shared.network.dto.SignedInDeviceDto
import kotlin.test.Test
import kotlin.test.assertEquals

class SignedInDevicesLabelsTest {
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    private fun device(
        lastActiveAt: String,
        current: Boolean = false,
    ) = SignedInDeviceDto(sessionId = "s", platform = "web", signedInAt = lastActiveAt, lastActiveAt = lastActiveAt, current = current)

    @Test
    fun devicesAreNamedForPeople() {
        assertEquals("Android phone", deviceName("android"))
        assertEquals("iPhone", deviceName("ios"))
        assertEquals("Web browser", deviceName("web"))
        assertEquals("Computer", deviceName("desktop"))
        assertEquals("Unknown device", deviceName(null))
    }

    @Test
    fun activityReadsAsHowLongAgo() {
        assertEquals("This device", deviceActivityLabel(device("2027-01-15T07:00:00Z", current = true), now))
        assertEquals("Active in the last hour", deviceActivityLabel(device("2027-01-15T07:30:00Z"), now))
        assertEquals("Active 1 hour ago", deviceActivityLabel(device("2027-01-15T06:30:00Z"), now))
        assertEquals("Active 5 hours ago", deviceActivityLabel(device("2027-01-15T03:00:00Z"), now))
        assertEquals("Active yesterday", deviceActivityLabel(device("2027-01-14T07:00:00Z"), now))
        assertEquals("Active 9 days ago", deviceActivityLabel(device("2027-01-06T07:00:00Z"), now))
        assertEquals("Signed in", deviceActivityLabel(device("not a time"), now))
    }
}
