package com.my.kizzy.data.get_current_data.app

interface ForegroundDetector {
    fun isAvailable(): Boolean
    fun getCurrentApp(): String?
}
