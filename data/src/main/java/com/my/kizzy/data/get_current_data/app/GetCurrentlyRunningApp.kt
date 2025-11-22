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
    operator fun invoke(
        beginTime: Long = System.currentTimeMillis() - 300000, // 5 minutes
        filterList: List<String> = emptyList()
    ): CommonRpc {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val currentTimeMillis = System.currentTimeMillis()
        val queryUsageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, beginTime, currentTimeMillis
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
            
            // Log ALL recent apps to debug
            val recentApps = keys.take(10).map { treeMap[it]!!.packageName }
            android.util.Log.e("GetCurrentlyRunningApp", "Top 10 recent apps: $recentApps")
            android.util.Log.e("GetCurrentlyRunningApp", "Looking for: $filterList")
            
            // Check if Symfonik is in the full list
            val hasSymfonik = keys.any { treeMap[it]!!.packageName == "app.symfonik.music.player" }
            android.util.Log.e("GetCurrentlyRunningApp", "Symfonik in usage stats: $hasSymfonik")
            
            for (key in keys) {
                val usageStats = treeMap[key]!!
                if (filterList.contains(usageStats.packageName)) {
                    android.util.Log.e("GetCurrentlyRunningApp", "✓ Found enabled app: ${usageStats.packageName}")
                    return createCommonRpc(usageStats.packageName)
                }
            }
            // No filtered app found, return empty
            android.util.Log.e("GetCurrentlyRunningApp", "✗ No enabled app found in recent usage")
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