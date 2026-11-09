package com.pynapli.shared.core.result

sealed interface AppResult<out T> {
    data class Success<T>(
        val value: T,
    ) : AppResult<T>

    data class Failure(
        val error: AppError,
    ) : AppResult<Nothing>
}

data class AppError(
    val code: String,
    val message: String,
    val recoverable: Boolean = true,
)

/** Transforms a success value; a failure passes through unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> =
    when (this) {
        is AppResult.Success -> AppResult.Success(transform(value))
        is AppResult.Failure -> this
    }
