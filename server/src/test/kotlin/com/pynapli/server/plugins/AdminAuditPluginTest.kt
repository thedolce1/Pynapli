package com.pynapli.server.plugins

import com.pynapli.server.admin.AdminRequestAuditRecorder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals

class AdminAuditPluginTest {
    @Test
    fun recordsSuccessfulAdminMutation() =
        testApplication {
            val recorded = Channel<RecordedRequest>(Channel.UNLIMITED)
            application {
                configureAdminAudit(FakeRecorder(recorded))
                routing {
                    post("/v1/admin/things") { call.respond(HttpStatusCode.OK, "ok") }
                }
            }

            client.post("/v1/admin/things") { header("X-Admin-Id", "ops-1") }

            val entry = withTimeout(3000) { recorded.receive() }
            assertEquals("ops-1", entry.actorId)
            assertEquals("POST", entry.method)
            assertEquals("/v1/admin/things", entry.path)
            assertEquals(200, entry.status)
        }

    @Test
    fun skipsMutationWithoutAdminActor() =
        testApplication {
            val recorded = Channel<RecordedRequest>(Channel.UNLIMITED)
            application {
                configureAdminAudit(FakeRecorder(recorded))
                routing {
                    post("/v1/admin/things") { call.respond(HttpStatusCode.OK, "ok") }
                }
            }

            // The first call has no actor and must be skipped; the second is the control that records.
            client.post("/v1/admin/things")
            client.post("/v1/admin/things") { header("X-Admin-Id", "control") }

            val entry = withTimeout(3000) { recorded.receive() }
            assertEquals("control", entry.actorId)
        }
}

private data class RecordedRequest(
    val actorId: String,
    val method: String,
    val path: String,
    val status: Int,
)

private class FakeRecorder(
    private val sink: Channel<RecordedRequest>,
) : AdminRequestAuditRecorder {
    override suspend fun record(
        actorId: String,
        method: String,
        path: String,
        status: Int,
    ) {
        sink.send(RecordedRequest(actorId, method, path, status))
    }
}
