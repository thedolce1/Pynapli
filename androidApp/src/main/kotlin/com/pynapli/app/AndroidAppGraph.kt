package com.pynapli.app

import android.content.Context
import com.pynapli.app.activity.ActivityStateHolder
import com.pynapli.app.activity.ApiActivityFeedRepository
import com.pynapli.app.auth.AuthStateHolder
import com.pynapli.app.auth.DevLoginCredentials
import com.pynapli.app.conversation.ApiConversationRepository
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.grid.ApiGridFeedRepository
import com.pynapli.app.grid.GridStateHolder
import com.pynapli.app.location.ApiLocationUpdateRepository
import com.pynapli.app.location.LocationStateHolder
import com.pynapli.app.media.ApiMediaAlbumsRepository
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.messages.ApiMessagesRepository
import com.pynapli.app.messages.MessagesStateHolder
import com.pynapli.app.profile.ApiPublicProfileRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.shared.auth.AuthRepository
import com.pynapli.shared.auth.SqlDelightSessionStore
import com.pynapli.shared.core.config.PynapliConfigs
import com.pynapli.shared.network.KtorMediaByteUploader
import com.pynapli.shared.network.KtorPynapliApiClient
import com.pynapli.shared.storage.AndroidPynapliDatabaseFactory

class AndroidAppGraph(context: Context) {
    private val database = AndroidPynapliDatabaseFactory(context.applicationContext).createDatabase()
    private val sessionStore = SqlDelightSessionStore(database)
    private val apiClient = KtorPynapliApiClient(
        config = PynapliConfigs.LocalAndroidEmulator,
        accessTokenProvider = sessionStore
    )
    private val authRepository = AuthRepository(
        apiClient = apiClient,
        sessionStore = sessionStore
    )

    val authStateHolder = AuthStateHolder(
        authRepository = authRepository,
        platform = "android",
        appVersion = "0.1.0"
    )

    val gridStateHolder = GridStateHolder(
        repository = ApiGridFeedRepository(apiClient)
    )

    val profileDetailStateHolder = ProfileDetailStateHolder(
        repository = ApiPublicProfileRepository(apiClient)
    )

    val conversationStateHolder = ConversationStateHolder(
        repository = ApiConversationRepository(apiClient)
    )

    val messagesStateHolder = MessagesStateHolder(
        repository = ApiMessagesRepository(apiClient)
    )

    val activityStateHolder = ActivityStateHolder(
        repository = ApiActivityFeedRepository(apiClient)
    )

    val locationStateHolder = LocationStateHolder(
        repository = ApiLocationUpdateRepository(apiClient)
    )

    val mediaAlbumsStateHolder = MediaAlbumsStateHolder(
        repository = ApiMediaAlbumsRepository(
            apiClient = apiClient,
            mediaByteUploader = KtorMediaByteUploader()
        )
    )

    val devLoginCredentials = DevLoginCredentials(
        email = "dev@pynapli.local",
        password = "PynapliDevPass123!"
    )
}
