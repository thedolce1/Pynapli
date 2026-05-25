package com.pynapli.app.profile

internal fun String.stableIndex(size: Int): Int =
    fold(0) { total, char -> total + char.code }.mod(size)
