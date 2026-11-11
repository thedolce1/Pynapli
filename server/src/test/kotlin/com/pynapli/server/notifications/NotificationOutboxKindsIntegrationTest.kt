package com.pynapli.server.notifications

import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.assertNotNull

/**
 * Every kind the server can send must be storable in the outbox. The database CHECK constraint
 * drifted from NOTIFICATION_KINDS for months (V24 vs Playing Tonight, Psync and album-grant pushes),
 * and because rows are only written for users with a push device, nothing failed until a real phone
 * was registered. This inserts one row per kind against the real schema.
 */
class NotificationOutboxKindsIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest {
            PynapliTestDatabase.reset()
        }

        "the outbox accepts every notification kind the server sends" {
            runBlocking {
                val recipient = UUID.randomUUID()
                val actor = UUID.randomUUID()
                dataSource.connection.use { connection ->
                    connection.prepareStatement("INSERT INTO users (id, status) VALUES (?, 'active'), (?, 'active')").use { statement ->
                        statement.setObject(1, recipient)
                        statement.setObject(2, actor)
                        statement.executeUpdate()
                    }
                }
                val repository = JdbcNotificationRepository(dataSource)

                for (kind in NOTIFICATION_KINDS) {
                    val inserted =
                        repository.insertOutboxNotification(
                            notification =
                                NotificationOutboxInsert(
                                    recipientUserId = recipient,
                                    actorUserId = actor,
                                    kind = kind,
                                    subjectId = UUID.randomUUID(),
                                    idempotencyKey = "kind-check:$kind",
                                    throttleKey = null,
                                    title = "Pynapli",
                                    body = "You have a new update.",
                                    payloadJson = """{"kind":"$kind"}""",
                                ),
                            notificationIdFactory = UUID::randomUUID,
                        )
                    assertNotNull(inserted, "outbox refused kind '$kind'")
                }
            }
        }
    })
