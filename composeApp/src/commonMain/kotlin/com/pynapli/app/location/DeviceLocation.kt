package com.pynapli.app.location

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Int?
)
