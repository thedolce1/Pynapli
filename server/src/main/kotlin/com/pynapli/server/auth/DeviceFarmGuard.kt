package com.pynapli.server.auth

import com.pynapli.server.auth.security.Sha256
import com.pynapli.server.safety.DeviceRiskSignalPublisher
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Records each signup's device -> account link and raises an account-farm risk signal when one
 * device hash is behind an unusual number of distinct accounts within the window
 * (TRUST_AND_SAFETY_SPEC). Signal-only: a ledger/risk failure must never block account creation,
 * and the new account is always created regardless.
 *
 * The client device id is hashed server-side before storage, so the raw identifier is never
 * persisted (mirrors hashed email/token storage). When no repository is wired (e.g. tests without
 * a database) or the request carries no device id, this is a no-op.
 */
internal class DeviceFarmGuard(
    private val repository: DeviceFarmRepository?,
    private val riskSignalPublisher: DeviceRiskSignalPublisher?,
    private val clock: Clock,
    private val accountThreshold: Int = DEFAULT_ACCOUNT_THRESHOLD,
    private val window: Duration = DEFAULT_WINDOW,
) {
    suspend fun recordSignup(
        userId: UUID,
        deviceId: String?,
    ) {
        val repo = repository ?: return
        val rawDeviceId = deviceId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            val deviceHash = Sha256.hash(rawDeviceId)
            val now = clock.instant()
            repo.linkDeviceToAccount(deviceHash, userId, now)
            val distinctAccounts = repo.countDistinctAccountsForDevice(deviceHash, now.minus(window))
            if (distinctAccounts >= accountThreshold) {
                riskSignalPublisher?.recordDeviceAccountFarm(userId, distinctAccounts, window.toHours())
            }
        }
    }

    companion object {
        const val DEFAULT_ACCOUNT_THRESHOLD = 4
        val DEFAULT_WINDOW: Duration = Duration.ofHours(24)
    }
}
