package com.pynapli.server.testsupport

import com.pynapli.server.config.ServerConfig

/**
 * The base [ServerConfig] for tests that don't talk to real infrastructure: the required connection
 * fields point at local defaults that are never dialed. Override what a test cares about with
 * `testServerConfig(environment = "local").copy(adminApiToken = ...)`.
 */
internal fun testServerConfig(environment: String = "test"): ServerConfig =
    ServerConfig(
        environment = environment,
        port = 8080,
        databaseUrl = "jdbc:postgresql://localhost:5432/pynapli_test",
        databaseUser = "pynapli",
        databasePassword = "pynapli_dev_password",
        redisUrl = "redis://localhost:6379",
    )
