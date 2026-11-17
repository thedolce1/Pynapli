package com.pynapli.server.safety

import com.pynapli.server.auth.AuthService
import com.pynapli.server.auth.JdbcAuthRepository
import com.pynapli.server.auth.security.PasswordHasher
import com.pynapli.server.testsupport.PynapliTestDatabase
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportTargetType
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AlbumReportIntegrationTest {
    private val dataSource = PynapliTestDatabase.dataSource
    private val auth = AuthService(JdbcAuthRepository(dataSource), passwordHasher = PasswordHasher(iterations = 1_000))
    private val reports =
        ReportService(
            repository = JdbcReportRepository(dataSource),
            evidenceLookup = JdbcReportEvidenceLookup(dataSource),
        )

    @BeforeTest
    fun reset() {
        PynapliTestDatabase.reset()
    }

    private suspend fun user(email: String) = UUID.fromString(auth.signupEmail(email, "report-password-1", "1990-01-01", true, true, true, null, "android", "1.0").user.userId)

    private fun execute(
        sql: String,
        vararg args: Any?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
    }

    private fun asset(owner: UUID): UUID {
        val id = UUID.randomUUID()
        execute(
            "INSERT INTO media_assets (id, owner_user_id, media_type, storage_provider, storage_bucket, storage_object_key, content_type, byte_size) " +
                "VALUES (?, ?, 'image', 'local', 'test', ?, 'image/jpeg', 10)",
            id,
            owner,
            "key-$id",
        )
        return id
    }

    /** A private album holding [photos] in order. */
    private fun privateAlbum(
        owner: UUID,
        vararg photos: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        execute("INSERT INTO media_albums (id, owner_user_id, kind, title) VALUES (?, ?, 'private', 'Private')", id, owner)
        photos.forEachIndexed { index, photo ->
            execute("INSERT INTO media_album_items (album_id, media_asset_id, owner_user_id, sort_order) VALUES (?, ?, ?, ?)", id, photo, owner, index)
        }
        return id
    }

    private fun grant(
        album: UUID,
        owner: UUID,
        grantee: UUID,
    ) = execute("INSERT INTO media_album_access_grants (album_id, owner_user_id, grantee_user_id, granted_by_user_id) VALUES (?, ?, ?, ?)", album, owner, grantee, owner)

    private fun evidenceOf(reportId: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT evidence_text FROM safety_reports WHERE id = ?").use {
                it.setObject(1, UUID.fromString(reportId))
                it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private suspend fun reportAlbum(
        reporter: UUID,
        reported: UUID,
        albumId: Any?,
    ) = reports.submitReport(
        reporterUserId = reporter,
        request =
            CreateReportRequestDto(
                reportedUserId = reported.toString(),
                targetType = ReportTargetType.ALBUM,
                targetId = albumId?.toString(),
                category = ReportCategory.NON_CONSENSUAL_CONTENT,
            ),
    )

    @Test
    fun reportingASharedAlbumRecordsWhichPhotosWereShown() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            val first = asset(them)
            val second = asset(them)
            val album = privateAlbum(them, first, second)
            grant(album, them, me)

            val created = reportAlbum(me, them, album)

            assertEquals("private album $album: $first, $second", evidenceOf(created.reportId))
        }

    @Test
    fun onlyAnAlbumTheyOwnAndSharedWithYouCanBeReported() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            val stranger = user("stranger@example.com")
            val notShared = privateAlbum(them, asset(them))
            val strangersAlbum = privateAlbum(stranger, asset(stranger))
            grant(strangersAlbum, stranger, me)

            listOf(notShared, strangersAlbum, UUID.randomUUID(), "not-a-uuid", null).forEach { target ->
                val error = assertFailsWith<SafetyException> { reportAlbum(me, them, target) }
                assertEquals("REPORT_TARGET_NOT_FOUND", error.code, "target $target")
            }
        }

    @Test
    fun aPhotoSentInChatIsKeptAsEvidenceByItsMediaId() =
        runBlocking<Unit> {
            val me = user("me@example.com")
            val them = user("them@example.com")
            val photo = asset(them)
            val chat = UUID.randomUUID()
            execute("INSERT INTO conversations (id, kind, origin, created_by_user_id) VALUES (?, 'direct', 'grid', ?)", chat, them)
            execute("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)", chat, me)
            execute("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)", chat, them)
            val message = UUID.randomUUID()
            execute("INSERT INTO messages (id, conversation_id, sender_user_id, body, media_asset_id) VALUES (?, ?, ?, '', ?)", message, chat, them, photo)

            val created =
                reports.submitReport(
                    reporterUserId = me,
                    request =
                        CreateReportRequestDto(
                            reportedUserId = them.toString(),
                            targetType = ReportTargetType.MESSAGE,
                            targetId = message.toString(),
                            category = ReportCategory.NON_CONSENSUAL_CONTENT,
                        ),
                )

            assertEquals("[photo $photo]", evidenceOf(created.reportId))
        }
}
