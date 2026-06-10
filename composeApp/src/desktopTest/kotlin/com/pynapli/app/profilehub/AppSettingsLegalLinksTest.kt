package com.pynapli.app.profilehub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.pynapli.app.appsettings.AppSettingsUiState
import com.pynapli.app.design.PynapliTheme
import com.pynapli.shared.core.config.LegalLinks
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsLegalLinksTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun legalRowsOpenTheCanonicalDocumentUrls() =
        runComposeUiTest {
            val openedUris = mutableListOf<String>()
            val recordingUriHandler =
                object : UriHandler {
                    override fun openUri(uri: String) {
                        openedUris += uri
                    }
                }

            setContent {
                PynapliTheme(themeMode = "dark") {
                    CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                        Box(modifier = Modifier.size(width = 420.dp, height = 760.dp)) {
                            AppSettingsContent(
                                state = AppSettingsUiState(),
                                onBack = {},
                                onThemeModeChanged = {},
                                onDistanceUnitChanged = {},
                                onDiscreetIconChanged = {},
                                onNotificationDetailChanged = {},
                                onMessageNotificationsChanged = {},
                                onMatchNotificationsChanged = {},
                                onActivityNotificationsChanged = {},
                                onFlirtNotificationsChanged = {},
                                onAlbumRequestNotificationsChanged = {},
                                onPartnerInviteNotificationsChanged = {},
                                onSystemSafetyNotificationsChanged = {},
                                onQuietHoursEnabledChanged = {},
                                onQuietHoursWindowChanged = { _, _ -> },
                                onClearError = {},
                                onSave = {},
                            )
                        }
                    }
                }
            }

            onNodeWithTag(AppSettingsTestTags.LIST).performScrollToNode(hasText("Community Guidelines"))
            onNodeWithText("Legal").assertExists()
            onNodeWithText("Terms of Service").performClick()
            onNodeWithText("Privacy Policy").performClick()
            onNodeWithText("Community Guidelines").performClick()

            assertEquals(
                listOf(
                    LegalLinks.TERMS_OF_SERVICE,
                    LegalLinks.PRIVACY_POLICY,
                    LegalLinks.COMMUNITY_GUIDELINES,
                ),
                openedUris,
            )
        }
}
