package com.pynapli.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Desktop twin of `BrowserLocalStorage`: named string blobs under a per-user app directory.
 *
 * Plain files, not SQLDelight, and deliberately so — the web shell already established that a
 * non-SQLDelight store is fine for a shell whose job is not offline-first, and adding a SQLDelight
 * JVM driver would pull a new dependency into the version catalog for a target that exists to
 * iterate on UI. `NoOpGridSnapshotStore` / `NoOpSwipeActionQueueStore` cover the caches the mobile
 * shells persist, exactly as they do on web.
 *
 * NOT a secure store. Values land in a normal file with no encryption, which is why
 * [DesktopSessionStore] is gated to local/dev config only — see the guard in `DesktopRuntimeConfig`.
 */
internal object DesktopStorage {
    private val root: Path =
        Paths
            .get(System.getProperty("user.home"), ".pynapli", "desktop")
            .also { runCatching { Files.createDirectories(it) } }

    private fun pathFor(key: String): Path = root.resolve("$key.json")

    fun get(key: String): String? =
        runCatching {
            val path = pathFor(key)
            if (Files.exists(path)) Files.readString(path) else null
        }.getOrNull()

    fun set(
        key: String,
        value: String,
    ) {
        runCatching { Files.writeString(pathFor(key), value) }
    }

    fun remove(key: String) {
        runCatching { Files.deleteIfExists(pathFor(key)) }
    }
}
