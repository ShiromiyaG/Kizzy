package com.my.kizzy.data.get_current_data.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageStatsForegroundDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : ForegroundDetector {

    private val usageStatsManager: UsageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    // Cache para evitar queries repetidas
    private var lastQueryTime = 0L
    private var cachedPackage: String? = null

    override fun isAvailable(): Boolean {
        return try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 60_000
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
            !stats.isNullOrEmpty()
        } catch (e: Exception) {
            false
        }
    }

    override fun getCurrentApp(): String? {
        return try {
            val currentTime = System.currentTimeMillis()
            
            // Retorna cache se ainda válido
            if (currentTime - lastQueryTime < CACHE_VALIDITY_MS && cachedPackage != null) {
                return cachedPackage
            }
            
            val result = queryForegroundApp()
            cachedPackage = result
            lastQueryTime = currentTime
            result
        } catch (e: SecurityException) {
            null
        }
    }

    private fun queryForegroundApp(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - QUERY_WINDOW_MS
        
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        var lastForegroundPackage: String? = null
        var lastTimestamp = 0L
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            
            if (event.isForegroundEvent() && event.timeStamp > lastTimestamp) {
                val pkg = event.packageName
                if (!pkg.shouldBeIgnored()) {
                    lastForegroundPackage = pkg
                    lastTimestamp = event.timeStamp
                }
            }
        }
        
        return lastForegroundPackage
    }

    /**
     * Limpa o cache forçando uma nova query na próxima chamada
     */
    fun invalidateCache() {
        cachedPackage = null
        lastQueryTime = 0L
    }
    
    private fun UsageEvents.Event.isForegroundEvent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            eventType == UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
        }
    }

    private fun String.shouldBeIgnored(): Boolean {
        return this in IGNORED_PACKAGES ||
               IGNORED_PATTERNS.any { contains(it, ignoreCase = true) }
    }

    fun openPermissionSettings(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    companion object {
        private const val CACHE_VALIDITY_MS = 300L
        private const val QUERY_WINDOW_MS = 5000L
        
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.permissioncontroller",
        )
        private val IGNORED_PATTERNS = listOf(
            "inputmethod", 
            "keyboard", 
            "launcher", 
            ".ime.",
            "wallpaper",
            "lockscreen",
            "screenshot",
            "systemui",
            "permissioncontroller"
        )
    }
}
