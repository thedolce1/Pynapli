package com.pynapli.server.config

data class ServerConfig(
    val environment: String,
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val redisUrl: String,
    val mediaStorageProvider: String = "local",
    val mediaStorageBucket: String = "pynapli-local-media",
    val mediaUploadBaseUrl: String = "http://10.0.2.2:8080",
    val mediaLocalUploadDirectory: String = ".pynapli-local-media",
    val mediaDevAutoApproveUploads: Boolean = false,
    val notificationDispatchEnabled: Boolean = false,
    val notificationDispatchIntervalMillis: Long = 5_000L,
    val notificationDispatchBatchSize: Int = 50,
    val notificationMaxAttempts: Int = 5,
    val notificationPushProvider: String = "local",
    val firebaseServiceAccountPath: String? = null,
    val firebaseServiceAccountJson: String? = null,
    val playStorePackageName: String = "com.pynapli.app",
    val playStoreVerificationEnabled: Boolean = false,
    val playStoreServiceAccountPath: String? = null,
    val playStoreServiceAccountJson: String? = null,
    val playStoreDeveloperApiBaseUrl: String = "https://androidpublisher.googleapis.com",
    val playStoreSubscriptionSyncEnabled: Boolean = false,
    val playStoreSubscriptionSyncIntervalMillis: Long = 5 * 60 * 1_000L,
    val playStoreSubscriptionSyncBatchSize: Int = 100,
    val playStoreSubscriptionSyncStaleAfterHours: Long = 6,
    val playStoreSubscriptionSyncRenewalLookaheadHours: Long = 24,
    val adminApiToken: String? = null
) {
    companion object {
        fun fromEnvironment(): ServerConfig {
            val environment = System.getenv("PYNAPLI_ENV") ?: "local"
            val port = System.getenv("PYNAPLI_SERVER_PORT")?.toIntOrNull() ?: 8080
            val mediaStorageProvider = System.getenv("PYNAPLI_MEDIA_STORAGE_PROVIDER") ?: "local"
            val defaultUploadBaseUrl = if (mediaStorageProvider.equals("local", ignoreCase = true)) {
                "http://10.0.2.2:$port"
            } else {
                "https://storage.googleapis.com"
            }

            return ServerConfig(
                environment = environment,
                port = port,
                databaseUrl = System.getenv("PYNAPLI_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/pynapli_dev",
                databaseUser = System.getenv("PYNAPLI_DATABASE_USER") ?: "pynapli",
                databasePassword = System.getenv("PYNAPLI_DATABASE_PASSWORD") ?: "pynapli_dev_password",
                redisUrl = System.getenv("PYNAPLI_REDIS_URL") ?: "redis://localhost:6379",
                mediaStorageProvider = mediaStorageProvider,
                mediaStorageBucket = System.getenv("PYNAPLI_MEDIA_STORAGE_BUCKET") ?: "pynapli-local-media",
                mediaUploadBaseUrl = System.getenv("PYNAPLI_MEDIA_UPLOAD_BASE_URL") ?: defaultUploadBaseUrl,
                mediaLocalUploadDirectory = System.getenv("PYNAPLI_MEDIA_LOCAL_UPLOAD_DIR") ?: ".pynapli-local-media",
                mediaDevAutoApproveUploads = System.getenv("PYNAPLI_MEDIA_DEV_AUTO_APPROVE_UPLOADS")?.toBooleanStrictOrNull()
                    ?: (environment == "local"),
                notificationDispatchEnabled = System.getenv("PYNAPLI_NOTIFICATION_DISPATCH_ENABLED")?.toBooleanStrictOrNull()
                    ?: (environment == "local"),
                notificationDispatchIntervalMillis = System.getenv("PYNAPLI_NOTIFICATION_DISPATCH_INTERVAL_MS")
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1_000L)
                    ?: 5_000L,
                notificationDispatchBatchSize = System.getenv("PYNAPLI_NOTIFICATION_DISPATCH_BATCH_SIZE")
                    ?.toIntOrNull()
                    ?.coerceIn(1, 250)
                    ?: 50,
                notificationMaxAttempts = System.getenv("PYNAPLI_NOTIFICATION_MAX_ATTEMPTS")
                    ?.toIntOrNull()
                    ?.coerceIn(1, 20)
                    ?: 5,
                notificationPushProvider = System.getenv("PYNAPLI_NOTIFICATION_PUSH_PROVIDER")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "local",
                firebaseServiceAccountPath = System.getenv("PYNAPLI_FIREBASE_SERVICE_ACCOUNT_PATH")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                firebaseServiceAccountJson = System.getenv("PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                playStorePackageName = System.getenv("PYNAPLI_PLAY_STORE_PACKAGE_NAME")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "com.pynapli.app",
                playStoreVerificationEnabled = System.getenv("PYNAPLI_PLAY_STORE_VERIFICATION_ENABLED")
                    ?.toBooleanStrictOrNull()
                    ?: false,
                playStoreServiceAccountPath = System.getenv("PYNAPLI_PLAY_STORE_SERVICE_ACCOUNT_PATH")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                playStoreServiceAccountJson = System.getenv("PYNAPLI_PLAY_STORE_SERVICE_ACCOUNT_JSON")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                playStoreDeveloperApiBaseUrl = System.getenv("PYNAPLI_PLAY_STORE_DEVELOPER_API_BASE_URL")
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotEmpty() }
                    ?: "https://androidpublisher.googleapis.com",
                playStoreSubscriptionSyncEnabled = System.getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_ENABLED")
                    ?.toBooleanStrictOrNull()
                    ?: (System.getenv("PYNAPLI_PLAY_STORE_VERIFICATION_ENABLED")?.toBooleanStrictOrNull() ?: false),
                playStoreSubscriptionSyncIntervalMillis = System.getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_INTERVAL_MS")
                    ?.toLongOrNull()
                    ?.coerceAtLeast(60_000L)
                    ?: 5 * 60 * 1_000L,
                playStoreSubscriptionSyncBatchSize = System.getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_BATCH_SIZE")
                    ?.toIntOrNull()
                    ?.coerceIn(1, 500)
                    ?: 100,
                playStoreSubscriptionSyncStaleAfterHours = System.getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_STALE_AFTER_HOURS")
                    ?.toLongOrNull()
                    ?.coerceIn(1, 168)
                    ?: 6,
                playStoreSubscriptionSyncRenewalLookaheadHours = System.getenv("PYNAPLI_PLAY_STORE_SUBSCRIPTION_SYNC_RENEWAL_LOOKAHEAD_HOURS")
                    ?.toLongOrNull()
                    ?.coerceIn(1, 168)
                    ?: 24,
                adminApiToken = System.getenv("PYNAPLI_ADMIN_API_TOKEN")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            )
        }
    }
}
