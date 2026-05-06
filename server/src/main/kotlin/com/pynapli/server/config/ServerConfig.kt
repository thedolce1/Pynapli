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
                adminApiToken = System.getenv("PYNAPLI_ADMIN_API_TOKEN")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            )
        }
    }
}
