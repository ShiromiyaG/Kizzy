package com.my.kizzy.data.get_current_data.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundDetectorManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsDetector: UsageStatsForegroundDetector,
    val stateHolder: ForegroundAppStateHolder
) {
    companion object {
        private const val TAG = "ForegroundDetectorMgr"
    }

    enum class DetectionMethod {
        ACCESSIBILITY,
        USAGE_STATS,
        NONE
    }

    private val _currentMethod = MutableStateFlow(DetectionMethod.NONE)
    val currentMethod: StateFlow<DetectionMethod> = _currentMethod.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun refreshAvailability(): DetectionMethod {
        val method = when {
            isAccessibilityServiceEnabled() -> DetectionMethod.ACCESSIBILITY
            usageStatsDetector.isAvailable() -> DetectionMethod.USAGE_STATS
            else -> DetectionMethod.NONE
        }
        
        _currentMethod.value = method
        Log.d(TAG, "Detection method: $method")
        return method
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = ComponentName(
            context,
            "com.my.kizzy.feature_rpc_base.services.ForegroundAppDetector"
        ).flattenToString()

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        
        while (colonSplitter.hasNext()) {
            if (colonSplitter.next().equals(serviceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isUsageStatsAvailable(): Boolean = usageStatsDetector.isAvailable()

    fun hasAnyPermission(): Boolean {
        return isAccessibilityServiceEnabled() || usageStatsDetector.isAvailable()
    }

    fun getCurrentAppViaUsageStats(): String? {
        return usageStatsDetector.getCurrentApp()
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun getUsageStatsSettingsIntent(): Intent {
        return usageStatsDetector.openPermissionSettings()
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}
