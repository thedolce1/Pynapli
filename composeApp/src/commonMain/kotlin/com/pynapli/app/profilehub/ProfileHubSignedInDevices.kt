package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliBottomSheet
import com.pynapli.app.design.PynapliSheetColumn
import com.pynapli.app.design.PynapliSheetTitle
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Security
import com.pynapli.app.design.icons.filled.Smartphone
import com.pynapli.shared.network.dto.SignedInDeviceDto
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Lists the account's live sessions; any other one can be signed out, or all of them at once. */
@Composable
internal fun SignedInDevicesSheet(
    state: ProfileHubUiState,
    onDismiss: () -> Unit,
    onSignOut: (String) -> Unit,
    onSignOutOthers: () -> Unit,
) {
    val devices = state.signedInDevices
    val others = devices.orEmpty().filterNot { it.current }
    PynapliBottomSheet(onDismiss = onDismiss) {
        PynapliSheetColumn {
            PynapliSheetTitle("Where you're signed in")
            Text(
                text = "If you don't recognise a device, sign it out and change your password.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                devices == null && state.isLoadingDevices ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))

                devices == null -> Unit

                else -> {
                    val now = nowMillis()
                    devices.forEach { device ->
                        SignedInDeviceRow(
                            device = device,
                            activity = deviceActivityLabel(device, now),
                            signingOut = device.sessionId in state.signingOutDeviceIds,
                            onSignOut = { onSignOut(device.sessionId) },
                        )
                    }
                }
            }
            (state.errorMessage ?: state.feedbackMessage)?.let { message ->
                Text(
                    text = message,
                    color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (others.isNotEmpty()) {
                val busy = others.any { it.sessionId in state.signingOutDeviceIds }
                OutlinedButton(
                    onClick = onSignOutOthers,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().handCursor(enabled = !busy),
                ) {
                    Text(if (others.size == 1) "Sign out the other device" else "Sign out all ${others.size} other devices")
                }
            }
        }
    }
}

@Composable
private fun SignedInDeviceRow(
    device: SignedInDeviceDto,
    activity: String,
    signingOut: Boolean,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (device.platform == "android" || device.platform == "ios") Icons.Filled.Smartphone else Icons.Filled.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deviceName(device.platform),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = activity,
                color = if (device.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!device.current) {
            TextButton(
                onClick = onSignOut,
                enabled = !signingOut,
                modifier = Modifier.handCursor(enabled = !signingOut),
            ) {
                Text(if (signingOut) "Signing out" else "Sign out")
            }
        }
    }
}

internal fun deviceName(platform: String?): String =
    when (platform) {
        "android" -> "Android phone"
        "ios" -> "iPhone"
        "web" -> "Web browser"
        "desktop" -> "Computer"
        else -> "Unknown device"
    }

/** "This device", or how long ago the session was last active ("Active 3 hours ago"). */
@OptIn(ExperimentalTime::class)
internal fun deviceActivityLabel(
    device: SignedInDeviceDto,
    nowMillis: Long,
): String {
    if (device.current) return "This device"
    val lastActive = runCatching { Instant.parse(device.lastActiveAt).toEpochMilliseconds() }.getOrNull() ?: return "Signed in"
    val minutes = ((nowMillis - lastActive) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 60 -> "Active in the last hour"
        minutes < 24 * 60 -> (minutes / 60).let { hours -> if (hours == 1L) "Active 1 hour ago" else "Active $hours hours ago" }
        else -> (minutes / (24 * 60)).let { days -> if (days == 1L) "Active yesterday" else "Active $days days ago" }
    }
}

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
