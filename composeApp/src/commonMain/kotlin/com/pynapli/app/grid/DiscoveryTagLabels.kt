package com.pynapli.app.grid

/**
 * The readable label for a discovery taxonomy key the server sends ("public_meet_first" ->
 * "Public meet first"). Profile tags on cards mix lifestyle, logistics, intent and style keys, so
 * every taxonomy is checked; an unknown key (newer server) falls back to a tidied version of
 * itself rather than showing the raw key.
 */
internal fun discoveryTagLabel(value: String): String =
    LifestyleTag.entries.firstOrNull { it.serverValue == value }?.label
        ?: LogisticsTag.entries.firstOrNull { it.serverValue == value }?.label
        ?: Intent.entries.firstOrNull { it.serverValue == value }?.label
        ?: DiscoveryStyle.entries.firstOrNull { it.serverValue == value }?.label
        ?: value.split('_').joinToString(" ").replaceFirstChar { it.uppercase() }
