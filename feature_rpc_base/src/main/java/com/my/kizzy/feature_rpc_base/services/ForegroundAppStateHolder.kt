package com.my.kizzy.feature_rpc_base.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundAppStateHolder @Inject constructor() {
    
    private val _currentAppFlow = MutableStateFlow<String?>(null)
    val currentAppFlow: StateFlow<String?> = _currentAppFlow.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    val currentForegroundApp: String? 
        get() = _currentAppFlow.value
    
    fun updateApp(packageName: String?) {
        _currentAppFlow.value = packageName
    }
    
    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _currentAppFlow.value = null
        }
    }
}