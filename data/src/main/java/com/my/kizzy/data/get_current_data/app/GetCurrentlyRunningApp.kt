/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * GetAppsUseCase.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.data.get_current_data.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import com.blankj.utilcode.util.AppUtils
import com.my.kizzy.data.rpc.CommonRpc
import com.my.kizzy.data.rpc.RpcImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Objects
import java.util.SortedMap
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

class GetCurrentlyRunningApp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundAppStateHolder: ForegroundAppStateHolder
) {
    private data class CachedApp(val packageName: String, val timestamp: Long)
    private val cachedApp = AtomicReference<CachedApp?>(null)

    fun clearCache() {
        android.util.Log.e("GetCurrentlyRunningApp", "🗑️ Cache cleared manually")
        cachedApp.set(null)
    }

    operator fun invoke(
        beginTime: Long = System.currentTimeMillis() - 30000,
        filterList: List<String> = emptyList()
    ): CommonRpc {
        // PRIORITY 1: Use AccessibilityService if available
        val accessPkg = foregroundAppStateHolder.get()
        android.util.Log.e("GetCurrentlyRunningApp", "Invoke called. AccessPkg: $accessPkg, FilterList size: ${filterList.size}")

        accessPkg?.let { pkg ->
            val inFilter = filterList.isEmpty() || filterList.contains(pkg)
            val isLauncher = pkg.contains("launcher", ignoreCase = true)
            val isSystemUI = pkg == "com.android.systemui" || pkg == "com.my.kizzy"
            android.util.Log.e("GetCurrentlyRunningApp", "Checking AccessPkg: $pkg, inFilter: $inFilter, isLauncher: $isLauncher")

            // Se detectou launcher ou app fora do filtro, limpar cache
            if (isLauncher || (!inFilter && !isSystemUI)) {
                val cached = cachedApp.get()
                if (cached != null) {
                    android.util.Log.e("GetCurrentlyRunningApp", "🚨 App closed/switched, clearing cache (was: ${cached.packageName})")
                    cachedApp.set(null)
                }
                return CommonRpc()
            }

            if (inFilter) {
                android.util.Log.e("GetCurrentlyRunningApp", "✅ Using AccessibilityService: $pkg")
                cachedApp.set(CachedApp(pkg, System.currentTimeMillis()))
                return createCommonRpcDirect(pkg)
            }
        }

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTimeMillis = System.currentTimeMillis()

        if (filterList.isNotEmpty()) {
            // Use a large single window to capture all events including old MOVE_TO_BACKGROUND
            val queryWindow = 300000L // 5 minutes

            val events = usageStatsManager.queryEvents(
                currentTimeMillis - queryWindow,
                currentTimeMillis
            )

            // Track state of ALL apps in filterList
            val appStates = mutableMapOf<String, MutableList<Pair<Long, Boolean>>>()

            while (events.hasNextEvent()) {
                val event = UsageEvents.Event()
                events.getNextEvent(event)

                // Only track apps from filterList
                if (filterList.contains(event.packageName)) {
                    when (event.eventType) {
                        UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            appStates.getOrPut(event.packageName) { mutableListOf() }
                                .add(Pair(event.timeStamp, true))
                        }
                        UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            appStates.getOrPut(event.packageName) { mutableListOf() }
                                .add(Pair(event.timeStamp, false))
                        }
                    }
                }
            }

            // Find most recent app and its current state
            var mostRecentApp: String? = null
            var mostRecentTime = 0L
            var isCurrentlyInForeground = false

            for ((pkg, eventsList) in appStates) {
                // Get most recent event for this app
                val lastEvent = eventsList.maxByOrNull { it.first }
                if (lastEvent != null && lastEvent.first > mostRecentTime) {
                    mostRecentTime = lastEvent.first
                    mostRecentApp = pkg
                    isCurrentlyInForeground = lastEvent.second
                }
            }

            // If found an app in FOREGROUND
            if (mostRecentApp != null && isCurrentlyInForeground) {
                if (!mostRecentApp.contains("launcher", ignoreCase = true)) {
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Found foreground app: $mostRecentApp (${currentTimeMillis - mostRecentTime}ms ago)")

                    cachedApp.set(CachedApp(mostRecentApp, mostRecentTime))
                    return createCommonRpcDirect(mostRecentApp)
                }
            }

            // If last event was BACKGROUND, clear cache
            val cached = cachedApp.get()
            if (mostRecentApp != null &&
                cached != null &&
                mostRecentApp == cached.packageName &&
                !isCurrentlyInForeground) {

                android.util.Log.e("GetCurrentlyRunningApp", "✗ App moved to background: $mostRecentApp")
                cachedApp.set(null)
                return CommonRpc()
            }

            // Use cache with 1-second timeout as fallback
            if (cached != null && filterList.contains(cached.packageName)) {
                val timeSinceKnown = currentTimeMillis - cached.timestamp

                // Very short timeout: AccessibilityService should update within 1s
                if (timeSinceKnown < 1000) {
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Using cached foreground app: ${cached.packageName} (${timeSinceKnown}ms old)")
                    return createCommonRpcDirect(cached.packageName)
                } else {
                    android.util.Log.e("GetCurrentlyRunningApp", "⏰ Cache timeout: ${cached.packageName} (${timeSinceKnown}ms old)")
                    cachedApp.set(null)
                }
            }
        }

        android.util.Log.e("GetCurrentlyRunningApp", "Using fallback method...")

        // Fallback for cases where no events (app just opened)
        val fallbackBeginTime = currentTimeMillis - 60000 // 1 minute
        val queryUsageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, fallbackBeginTime, currentTimeMillis
        )

        if (queryUsageStats == null || queryUsageStats.isEmpty()) {
            android.util.Log.e("GetCurrentlyRunningApp", "No usage stats available")
            // Clear cache if no data
            val cached = cachedApp.get()
            if (cached != null) {
                android.util.Log.e("GetCurrentlyRunningApp", "✗ Clearing stale cache: ${cached.packageName}")
                cachedApp.set(null)
            }
            return CommonRpc()
        }

        val treeMap: SortedMap<Long, UsageStats> = TreeMap()
        for (usageStats in queryUsageStats) {
            treeMap[usageStats.lastTimeUsed] = usageStats
        }

        if (treeMap.isEmpty()) {
            android.util.Log.e("GetCurrentlyRunningApp", "TreeMap is empty")
            val cached = cachedApp.get()
            if (cached != null) {
                android.util.Log.e("GetCurrentlyRunningApp", "✗ Clearing stale cache: ${cached.packageName}")
                cachedApp.set(null)
            }
            return CommonRpc()
        }

        if (filterList.isNotEmpty()) {
            val keys = treeMap.keys.toList().reversed()
            android.util.Log.e("GetCurrentlyRunningApp", "Checking ${keys.size} apps against filter list")

            // Track if cached app is found in recent usage
            var cachedAppFoundInRecent = false
            val cached = cachedApp.get()

            for (key in keys) {
                val usageStats = treeMap[key]!!
                val pkg = usageStats.packageName

                val timeSinceLastUse = currentTimeMillis - usageStats.lastTimeUsed
                // Reduce window to 30 seconds
                if (timeSinceLastUse > 30000 && timeSinceLastUse >= 0) {
                    continue
                }

                if (pkg.contains("launcher", ignoreCase = true) ||
                    pkg == "com.my.kizzy" ||
                    pkg == "android") {
                    continue
                }

                // Mark if we found the cached app
                if (cached != null && pkg == cached.packageName) {
                    cachedAppFoundInRecent = true
                }

                if (filterList.contains(pkg)) {
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Found valid app (fallback): $pkg (${timeSinceLastUse}ms ago)")
                    cachedApp.set(CachedApp(pkg, key))
                    return createCommonRpcDirect(pkg)
                }
            }

            // If cached app NOT found in recent UsageStats, clear it
            if (cached != null && !cachedAppFoundInRecent) {
                android.util.Log.e("GetCurrentlyRunningApp", "✗ Cached app not in recent usage: ${cached.packageName}")
                cachedApp.set(null)
            }

            android.util.Log.e("GetCurrentlyRunningApp", "✗ No enabled app found")
            return CommonRpc()
        } else {
            val lastKey = treeMap.lastKey()
            val lastPackageName = treeMap[lastKey]!!.packageName
            android.util.Log.e("GetCurrentlyRunningApp", "Returning most recent app: $lastPackageName")
            return createCommonRpcDirect(lastPackageName)
        }
    }

    fun createCommonRpcDirect(packageName: String): CommonRpc {
        Objects.requireNonNull(packageName)
        return CommonRpc(
            name = AppUtils.getAppName(packageName),
            details = null,
            state = null,
            largeImage = RpcImage.ApplicationIcon(packageName, context),
            packageName = packageName
        )
    }
}