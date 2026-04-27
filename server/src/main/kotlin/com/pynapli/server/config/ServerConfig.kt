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
    val mediaDevAutoApproveUploads: Boolean = false
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
                    ?: (environment == "local")
            )
        }
    }
}
