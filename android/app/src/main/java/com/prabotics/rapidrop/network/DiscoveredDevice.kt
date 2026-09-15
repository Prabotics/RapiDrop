package com.prabotics.rapidrop.network


data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val id: String = "",
    val lastSeen: Long = System.currentTimeMillis()
)

data class PairInviteInfo(
    val deviceName: String,
    val pin: String?,
    val host: String? = null,
    val port: Int = WireFrame.DEFAULT_PORT
)
