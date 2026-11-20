package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

/**
 * "Hide from people you know" (PROFILE_SETTINGS_SPEC, TRUST_AND_SAFETY_SPEC "Contact blocks").
 * Body of `POST /v1/me/contact-blocks` (add) and `POST /v1/me/contact-blocks/remove` (remove one by
 * typing it again). Both answer 204 with no body whatever the address is.
 */
@Serializable
data class ContactBlockAddressRequestDto(
    val email: String,
)

/**
 * `GET /v1/me/contact-blocks`. The server stores only a keyed code per address, so it can't list them:
 * [count] is how many addresses this member added, members or not.
 */
@Serializable
data class ContactBlockSummaryDto(
    val count: Int,
    val maxAddresses: Int,
)
