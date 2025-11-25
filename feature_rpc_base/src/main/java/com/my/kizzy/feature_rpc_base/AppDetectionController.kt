package com.my.kizzy.feature_rpc_base

import android.content.Context
import android.util.Log
import com.my.kizzy.data.get_current_data.app.ForegroundDetectorManager
import com.my.kizzy.feature_rpc_base.services.UsageStatsPollingService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppDetectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detectorManager: ForegroundDetectorManager
) {
    companion object {
        private const val TAG = "AppDetectionController"
    }

    fun start(): Boolean {
        val method = detectorManager.refreshAvailability()
        
        Log.d(TAG, "Starting detection: $method")
        
        return when (method) {
            ForegroundDetectorManager.DetectionMethod.ACCESSIBILITY -> {
                UsageStatsPollingService.stop(context)
                Log.d(TAG, "✅ Using Accessibility (real-time)")
                true
            }
            
            ForegroundDetectorManager.DetectionMethod.USAGE_STATS -> {
                UsageStatsPollingService.start(context)
                Log.d(TAG, "✅ Using UsageStats (polling)")
                true
            }
            
            ForegroundDetectorManager.DetectionMethod.NONE -> {
                Log.w(TAG, "❌ No detection method available")
                false
            }
        }
    }

    fun stop() {
        UsageStatsPollingService.stop(context)
        detectorManager.stateHolder.clear()
        detectorManager.setRunning(false)
        Log.d(TAG, "🛑 Detection stopped")
    }

    fun getCurrentMethod() = detectorManager.currentMethod.value
    
    fun isRunning() = detectorManager.isRunning.value
}
