package com.pynapli.app.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.conversation.SampleConversationRepository
import com.pynapli.app.profile.ProfileDetailStateHolder
import com.pynapli.app.profile.SamplePublicProfileRepository

@Composable
internal fun rememberGridStateHolder(): GridStateHolder =
    remember {
        GridStateHolder(
            repository = SampleGridFeedRepository(),
        )
    }

@Composable
internal fun rememberProfileDetailStateHolder(): ProfileDetailStateHolder =
    remember {
        ProfileDetailStateHolder(
            repository = SamplePublicProfileRepository(),
        )
    }

@Composable
internal fun rememberConversationStateHolder(): ConversationStateHolder =
    remember {
        ConversationStateHolder(
            repository = SampleConversationRepository(),
        )
    }
