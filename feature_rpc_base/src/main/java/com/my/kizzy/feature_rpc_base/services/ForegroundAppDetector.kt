package com.my.kizzy.feature_rpc_base.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

import com.my.kizzy.data.get_current_data.app.ForegroundAppStateHolder
import com.my.kizzy.data.get_current_data.app.ForegroundDetectorManager

class ForegroundAppDetector : AccessibilityService() {

    companion object {
        private const val TAG = "ForegroundAppDetector"
        private const val DEBOUNCE_MS = 100L
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ForegroundAppDetectorEntryPoint {
        fun stateHolder(): ForegroundAppStateHolder
        fun detectorManager(): ForegroundDetectorManager
    }

    private val entryPoint: ForegroundAppDetectorEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            ForegroundAppDetectorEntryPoint::class.java
        )
    }

    private val stateHolder: ForegroundAppStateHolder by lazy { 
        entryPoint.stateHolder() 
    }
    
    private val detectorManager: ForegroundDetectorManager by lazy { 
        entryPoint.detectorManager() 
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackage: String? = null
    private var lastEventTime = 0L
    
    private val emitRunnable = Runnable {
        pendingPackage?.let { pkg ->
            val current = stateHolder.get()
            if (pkg != current) {
                log("📱 App changed: $current -> $pkg")
                stateHolder.update(pkg)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 50L
        }
        
        detectorManager.refreshAvailability()
        detectorManager.setRunning(true)
        stopUsageStatsPolling()
        
        log("🟢 Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            processEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing event", e)
        }
    }
    
    private fun processEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        
        val pkg = event.packageName?.toString() ?: return
        if (pkg.shouldBeIgnored()) return
        
        val now = System.currentTimeMillis()
        pendingPackage = pkg
        handler.removeCallbacks(emitRunnable)
        
        if (now - lastEventTime > DEBOUNCE_MS) {
            emitRunnable.run()
        } else {
            handler.postDelayed(emitRunnable, DEBOUNCE_MS)
        }
        
        lastEventTime = now
    }
    
    private fun String.shouldBeIgnored(): Boolean {
        return this in IGNORED_PACKAGES || 
               IGNORED_PATTERNS.any { contains(it, ignoreCase = true) }
    }

    override fun onInterrupt() {
        log("⚠️ Service interrupted")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detectorManager.setRunning(false)
        detectorManager.refreshAvailability()
        log("🔴 Service destroyed")
        super.onDestroy()
    }
    
    private fun stopUsageStatsPolling() {
        try {
            UsageStatsPollingService.stop(this)
            log("🛑 Stopped UsageStats polling")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping polling", e)
        }
    }
    
    private fun log(message: String) {
        Log.d(TAG, message)
    }
}

// Constantes fora da classe para evitar recriação
private val IGNORED_PACKAGES = setOf(
    "com.android.systemui",
    "android",
)

private val IGNORED_PATTERNS = listOf(
    "inputmethod",
    "keyboard", 
    "launcher",
    ".ime.",
)