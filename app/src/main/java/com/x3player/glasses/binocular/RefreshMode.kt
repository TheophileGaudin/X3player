package com.x3player.glasses.binocular

enum class RefreshMode {
    IDLE,
    LOW,
    NORMAL,
    HIGH,
    REALTIME;

    fun getIntervalMs(): Long = when (this) {
        IDLE -> DisplayConfig.REFRESH_INTERVAL_IDLE_MS
        LOW -> DisplayConfig.REFRESH_INTERVAL_LOW_MS
        NORMAL -> DisplayConfig.REFRESH_INTERVAL_NORMAL_MS
        HIGH -> DisplayConfig.REFRESH_INTERVAL_HIGH_MS
        REALTIME -> DisplayConfig.REFRESH_INTERVAL_REALTIME_MS
    }

    fun needsContinuousRefresh(): Boolean = this != IDLE
}
