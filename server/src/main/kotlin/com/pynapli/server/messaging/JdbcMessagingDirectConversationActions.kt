package com.pynapli.server.messaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

internal suspend fun DataSource.getOrCreateDirectMessagingConversation(
    viewerUserId: UUID,
    targetUserId: UUID,
    origin: String,
    conversationIdFactory: () -> UUID,
): ConversationRecord? =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                if (!connection.targetAllowsConversation(viewerUserId, targetUserId, origin)) {
                    connection.rollback()
                    return@withContext null
                }

                val existingId = connection.findDirectConversationId(viewerUserId, targetUserId)
                val conversationId =
                    if (existingId != null) {
                        existingId
                    } else {
                        val newId = conversationIdFactory()
                        try {
                            connection.insertDirectConversation(
                                conversationId = newId,
                                viewerUserId = viewerUserId,
                                targetUserId = targetUserId,
                                origin = origin,
                            )
                            newId
                        } catch (error: SQLException) {
                            // Concurrent duplicate create lost the pair unique-index race. A new
                            // connection is needed because this one is in a failed transaction.
                            if (error.sqlState == JdbcMessagingRepositoryDefaults.UniqueViolationSqlState) {
                                connection.rollback()
                                return@withContext this@getOrCreateDirectMessagingConversation.connection.use { lookup ->
                                    lookup.findDirectConversationId(viewerUserId, targetUserId)?.let { racedId ->
                                        lookup.loadConversation(viewerUserId, racedId)
                                    }
                                }
                            }
                            throw error
                        }
                    }

                connection.commit()
                connection.loadConversation(viewerUserId, conversationId)
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }
