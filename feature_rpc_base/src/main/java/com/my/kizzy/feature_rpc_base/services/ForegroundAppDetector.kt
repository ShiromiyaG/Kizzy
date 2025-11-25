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

class ForegroundAppDetector : AccessibilityService() {

    companion object {
        private const val TAG = "ForegroundAppDetector"
        private const val DEBOUNCE_MS = 100L
    }

    // EntryPoint para acessar dependências Hilt
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ForegroundAppDetectorEntryPoint {
        fun stateHolder(): ForegroundAppStateHolder
    }

    // Lazy initialization - só acessa após service estar conectado
    private val stateHolder: ForegroundAppStateHolder by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            ForegroundAppDetectorEntryPoint::class.java
        ).stateHolder()
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackage: String? = null
    private var lastEventTime = 0L
    
    private val emitRunnable = Runnable {
        pendingPackage?.let { pkg ->
            if (pkg != stateHolder.get()) {
                log("📱 App changed: ${stateHolder.get()} -> $pkg")
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
        log("🔴 Service destroyed")
        super.onDestroy()
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