package com.my.kizzy.data.get_current_data.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForegroundAppStateHolder @Inject constructor() {
    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage = _currentPackage.asStateFlow()

    fun update(packageName: String?) {
        _currentPackage.value = packageName
    }
    
    fun get(): String? = _currentPackage.value
}
