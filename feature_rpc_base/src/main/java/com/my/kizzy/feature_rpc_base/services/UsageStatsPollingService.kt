package com.my.kizzy.feature_rpc_base.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.my.kizzy.data.get_current_data.app.ForegroundDetectorManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class UsageStatsPollingService : Service() {

    companion object {
        private const val TAG = "UsageStatsPolling"
        private const val POLL_INTERVAL_MS = 500L
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "foreground_detection_channel"
        
        fun start(context: Context) {
            val intent = Intent(context, UsageStatsPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, UsageStatsPollingService::class.java))
        }
    }

    @Inject
    lateinit var detectorManager: ForegroundDetectorManager

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        Log.d(TAG, "🟢 Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (detectorManager.isAccessibilityServiceEnabled()) {
            Log.d(TAG, "⚠️ Accessibility active, stopping polling")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (!detectorManager.isUsageStatsAvailable()) {
            Log.d(TAG, "❌ UsageStats not available")
            stopSelf()
            return START_NOT_STICKY
        }
        
        startPolling()
        detectorManager.setRunning(true)
        
        return START_STICKY
    }

    private fun startPolling() {
        pollingJob?.cancel()
        
        pollingJob = serviceScope.launch {
            var lastPackage: String? = null
            Log.d(TAG, "🔄 Polling started")

            while (isActive) {
                if (detectorManager.isAccessibilityServiceEnabled()) {
                    Log.d(TAG, "🔀 Accessibility enabled, stopping")
                    withContext(Dispatchers.Main) {
                        stopSelf()
                    }
                    break
                }

                try {
                    val currentPackage = detectorManager.getCurrentAppViaUsageStats()

                    if (currentPackage != null && currentPackage != lastPackage) {
                        Log.d(TAG, "📱 App: $currentPackage")
                        detectorManager.stateHolder.update(currentPackage)
                        lastPackage = currentPackage
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling", e)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Detecting current app for Discord RPC"
                setShowBadge(false)
            }

            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Detection Active")
            .setContentText("Monitoring foreground apps")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        detectorManager.setRunning(false)
        Log.d(TAG, "🔴 Service destroyed")
        super.onDestroy()
    }
}
