package com.my.kizzy.feature_rpc_base.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService that detects the currently running foreground app.
 * Emits package name changes via [currentAppFlow] for reactive consumers.
 */
class ForegroundAppDetector : AccessibilityService() {

    companion object {
        private const val TAG = "ForegroundAppDetector"
        
        private val _currentAppFlow = MutableStateFlow<String?>(null)
        
        /** Flow that emits the current foreground app's package name */
        val currentAppFlow: StateFlow<String?> = _currentAppFlow.asStateFlow()

        /** Current foreground app package name (nullable) */
        var currentForegroundApp: String? = null
            private set
            
        /** Whether this accessibility service is currently running */
        var isRunning: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or 
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                if (pkg != currentForegroundApp) {
                    currentForegroundApp = pkg
                    _currentAppFlow.value = pkg
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        currentForegroundApp = null
        _currentAppFlow.value = null
    }
}
