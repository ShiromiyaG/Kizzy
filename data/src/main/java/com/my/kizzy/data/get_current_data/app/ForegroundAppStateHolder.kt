package com.my.kizzy.data.get_current_data.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundAppStateHolder @Inject constructor() {
    
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()
    
    fun get(): String? = _currentApp.value
    
    fun update(packageName: String?) {
        if (packageName != _currentApp.value) {
            _currentApp.value = packageName
        }
    }
    
    fun clear() {
        _currentApp.value = null
    }
}
