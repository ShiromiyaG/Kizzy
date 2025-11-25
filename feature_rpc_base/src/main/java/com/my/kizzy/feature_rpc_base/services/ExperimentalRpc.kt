/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * ExperimentalRpc.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.feature_rpc_base.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.IBinder
import com.my.kizzy.data.get_current_data.app.GetCurrentlyRunningApp
import com.my.kizzy.data.get_current_data.media.GetCurrentPlayingMediaAll
import com.my.kizzy.data.get_current_data.media.RichMediaMetadata
import com.my.kizzy.data.rpc.CommonRpc
import com.my.kizzy.data.rpc.KizzyRPC
import com.my.kizzy.data.rpc.RpcImage
import com.my.kizzy.data.rpc.TemplateKeys
import com.my.kizzy.data.rpc.TemplateProcessor
import com.my.kizzy.data.rpc.Timestamps
import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.model.rpc.RpcButtons
import com.my.kizzy.feature_rpc_base.Constants
import com.my.kizzy.feature_rpc_base.setLargeIcon
import com.my.kizzy.preference.Prefs
import com.my.kizzy.resources.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val TAG = "ExperimentalRPC"

private val IGNORED_PACKAGES = setOf(
    "com.android.systemui"
)

private val IGNORED_PACKAGE_KEYWORDS = listOf(
    "inputmethod",
    "keyboard"
)

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ExperimentalRpc : Service() {

    @Inject
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var kizzyRPC: KizzyRPC

    @Inject
    lateinit var getCurrentPlayingMediaAll: GetCurrentPlayingMediaAll

    @Inject
    lateinit var getCurrentlyRunningApp: GetCurrentlyRunningApp

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var notificationBuilder: Notification.Builder

    private lateinit var mediaSessionManager: MediaSessionManager

    private var currentMediaController: MediaController? = null
    private val mediaControllerCallback = MediaControllerCallback()

    // Settings
    private var useAppsRpc = true
    private var useMediaRpc = true
    private var templateName = TemplateKeys.APP_NAME
    private var templateDetails = TemplateKeys.MEDIA_TITLE
    private var templateState = TemplateKeys.MEDIA_ARTIST
    private var appActivityTypes: Map<String, Int> = emptyMap()
    private var enabledExperimentalApps: List<String> = emptyList()

    // State
    private var latestMediaData: RichMediaMetadata? = null
    private var latestRawMediaMetadata: MediaMetadata? = null
    private var latestAppData: CommonRpc? = null
    private var cachedRpcButtons: RpcButtons? = null
    
    private var appDetectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        log("EXPERIMENTAL RPC SERVICE CREATED")
        
        // Initialize with current value if available
        ForegroundAppDetector.currentForegroundApp?.let { pkg ->
            GetCurrentlyRunningApp.accessibilityServicePackage = pkg
            log("🔄 AccessibilityService (Initial): $pkg")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("EXPERIMENTAL RPC SERVICE STARTING - Action: ${intent?.action}")
        
        if (intent?.action.equals(Constants.ACTION_STOP_SERVICE)) {
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        } else if (intent?.action.equals(Constants.ACTION_RESTART_SERVICE)) {
            stopSelf()
            startService(Intent(this, ExperimentalRpc::class.java))
            return super.onStartCommand(intent, flags, startId)
        }

        val stopIntent = Intent(this, ExperimentalRpc::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val pendingIntent: PendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val restartIntent = Intent(this, ExperimentalRpc::class.java).apply {
            action = Constants.ACTION_RESTART_SERVICE
        }
        val restartPendingIntent: PendingIntent = PendingIntent.getService(
            this, 0, restartIntent, PendingIntent.FLAG_IMMUTABLE
        )

        startForeground(
            Constants.NOTIFICATION_ID,
            notificationBuilder
                .setSmallIcon(R.drawable.ic_dev_rpc)
                .setContentTitle(getString(R.string.service_enabled))
                .addAction(
                    R.drawable.ic_dev_rpc,
                    getString(R.string.restart),
                    restartPendingIntent
                )
                .addAction(R.drawable.ic_dev_rpc, getString(R.string.exit), pendingIntent)
                .build()
        )

        loadSettings()

        // Initialize Media Detection
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSessionManager.addOnActiveSessionsChangedListener(
            ::activeSessionsListener,
            ComponentName(this, NotificationListener::class.java)
        )

        // Check initial media sessions
        if (useMediaRpc) {
            val initialMediaSessions = mediaSessionManager.getActiveSessions(
                ComponentName(this, NotificationListener::class.java)
            )
            activeSessionsListener(initialMediaSessions)
        }

        // Initialize App Detection
        if (useAppsRpc) {
            startAppDetectionCoroutine()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun loadSettings() {
        templateName = Prefs[Prefs.EXPERIMENTAL_RPC_TEMPLATE_NAME, TemplateKeys.APP_NAME]
        templateDetails = Prefs[Prefs.EXPERIMENTAL_RPC_TEMPLATE_DETAILS, TemplateKeys.MEDIA_TITLE]
        templateState = Prefs[Prefs.EXPERIMENTAL_RPC_TEMPLATE_STATE, TemplateKeys.MEDIA_ARTIST]
        useAppsRpc = Prefs[Prefs.EXPERIMENTAL_RPC_USE_APPS_RPC, true]
        useMediaRpc = Prefs[Prefs.EXPERIMENTAL_RPC_USE_MEDIA_RPC, true]
        appActivityTypes = Prefs.getAppActivityTypes()
        enabledExperimentalApps = try {
            Json.decodeFromString(Prefs[Prefs.ENABLED_EXPERIMENTAL_APPS, "[]"])
        } catch (e: Exception) {
            log("Failed to decode enabled apps: ${e.message}")
            emptyList()
        }
        cachedRpcButtons = try {
            Json.decodeFromString(Prefs[Prefs.RPC_BUTTONS_DATA, "{}"])
        } catch (e: Exception) {
            log("Failed to decode RPC buttons: ${e.message}")
            RpcButtons()
        }
        log("Settings loaded: Apps=$useAppsRpc, Media=$useMediaRpc, EnabledApps=${enabledExperimentalApps.size}")
    }

    private fun startAppDetectionCoroutine() {
        appDetectionJob?.cancel()
        appDetectionJob = scope.launch {
            log("App detection coroutine started (Reactive Mode)")
            
            ForegroundAppDetector.currentAppFlow.collectLatest { pkgName ->
                if (!useAppsRpc) return@collectLatest
                
                if (pkgName == null) {
                    log("App flow emitted null")
                    latestAppData = null
                    decideAndPushRpc()
                    return@collectLatest
                }

                // Ignore system apps/keyboard if needed
                val isIgnored = pkgName in IGNORED_PACKAGES ||
                    IGNORED_PACKAGE_KEYWORDS.any { pkgName.contains(it, ignoreCase = true) }

                if (isIgnored) return@collectLatest

                // Sync with helper
                GetCurrentlyRunningApp.accessibilityServicePackage = pkgName
                
                // Logic to check if enabled
                val mediaPackages = if (currentMediaController != null) {
                    listOf(currentMediaController!!.packageName)
                } else emptyList()
                
                val appsToCheck = enabledExperimentalApps.filter { !mediaPackages.contains(it) }
                
                // Direct check instead of calling expensive getCurrentlyRunningApp with UsageStats fallback
                if (appsToCheck.contains(pkgName)) {
                    val appData = getCurrentlyRunningApp.createCommonRpcDirect(pkgName)
                    latestAppData = appData.copy(time = Timestamps(start = System.currentTimeMillis()))
                    log("Detected app (Reactive): ${appData.name} ($pkgName)")
                    decideAndPushRpc()
                } else {
                    // App changed to something not enabled
                    log("App changed to non-enabled: $pkgName")
                    latestAppData = null
                    decideAndPushRpc()
                }
            }
        }
    }

    private fun activeSessionsListener(mediaSessions: List<MediaController>?) {
        if (!useMediaRpc) return
        
        log("Media sessions changed: ${mediaSessions?.size ?: 0} sessions")
        mediaSessions?.forEach { log("  - ${it.packageName}") }
        
        val newController = mediaSessions?.firstOrNull()
        
        if (newController?.packageName != currentMediaController?.packageName) {
            log("Switching media controller: ${currentMediaController?.packageName} -> ${newController?.packageName}")
            currentMediaController?.unregisterCallback(mediaControllerCallback)
            currentMediaController = newController
            currentMediaController?.registerCallback(mediaControllerCallback)
            updateMediaState()
        } else if (newController == null && currentMediaController != null) {
            log("Clearing media controller")
            currentMediaController?.unregisterCallback(mediaControllerCallback)
            currentMediaController = null
            updateMediaState()
        }
    }

    private var updateMediaJob: Job? = null
    
    private fun updateMediaState() {
        updateMediaJob?.cancel()
        updateMediaJob = scope.launch {
            delay(300)
            
            val controller = currentMediaController
            if (controller != null) {
                val metadata = controller.metadata
                val playbackState = controller.playbackState?.state
                
                log("updateMediaState: pkg=${controller.packageName}, hasMetadata=${metadata != null}, state=$playbackState")
                
                if (metadata != null) {
                    // Check if app is in enabled list BEFORE processing
                    if (!enabledExperimentalApps.contains(controller.packageName)) {
                        log("Media: App ${controller.packageName} not in enabled list (${enabledExperimentalApps.size} apps enabled), skipping")
                        latestMediaData = null
                        latestRawMediaMetadata = null
                        decideAndPushRpc()
                        return@launch
                    }
                    
                    log("Calling getCurrentPlayingMediaAll with pkg=${controller.packageName}, enabledApps=$enabledExperimentalApps")
                    val richMediaData = getCurrentPlayingMediaAll(
                        packageName = controller.packageName,
                        enabledApps = enabledExperimentalApps
                    )
                    log("Result: appName=${richMediaData.appName}, pkg=${richMediaData.packageName}")
                    
                    if (richMediaData.appName != null && (!richMediaData.title.isNullOrBlank() || !richMediaData.artist.isNullOrBlank())) {
                        latestMediaData = richMediaData
                        latestRawMediaMetadata = metadata
                        
                        val stateStr = when(richMediaData.playbackState) {
                            PlaybackState.STATE_PLAYING -> "PLAYING"
                            PlaybackState.STATE_PAUSED -> "PAUSED"
                            PlaybackState.STATE_STOPPED -> "STOPPED"
                            else -> "OTHER (${richMediaData.playbackState})"
                        }
                        log("Media Updated: ${richMediaData.title ?: "(sem título)"} - ${richMediaData.artist ?: "(sem artista)"} - State: $stateStr - CoverArt: ${richMediaData.coverArt != null}")
                    } else {
                        log("Media: Invalid data (appName=${richMediaData.appName}, title=${richMediaData.title}, artist=${richMediaData.artist})")
                        return@launch
                    }
                } else {
                    log("Media: Metadata is null, waiting...")
                    return@launch
                }
            } else {
                latestMediaData = null
                latestRawMediaMetadata = null
                log("Media: Controller is null")
            }
            decideAndPushRpc()
        }
    }

    private inner class MediaControllerCallback : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            updateMediaState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            updateMediaState()
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            updateMediaState()
        }
    }

    private suspend fun decideAndPushRpc() {
        val media = latestMediaData
        val app = latestAppData
        
        log("decideAndPushRpc called: media=${media?.appName}, app=${app?.name}")

        // 1. Determine if Media should be shown
        var showMedia = false
        if (useMediaRpc && media != null && media.appName != null) {
            val isPlaying = media.playbackState == PlaybackState.STATE_PLAYING
            val isBuffering = media.playbackState == PlaybackState.STATE_BUFFERING
            showMedia = isPlaying || isBuffering
            log("Media check: state=${media.playbackState}, isPlaying=$isPlaying, isBuffering=$isBuffering, showMedia=$showMedia")
        }

        // 2. Decide winner
        if (showMedia) {
            log("Decision: Showing MEDIA (playing/buffering)")
            updatePresence(richMediaInfo = media, rawMediaMetadata = latestRawMediaMetadata)
        } else if (useAppsRpc && app != null && app.packageName.isNotEmpty()) {
            log("Decision: Showing APP - ${app.name} (${app.packageName})")
            updatePresence(appInfo = app)
        } else {
            log("Decision: CLEAR (No active media or app)")
            updatePresence(null, null, null)
        }
    }

    private suspend fun updatePresence(
        appInfo: CommonRpc? = null,
        richMediaInfo: RichMediaMetadata? = null,
        rawMediaMetadata: MediaMetadata? = null,
    ) {
        log("updatePresence called: appInfo=${appInfo?.name}, richMediaInfo=${richMediaInfo?.appName}")
        val rpcButtons = cachedRpcButtons ?: RpcButtons()

        var finalName: String?
        var finalDetails: String?
        var finalState: String?
        var finalLargeImage: RpcImage?
        var finalSmallImage: RpcImage?
        var finalLargeText: String?
        var finalSmallText: String?
        var finalTimestamps: Timestamps?
        var effectivePackageName: String?

        val processor = TemplateProcessor(
            mediaMetadata = rawMediaMetadata,
            mediaPlayerAppName = richMediaInfo?.appName,
            mediaPlayerPackageName = richMediaInfo?.packageName,
            detectedAppInfo = appInfo
        )

        if (richMediaInfo != null) {
            // --- MEDIA MODE ---
            effectivePackageName = richMediaInfo.packageName
            
            val processedName = processor.process(templateName)
            val processedDetails = processor.process(templateDetails)
            val processedState = processor.process(templateState)
            
            finalName = processedName?.takeIf { it.isNotBlank() } ?: richMediaInfo.appName
            finalDetails = processedDetails?.takeIf { it.isNotBlank() } ?: richMediaInfo.title ?: richMediaInfo.artist
            finalState = processedState?.takeIf { it.isNotBlank() } ?: richMediaInfo.artist ?: richMediaInfo.album

            finalLargeImage = when {
                Prefs[Prefs.EXPERIMENTAL_RPC_SHOW_COVER_ART, true] -> richMediaInfo.coverArt
                Prefs[Prefs.EXPERIMENTAL_RPC_SHOW_APP_ICON, false] -> richMediaInfo.appIcon
                else -> null
            }

            finalSmallImage = when {
                Prefs[Prefs.EXPERIMENTAL_RPC_SHOW_PLAYBACK_STATE, true] -> richMediaInfo.playbackStateIcon
                Prefs[Prefs.EXPERIMENTAL_RPC_SHOW_APP_ICON, false] && finalLargeImage != richMediaInfo.appIcon -> richMediaInfo.appIcon
                else -> null
            }

            finalLargeText = richMediaInfo.album
            finalSmallText = if (finalSmallImage == richMediaInfo.appIcon) richMediaInfo.appName else null
            finalTimestamps = if (Prefs[Prefs.EXPERIMENTAL_RPC_ENABLE_TIMESTAMPS, true]) richMediaInfo.timestamps else null

        } else if (appInfo != null) {
            // --- APP MODE ---
            effectivePackageName = appInfo.packageName
            
            val processedName = processor.process(templateName)
            val processedDetails = processor.process(templateDetails)
            val processedState = processor.process(templateState)
            
            finalName = if (processedName.isNullOrEmpty()) appInfo.name else processedName
            finalDetails = if (processedDetails.isNullOrEmpty()) appInfo.details else processedDetails
            finalState = if (processedState.isNullOrEmpty()) appInfo.state else processedState

            finalLargeImage = appInfo.largeImage
            finalSmallImage = appInfo.smallImage
            finalLargeText = appInfo.largeText
            finalSmallText = appInfo.smallText
            finalTimestamps = if (Prefs[Prefs.EXPERIMENTAL_RPC_ENABLE_TIMESTAMPS, true]) appInfo.time else null
        } else {
            // --- CLEAR ---
            log("updatePresence: CLEAR mode")
            if (kizzyRPC.isRpcRunning()) {
                kizzyRPC.closeRPC()
                updateNotification(getString(R.string.idling_notification))
            }
            return
        }

        log("updatePresence: finalName=$finalName, finalDetails=$finalDetails, finalState=$finalState")
        
        if (finalName.isNullOrBlank()) {
            log("updatePresence: finalName is empty, using fallback")
            finalName = richMediaInfo?.appName ?: appInfo?.name ?: "Unknown"
        }
        
        val rpcDataIsEmpty = finalName.isBlank() && finalDetails.isNullOrBlank() && finalState.isNullOrBlank()

        if (rpcDataIsEmpty) {
            log("updatePresence: RPC data is empty, clearing")
            if (kizzyRPC.isRpcRunning()) {
                kizzyRPC.closeRPC()
                updateNotification(getString(R.string.idling_notification))
            }
            return
        }

        // Update RPC
        log("updatePresence: Sending RPC to Discord (running=${kizzyRPC.isRpcRunning()})")
        if (kizzyRPC.isRpcRunning()) {
            kizzyRPC.updateRPC(
                commonRpc = CommonRpc(
                    name = finalName ?: "",
                    type = appActivityTypes[effectivePackageName] ?: 0,
                    details = finalDetails,
                    state = finalState,
                    largeImage = finalLargeImage,
                    smallImage = finalSmallImage,
                    largeText = finalLargeText,
                    smallText = finalSmallText,
                    time = finalTimestamps,
                    packageName = effectivePackageName ?: ""
                ),
                enableTimestamps = Prefs[Prefs.EXPERIMENTAL_RPC_ENABLE_TIMESTAMPS, true]
            )
        } else {
            try {
                kizzyRPC.apply {
                    setName(finalName)
                    setType(appActivityTypes[effectivePackageName] ?: 0)
                    setStatus(Prefs[Prefs.CUSTOM_ACTIVITY_STATUS, "dnd"])
                    setDetails(finalDetails)
                    setState(finalState)
                    setStartTimestamps(finalTimestamps?.start)
                    setStopTimestamps(finalTimestamps?.end)
                    setLargeImage(finalLargeImage, finalLargeText)
                    setSmallImage(finalSmallImage, finalSmallText)
                    if (Prefs[Prefs.USE_RPC_BUTTONS, false]) {
                        with(rpcButtons) {
                            setButton1(button1.takeIf { it.isNotEmpty() })
                            setButton1URL(button1Url.takeIf { it.isNotEmpty() })
                            setButton2(button2.takeIf { it.isNotEmpty() })
                            setButton2URL(button2Url.takeIf { it.isNotEmpty() })
                        }
                    }
                    build()
                }
                log("updatePresence: RPC build() called")
            } catch (e: Exception) {
                log("updatePresence: ERROR building RPC: ${e.message}", isError = true)
            }
        }

        // Update Notification
        val notifTitle = finalName.takeIf { !it.isNullOrEmpty() } ?: getString(R.string.app_name)
        val notifText = finalDetails ?: finalState
        
        notificationManager.notify(
            Constants.NOTIFICATION_ID, notificationBuilder
                .setContentTitle(notifTitle)
                .setContentText(notifText)
                .setLargeIcon(rpcImage = finalLargeImage, context = this@ExperimentalRpc)
                .build()
        )
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(
            Constants.NOTIFICATION_ID, notificationBuilder
                .setContentTitle(getString(R.string.service_enabled))
                .setContentText(text)
                .setLargeIcon(null as? android.graphics.Bitmap)
                .build()
        )
    }

    private fun log(message: String, isError: Boolean = false) {
        if (isError) {
            android.util.Log.e(TAG, message)
            logger.e(TAG, message)
        } else {
            android.util.Log.d(TAG, message)
            logger.i(TAG, message)
        }
    }

    override fun onDestroy() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(::activeSessionsListener)
        currentMediaController?.unregisterCallback(mediaControllerCallback)
        appDetectionJob?.cancel()
        // Launch closeRPC in scope before canceling to avoid blocking main thread
        scope.launch {
            try {
                kizzyRPC.closeRPC()
            } catch (e: Exception) {
                log("Error closing RPC: ${e.message}", isError = true)
            }
        }.invokeOnCompletion {
            scope.cancel()
        }
        GetCurrentlyRunningApp.accessibilityServicePackage = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}