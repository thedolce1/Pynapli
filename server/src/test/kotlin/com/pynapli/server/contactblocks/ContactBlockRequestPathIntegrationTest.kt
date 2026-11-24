package com.pynapli.server.contactblocks

import com.pynapli.server.grid.insertDiscoverableUser
import com.pynapli.server.testsupport.PynapliTestDatabase
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Collections
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Records every SQL statement the blocker's own requests actually execute (a DataSource whose
 * connections log each prepared or created statement), against the real database, in two worlds:
 * the added address belongs to a verified member with a contact token, or to nobody. The sequences
 * must be identical, none may read `users`, and none may be the resolver's.
 */
class ContactBlockRequestPathIntegrationTest :
    StringSpec({
        val dataSource = PynapliTestDatabase.dataSource

        beforeTest { PynapliTestDatabase.reset() }

        "add, count, remove and remove-all run the same statements whether or not the address is a member's, and never read users" {
            runBlocking<Unit> {
                fun world(addressIsMember: Boolean): List<String> {
                    PynapliTestDatabase.reset()
                    return runBlocking {
                        val graph = ContactBlockTestGraph(dataSource)
                        dataSource.insertDiscoverableUser(BLOCKER, "Alex", age = 34, longitude = -122.08, latitude = 37.42)
                        dataSource.insertDiscoverableUser(MEMBER, "Sam", age = 41, longitude = -122.081, latitude = 37.421)
                        graph.verifyAddress(MEMBER, if (addressIsMember) ADDRESS else "someone.else@example.com")
                        val recorded = Collections.synchronizedList(mutableListOf<String>())
                        val service = ContactBlockService(JdbcContactBlockRepository(recording(dataSource, recorded)), graph.tokens, graph.trigger)

                        service.add(BLOCKER, ADDRESS)
                        // Bound in the member world, still pending in the other: the remove below must look the same.
                        graph.resolver.resolveRecent()
                        service.summary(BLOCKER)
                        service.add(BLOCKER, ADDRESS)
                        service.remove(BLOCKER, ADDRESS)
                        service.add(BLOCKER, ADDRESS)
                        service.removeAll(BLOCKER)
                        service.summary(BLOCKER)
                        recorded.toList()
                    }
                }

                val member = world(addressIsMember = true)
                val nobody = world(addressIsMember = false)

                assertEquals(nobody, member, "the blocker's requests must not depend on membership")
                assertTrue(member.isNotEmpty())
                member.forEach { sql ->
                    assertTrue(!Regex("""\busers\b""").containsMatchIn(sql), "request-path SQL reads users: $sql")
                    assertTrue("contact_token" !in sql && "member:" !in sql, "request-path SQL resolves: $sql")
                }
            }
        }
    })

private const val ADDRESS = "boss@example.com"
private val BLOCKER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c3a1")
private val MEMBER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c3a2")

/** A DataSource whose connections record the SQL of every statement they prepare or create. */
private fun recording(
    real: DataSource,
    recorded: MutableList<String>,
): DataSource =
    Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
        val result = invokeUnwrapped(real, method, args)
        if (method.name == "getConnection" && result is Connection) recordingConnection(result, recorded) else result
    } as DataSource

private fun recordingConnection(
    real: Connection,
    recorded: MutableList<String>,
): Connection =
    Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
        if (method.name in setOf("prepareStatement", "prepareCall") && args?.firstOrNull() is String) {
            recorded += args!![0] as String
        }
        if (method.name == "createStatement") recorded += "<createStatement>"
        invokeUnwrapped(real, method, args)
    } as Connection

private fun invokeUnwrapped(
    target: Any,
    method: java.lang.reflect.Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (error: InvocationTargetException) {
        throw error.targetException
    }
