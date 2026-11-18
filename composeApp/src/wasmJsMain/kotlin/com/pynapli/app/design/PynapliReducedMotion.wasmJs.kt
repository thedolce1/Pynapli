@file:OptIn(ExperimentalWasmJsInterop::class)

package com.pynapli.app.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop

/** The browser's `prefers-reduced-motion` media query, which mirrors the OS setting. */
@Composable
internal actual fun rememberReducedMotion(): Boolean = remember { prefersReducedMotion() }

// Kotlin/Wasm JS interop requires js() to be the whole body of a top-level function.
private fun prefersReducedMotion(): Boolean = js("{ try { return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); } catch (e) { return false; } }")
