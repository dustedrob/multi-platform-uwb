package com.dustedrob.uwb

data class NearbyDevice(
    val id: String,
    val name: String,
    val distance: Double? = null,
    val lastSeen: Long = getCurrentTimeMillis()
)
