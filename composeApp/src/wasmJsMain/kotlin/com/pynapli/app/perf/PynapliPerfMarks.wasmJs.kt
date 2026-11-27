@file:OptIn(ExperimentalWasmJsInterop::class)

package com.pynapli.app.perf

import kotlin.js.ExperimentalWasmJsInterop

/** `performance.getEntriesByName('pynapli:<name>')` in the page; the web QA script subtracts two of them. */
internal actual fun emitPlatformPerfMark(name: String) = performanceMark("pynapli:" + name.replace('_', '-'))

// Kotlin/Wasm JS interop requires js() to be the whole body of a top-level function.
private fun performanceMark(name: String): Unit = js("{ try { performance.mark(name); } catch (e) { } }")
