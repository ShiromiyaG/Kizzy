package com.my.kizzy.data.get_current_data.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current foreground app state with timestamp tracking.
 * Used by both AccessibilityService and UsageStats detection methods.
 */
@Singleton
class ForegroundAppStateHolder @Inject constructor() {
    
    data class AppState(
        val packageName: String,
        val detectedAt: Long = System.currentTimeMillis()
    )
    
    private val _currentApp = MutableStateFlow<AppState?>(null)
    val currentApp: StateFlow<AppState?> = _currentApp.asStateFlow()
    
    /**
     * Returns the current package name, or null if no app is detected
     */
    fun get(): String? = _currentApp.value?.packageName
    
    /**
     * Returns the full AppState with timestamp, or null if no app is detected
     */
    fun getState(): AppState? = _currentApp.value
    
    /**
     * Returns how long the current app has been in foreground (in milliseconds)
     */
    fun getElapsedTime(): Long {
        val state = _currentApp.value ?: return 0L
        return System.currentTimeMillis() - state.detectedAt
    }
    
    /**
     * Returns the timestamp when the current app was first detected
     */
    fun getStartTimestamp(): Long? = _currentApp.value?.detectedAt
    
    /**
     * Updates the current app. Only updates timestamp if package actually changed.
     */
    fun update(packageName: String?) {
        if (packageName == null) {
            _currentApp.value = null
            return
        }
        
        val current = _currentApp.value
        if (current?.packageName != packageName) {
            _currentApp.value = AppState(packageName, System.currentTimeMillis())
        }
    }
    
    fun clear() {
        _currentApp.value = null
    }
}
