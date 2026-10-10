package com.prabotics.rapidrop.clipboard

data class ConnectedDeviceInfo(
    val modelId: String = "",
    val modelName: String = "",
    val chip: String = "",
    val deviceName: String = "",
    val batteryLevel: Int? = null,
    val isCharging: Boolean = false,
)
