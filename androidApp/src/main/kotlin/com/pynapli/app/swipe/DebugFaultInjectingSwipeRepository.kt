package com.pynapli.app.swipe

import android.content.Context
import androidx.core.content.edit
import com.pynapli.app.debug.DebugQaFlags
import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MatchDto
import com.pynapli.shared.network.dto.SwipeDeckRequestDto

class DebugFaultInjectingSwipeRepository(
    context: Context,
    private val delegate: SwipeRepository,
) : SwipeRepository {
    private val appContext = context.applicationContext
    private val preferences by lazy {
        appContext.getSharedPreferences(DebugQaFlags.PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun loadDeck(request: SwipeDeckRequestDto): AppResult<SwipeDeckUi> = delegate.loadDeck(request)

    override suspend fun loadTopPicks(): AppResult<SwipeDeckUi> = delegate.loadTopPicks()

    override suspend fun submitAction(action: QueuedSwipeAction): AppResult<MatchDto?> {
        if (DebugQaFlags.isDebuggable(appContext)) {
            val remainingFailures = preferences.getInt(DebugQaFlags.KEY_FAIL_NEXT_SWIPE_ACTIONS, 0)
            if (remainingFailures > 0) {
                // commit, not apply: the QA harness force-stops the app between stages, and an
                // async write can lose the decremented counter — which silently changes how many
                // failures the next stage injects.
                preferences.edit(commit = true) {
                    putInt(DebugQaFlags.KEY_FAIL_NEXT_SWIPE_ACTIONS, remainingFailures - 1)
                }
                return AppResult.Failure(
                    AppError(
                        code = "NETWORK_ERROR",
                        message = "Debug offline simulation.",
                        recoverable = true,
                    ),
                )
            }
        }

        return delegate.submitAction(action)
    }

    override suspend fun rewind(): AppResult<SwipeRewindUi?> = delegate.rewind()
}
