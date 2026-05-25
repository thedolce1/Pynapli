package com.pynapli.server.messaging

internal enum class ParticipantStatus {
    None,
    Active,
    Invited,
    Inactive
}

internal enum class PartnerInviteUserRole {
    None,
    InvitedPartner,
    ThirdPartyApprover
}

internal data class GridMessagePolicy(
    val allowGridMessages: Boolean,
    val gridMessagePolicy: String
)
