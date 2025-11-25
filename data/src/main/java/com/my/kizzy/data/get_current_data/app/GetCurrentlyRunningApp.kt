package com.my.kizzy.data.get_current_data.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.blankj.utilcode.util.AppUtils
import com.my.kizzy.data.rpc.CommonRpc
import com.my.kizzy.data.rpc.RpcImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

class GetCurrentlyRunningApp @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foregroundAppStateHolder: ForegroundAppStateHolder
) {
    companion object {
        private const val TAG = "GetCurrentlyRunningApp"
        
        // Tempo máximo que consideramos um app como "ainda em foreground"
        private const val FOREGROUND_THRESHOLD_MS = 30_000L  // 30 segundos
        
        // Tempo para considerar cache válido quando app está "estável"
        private const val CACHE_EXTENDED_MS = 120_000L  // 2 minutos

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.my.kizzy",
            "com.my.kizzy.debug"
        )

        private val IGNORED_PATTERNS = listOf(
            "inputmethod", "keyboard", ".ime."
        )

        private val LAUNCHER_PATTERNS = listOf(
            "launcher",
            "com.miui.home",
            "trebuchet",
            "lawnchair",
            "nova",
            "oneplus.launcher",
            "sec.android.app.launcher",
            "huawei.android.launcher",
            "oppo.launcher",
            "vivo.launcher",
            "realme.launcher"
        )
    }

    private data class CachedApp(val packageName: String, val lastSeenTime: Long)
    private val cachedApp = AtomicReference<CachedApp?>(null)
    
    // Guarda o timestamp da última vez que vimos o launcher
    private var lastLauncherTime = 0L

    fun clearCache() {
        cachedApp.set(null)
        lastLauncherTime = 0L
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    operator fun invoke(
        beginTime: Long = System.currentTimeMillis() - 60000,
        filterList: List<String> = emptyList()
    ): CommonRpc {
        val currentTime = System.currentTimeMillis()

        Log.d(TAG, "════════════════════════════════════════")

        // ═══════════════════════════════════════════════════════════
        // 1. AccessibilityService (prioridade máxima)
        // ═══════════════════════════════════════════════════════════
        val accessPkg = foregroundAppStateHolder.get()
        Log.d(TAG, "🔍 Accessibility: ${accessPkg ?: "NULL"}")

        if (accessPkg != null) {
            val result = handleAccessibilityPackage(accessPkg, filterList, currentTime)
            if (result != null) return result
        }

        // ═══════════════════════════════════════════════════════════
        // 2. UsageStats
        // ═══════════════════════════════════════════════════════════
        if (hasUsageStatsPermission()) {
            val statsResult = analyzeUsageStats(filterList, currentTime)
            if (statsResult != null) return statsResult
        }

        // ═══════════════════════════════════════════════════════════
        // 3. Nenhum app encontrado
        // ═══════════════════════════════════════════════════════════
        Log.d(TAG, "❌ No app found")
        Log.d(TAG, "════════════════════════════════════════")
        return CommonRpc()
    }

    private fun handleAccessibilityPackage(
        pkg: String,
        filterList: List<String>,
        currentTime: Long
    ): CommonRpc? {
        if (pkg.isLauncher()) {
            Log.d(TAG, "🏠 Launcher via Accessibility")
            cachedApp.set(null)
            lastLauncherTime = currentTime
            return CommonRpc()
        }

        if (pkg.shouldBeIgnored()) {
            val cached = cachedApp.get()
            if (cached != null && (filterList.isEmpty() || filterList.contains(cached.packageName))) {
                return createCommonRpcDirect(cached.packageName)
            }
            return null
        }

        if (filterList.isEmpty() || filterList.contains(pkg)) {
            Log.d(TAG, "✅ Accessibility: $pkg")
            cachedApp.set(CachedApp(pkg, currentTime))
            return createCommonRpcDirect(pkg)
        }

        // App fora do filtro foi aberto - limpa cache
        Log.d(TAG, "📤 Different app opened: $pkg (not in filter)")
        cachedApp.set(null)
        return null
    }

    private fun analyzeUsageStats(
        filterList: List<String>,
        currentTime: Long
    ): CommonRpc? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null

        try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_YEARLY,
                currentTime - 60 * 60 * 1000L,
                currentTime
            )

            if (stats.isNullOrEmpty()) {
                Log.d(TAG, "📈 No UsageStats")
                return null
            }

            // Ordena por lastTimeUsed
            val sortedStats = stats
                .filter { !it.packageName.shouldBeIgnored() }
                .sortedByDescending { it.lastTimeUsed }

            // Encontra o app mais recente (pode ser launcher)
            val mostRecentApp = sortedStats.firstOrNull()
            
            // Encontra o launcher mais recente
            val mostRecentLauncher = sortedStats.find { it.packageName.isLauncher() }
            
            // Encontra o app do filtro mais recente
            val mostRecentFilteredApp = sortedStats.find { 
                filterList.isEmpty() || filterList.contains(it.packageName) 
            }

            // Debug logs
            Log.d(TAG, "📈 Most recent: ${mostRecentApp?.packageName} (${mostRecentApp?.let { (currentTime - it.lastTimeUsed) / 1000 }}s)")
            mostRecentLauncher?.let { 
                Log.d(TAG, "🏠 Launcher: ${it.packageName} (${(currentTime - it.lastTimeUsed) / 1000}s)")
            }
            mostRecentFilteredApp?.let {
                Log.d(TAG, "🎮 Filtered: ${it.packageName} (${(currentTime - it.lastTimeUsed) / 1000}s)")
            }

            val cached = cachedApp.get()

            // ═══════════════════════════════════════════════════════
            // CASO 1: Launcher é o app mais recente → usuário na home
            // ═══════════════════════════════════════════════════════
            if (mostRecentApp?.packageName?.isLauncher() == true) {
                val launcherAge = currentTime - mostRecentApp.lastTimeUsed
                
                // Se launcher foi usado recentemente (menos de 30s)
                if (launcherAge < FOREGROUND_THRESHOLD_MS) {
                    Log.d(TAG, "🏠 User is on HOME (launcher ${launcherAge/1000}s ago)")
                    cachedApp.set(null)
                    lastLauncherTime = mostRecentApp.lastTimeUsed
                    return CommonRpc()
                }
            }

            // ═══════════════════════════════════════════════════════
            // CASO 2: Launcher foi usado DEPOIS do app cacheado → app fechado
            // ═══════════════════════════════════════════════════════
            if (cached != null && mostRecentLauncher != null) {
                if (mostRecentLauncher.lastTimeUsed > cached.lastSeenTime) {
                    Log.d(TAG, "📤 Launcher used after cached app - clearing")
                    cachedApp.set(null)
                    lastLauncherTime = mostRecentLauncher.lastTimeUsed
                    return CommonRpc()
                }
            }

            // ═══════════════════════════════════════════════════════
            // CASO 3: Outro app (fora do filtro) é mais recente que o cacheado
            // ═══════════════════════════════════════════════════════
            if (cached != null && mostRecentApp != null) {
                val isInFilter = filterList.isEmpty() || filterList.contains(mostRecentApp.packageName)
                val isMoreRecent = mostRecentApp.lastTimeUsed > cached.lastSeenTime
                
                if (!mostRecentApp.packageName.isLauncher() && 
                    !isInFilter && 
                    isMoreRecent) {
                    Log.d(TAG, "📤 Other app opened: ${mostRecentApp.packageName}")
                    cachedApp.set(null)
                    return CommonRpc()
                }
            }

            // ═══════════════════════════════════════════════════════
            // CASO 4: App do filtro está em foreground
            // ═══════════════════════════════════════════════════════
            if (mostRecentFilteredApp != null) {
                val appAge = currentTime - mostRecentFilteredApp.lastTimeUsed
                val pkg = mostRecentFilteredApp.packageName
                
                // App usado nos últimos 30 segundos → definitivamente em foreground
                if (appAge <= FOREGROUND_THRESHOLD_MS) {
                    Log.d(TAG, "✅ UsageStats: $pkg (${appAge/1000}s ago)")
                    cachedApp.set(CachedApp(pkg, mostRecentFilteredApp.lastTimeUsed))
                    return createCommonRpcDirect(pkg)
                }
                
                // App é o mais recente E é o mesmo do cache → provavelmente ainda aberto
                if (cached != null && 
                    pkg == cached.packageName &&
                    mostRecentApp?.packageName == pkg) {
                    
                    val cacheAge = currentTime - cached.lastSeenTime
                    
                    if (cacheAge < CACHE_EXTENDED_MS) {
                        Log.d(TAG, "✅ UsageStats (cached): $pkg")
                        return createCommonRpcDirect(pkg)
                    }
                }
                
                // Se não há launcher mais recente, pode estar no app ainda
                if (mostRecentLauncher == null || 
                    mostRecentFilteredApp.lastTimeUsed > mostRecentLauncher.lastTimeUsed) {
                    
                    if (appAge <= CACHE_EXTENDED_MS) {
                        Log.d(TAG, "✅ UsageStats (no launcher): $pkg (${appAge/1000}s ago)")
                        cachedApp.set(CachedApp(pkg, mostRecentFilteredApp.lastTimeUsed))
                        return createCommonRpcDirect(pkg)
                    }
                }
            }

            // ═══════════════════════════════════════════════════════
            // CASO 5: Cache ainda válido
            // ═══════════════════════════════════════════════════════
            if (cached != null) {
                val cacheAge = currentTime - cached.lastSeenTime
                val inFilter = filterList.isEmpty() || filterList.contains(cached.packageName)
                
                // Verifica se não há evidência de que saiu do app
                val noLauncherEvidence = mostRecentLauncher == null || 
                    mostRecentLauncher.lastTimeUsed < cached.lastSeenTime
                    
                if (inFilter && noLauncherEvidence && cacheAge < CACHE_EXTENDED_MS) {
                    Log.d(TAG, "✅ Cache (no contrary evidence): ${cached.packageName}")
                    return createCommonRpcDirect(cached.packageName)
                }
                
                Log.d(TAG, "📤 Cache invalidated (age: ${cacheAge/1000}s, launcher evidence: ${!noLauncherEvidence})")
                cachedApp.set(null)
            }

            Log.d(TAG, "❌ No valid app state")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error in analyzeUsageStats", e)
            return null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun String.shouldBeIgnored(): Boolean {
        if (this in IGNORED_PACKAGES) return true
        return IGNORED_PATTERNS.any { contains(it, ignoreCase = true) }
    }

    private fun String.isLauncher(): Boolean {
        return LAUNCHER_PATTERNS.any { pattern ->
            this.contains(pattern, ignoreCase = true)
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