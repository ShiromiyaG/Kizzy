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
            queryForegroundApp()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun queryForegroundApp(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 2000
        
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
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
        )
        private val IGNORED_PATTERNS = listOf(
            "inputmethod", "keyboard", "launcher", ".ime."
        )
    }
}
