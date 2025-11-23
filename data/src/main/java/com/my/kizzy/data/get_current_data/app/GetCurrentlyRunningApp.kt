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

import android.app.ActivityManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import com.blankj.utilcode.util.AppUtils
import com.my.kizzy.data.rpc.CommonRpc
import com.my.kizzy.data.rpc.RpcImage
import com.my.kizzy.preference.Prefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Objects
import java.util.SortedMap
import java.util.TreeMap
import javax.inject.Inject

class GetCurrentlyRunningApp @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Cache do último app em foreground
    private var lastKnownForegroundApp: String? = null
    private var lastKnownForegroundTime: Long = 0L
    
    operator fun invoke(
        beginTime: Long = System.currentTimeMillis() - 30000,
        filterList: List<String> = emptyList()
    ): CommonRpc {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTimeMillis = System.currentTimeMillis()
        
        if (filterList.isNotEmpty()) {
            // MUDANÇA: Usar janela menor primeiro para detecção rápida
            val timeWindows = listOf(2000L, 5000L, 15000L)
            
            for (window in timeWindows) {
                val events = usageStatsManager.queryEvents(currentTimeMillis - window, currentTimeMillis)
                val appStates = mutableMapOf<String, MutableList<Pair<Long, Boolean>>>()
                
                // MUDANÇA: Coletar todos os eventos de cada app
                while (events.hasNextEvent()) {
                    val event = android.app.usage.UsageEvents.Event()
                    events.getNextEvent(event)
                    
                    when (event.eventType) {
                        android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            appStates.getOrPut(event.packageName) { mutableListOf() }
                                .add(Pair(event.timeStamp, true))
                        }
                        android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            appStates.getOrPut(event.packageName) { mutableListOf() }
                                .add(Pair(event.timeStamp, false))
                        }
                    }
                }
                
                // MUDANÇA: Encontrar o app mais recente e verificar se ainda está em foreground
                var mostRecentApp: String? = null
                var mostRecentTime = 0L
                var isCurrentlyInForeground = false
                
                for ((pkg, eventsList) in appStates) {
                    // Pegar o evento mais recente deste app
                    val lastEvent = eventsList.maxByOrNull { it.first }
                    if (lastEvent != null && lastEvent.first > mostRecentTime) {
                        mostRecentTime = lastEvent.first
                        mostRecentApp = pkg
                        isCurrentlyInForeground = lastEvent.second
                    }
                }
                
                // CORREÇÃO: Verificar se encontrou um app válido em foreground
                if (mostRecentApp != null && isCurrentlyInForeground) {
                    if (filterList.contains(mostRecentApp) && 
                        !mostRecentApp.contains("launcher", ignoreCase = true)) {
                        
                        val timeSince = currentTimeMillis - mostRecentTime
                        android.util.Log.e("GetCurrentlyRunningApp", "✓ Found foreground app: $mostRecentApp (${timeSince}ms ago, window=${window}ms)")
                        
                        lastKnownForegroundApp = mostRecentApp
                        lastKnownForegroundTime = mostRecentTime
                        return createCommonRpc(mostRecentApp)
                    }
                }
                
                // CORREÇÃO: Só limpar cache se detectou background do app que conhecemos
                if (mostRecentApp != null && 
                    mostRecentApp == lastKnownForegroundApp && 
                    !isCurrentlyInForeground) {
                    
                    android.util.Log.e("GetCurrentlyRunningApp", "✗ App moved to background: $mostRecentApp")
                    lastKnownForegroundApp = null
                    lastKnownForegroundTime = 0L
                    return CommonRpc()
                }
            }
            
            // MUDANÇA: Reduzir cache para 10 segundos em vez de 30
            if (lastKnownForegroundApp != null && filterList.contains(lastKnownForegroundApp)) {
                val timeSinceKnown = currentTimeMillis - lastKnownForegroundTime
                if (timeSinceKnown < 10000) { // 10 segundos
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Using cached foreground app: $lastKnownForegroundApp (${timeSinceKnown}ms old)")
                    return createCommonRpc(lastKnownForegroundApp!!)
                } else {
                    // Cache expirado
                    android.util.Log.e("GetCurrentlyRunningApp", "⏱ Cache expired for: $lastKnownForegroundApp")
                    lastKnownForegroundApp = null
                    lastKnownForegroundTime = 0L
                }
            }
        }
        
        android.util.Log.e("GetCurrentlyRunningApp", "Using fallback method...")
        
        // MUDANÇA: Reduzir fallback para 10 segundos
        val fallbackBeginTime = currentTimeMillis - 10000
        val queryUsageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST, fallbackBeginTime, currentTimeMillis
        )
        
        if (queryUsageStats == null || queryUsageStats.isEmpty()) {
            android.util.Log.e("GetCurrentlyRunningApp", "No usage stats available")
            return CommonRpc()
        }
        
        val treeMap: SortedMap<Long, UsageStats> = TreeMap()
        for (usageStats in queryUsageStats) {
            treeMap[usageStats.lastTimeUsed] = usageStats
        }
        
        if (treeMap.isEmpty()) {
            android.util.Log.e("GetCurrentlyRunningApp", "TreeMap is empty")
            return CommonRpc()
        }
        
        // If filter list is provided, only look for apps in that list
        if (filterList.isNotEmpty()) {
            val keys = treeMap.keys.toList().reversed()
            android.util.Log.e("GetCurrentlyRunningApp", "Checking ${keys.size} apps against filter list")
            for (key in keys) {
                val usageStats = treeMap[key]!!
                val pkg = usageStats.packageName
                
                // MUDANÇA: Reduzir para 10 segundos
                val timeSinceLastUse = currentTimeMillis - usageStats.lastTimeUsed
                if (timeSinceLastUse > 10000) {
                    continue
                }
                
                if (pkg.contains("launcher", ignoreCase = true) || 
                    pkg == "com.my.kizzy" ||
                    pkg == "android") {
                    continue
                }
                
                if (filterList.contains(pkg)) {
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Found valid app: $pkg (${timeSinceLastUse}ms ago)")
                    lastKnownForegroundApp = pkg
                    lastKnownForegroundTime = key
                    return createCommonRpc(pkg)
                }
            }
            
            android.util.Log.e("GetCurrentlyRunningApp", "✗ No enabled app found")
            return CommonRpc()
        } else {
            // No filter, return most recent app
            val lastKey = treeMap.lastKey()
            val lastPackageName = treeMap[lastKey]!!.packageName
            android.util.Log.e("GetCurrentlyRunningApp", "Returning most recent app: $lastPackageName")
            return createCommonRpc(lastPackageName)
        }
    }

    private fun createCommonRpc(packageName: String): CommonRpc {
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