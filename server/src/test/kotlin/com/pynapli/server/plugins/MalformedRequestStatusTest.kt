package com.pynapli.server.plugins

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MalformedRequestStatusTest {
    @Serializable
    private data class Probe(
        val targetUserId: String,
    )

    private fun test(
        body: String,
        check: suspend (HttpStatusCode, String) -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                post("/probe") {
                    val probe = call.receive<Probe>()
                    call.respond(HttpStatusCode.OK, probe.targetUserId)
                }
            }
        }
        val response =
            client.post("/probe") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        check(response.status, response.bodyAsText())
    }

    @Test
    fun brokenJsonIsABadRequestNotAServerError() =
        test("{nope") { status, body ->
            assertEquals(HttpStatusCode.BadRequest, status)
            assertTrue(body.contains("INVALID_REQUEST"))
        }

    @Test
    fun aMissingRequiredFieldIsABadRequest() =
        test("{}") { status, body ->
            assertEquals(HttpStatusCode.BadRequest, status)
            assertTrue(body.contains("INVALID_REQUEST"))
        }

    @Test
    fun aValidBodyStillWorks() =
        test("""{"targetUserId":"abc"}""") { status, _ ->
            assertEquals(HttpStatusCode.OK, status)
        }
}
