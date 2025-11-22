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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

private const val TAG = "ExperimentalRPC"

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
    
    private var appDetectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        log("EXPERIMENTAL RPC SERVICE CREATED")
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
        } catch (_: Exception) {
            emptyList()
        }
        log("Settings loaded: Apps=$useAppsRpc, Media=$useMediaRpc, EnabledApps=${enabledExperimentalApps.size}")
    }

    private fun startAppDetectionCoroutine() {
        appDetectionJob?.cancel()
        appDetectionJob = scope.launch {
            log("App detection coroutine started")
            var currentPackageName = ""
            
            while (isActive) {
                if (!useAppsRpc) break
                
                // Check for media sessions
                val currentSessions = try {
                    mediaSessionManager.getActiveSessions(
                        ComponentName(this@ExperimentalRpc, NotificationListener::class.java)
                    )
                } catch (e: Exception) {
                    emptyList()
                }
                
                val enabledMediaSession = currentSessions.firstOrNull {
                    enabledExperimentalApps.contains(it.packageName)
                }
                
                if (useMediaRpc && enabledMediaSession != null) {
                    log("Found media session: ${enabledMediaSession.packageName}")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        activeSessionsListener(listOf(enabledMediaSession))
                    }
                    break
                }
                
                // Check for apps
                val currentApp = getCurrentlyRunningApp(filterList = enabledExperimentalApps)
                
                if (currentApp.name.isNotEmpty() && enabledExperimentalApps.contains(currentApp.packageName)) {
                    if (currentApp.packageName != currentPackageName) {
                        currentPackageName = currentApp.packageName
                        latestAppData = currentApp.copy(time = Timestamps(start = System.currentTimeMillis()))
                        log("Detected app: ${currentApp.name}")
                        decideAndPushRpc()
                    }
                } else {
                    if (currentPackageName.isNotEmpty()) {
                        currentPackageName = ""
                        latestAppData = null
                        decideAndPushRpc()
                    }
                }
                
                delay(200)
            }
        }
    }

    private fun activeSessionsListener(mediaSessions: List<MediaController>?) {
        if (!useMediaRpc) return
        
        log("Media sessions changed: ${mediaSessions?.size ?: 0}")
        
        currentMediaController?.unregisterCallback(mediaControllerCallback)
        currentMediaController = null
        
        if (mediaSessions?.isNotEmpty() == true) {
            currentMediaController = mediaSessions.firstOrNull {
                enabledExperimentalApps.contains(it.packageName)
            }
            currentMediaController?.registerCallback(mediaControllerCallback)
        }
        
        updateMediaState()
    }

    private fun updateMediaState() {
        scope.launch {
            // Small delay to allow metadata to propagate
            delay(100)
            
            if (currentMediaController != null) {
                val richMediaData = getCurrentPlayingMediaAll(enabledApps = enabledExperimentalApps)
                if (richMediaData.appName != null) {
                    latestMediaData = richMediaData
                    latestRawMediaMetadata = currentMediaController?.metadata
                    log("Media Updated: ${richMediaData.title} - ${richMediaData.playbackState}")
                } else {
                    latestMediaData = null
                    latestRawMediaMetadata = null
                }
            } else {
                latestMediaData = null
                latestRawMediaMetadata = null
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

        // 1. Determine if Media should be shown
        var showMedia = false
        if (useMediaRpc && media != null && media.appName != null) {
            val hideOnPause = Prefs[Prefs.EXPERIMENTAL_RPC_HIDE_ON_PAUSE, false]
            val isPlaying = media.playbackState == PlaybackState.STATE_PLAYING
            
            showMedia = if (hideOnPause) isPlaying else true
        }

        // 2. Decide winner
        if (showMedia) {
            log("Decision: Showing MEDIA")
            updatePresence(richMediaInfo = media, rawMediaMetadata = latestRawMediaMetadata)
        } else if (useAppsRpc && app != null && app.packageName.isNotEmpty()) {
            log("Decision: Showing APP")
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
        val rpcButtonsString = Prefs[Prefs.RPC_BUTTONS_DATA, "{}"]
        val rpcButtons = Json.decodeFromString<RpcButtons>(rpcButtonsString)

        val finalName: String?
        val finalDetails: String?
        val finalState: String?
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
            
            finalName = processor.process(templateName) ?: richMediaInfo.appName
            finalDetails = processor.process(templateDetails) ?: richMediaInfo.title
            finalState = processor.process(templateState) ?: richMediaInfo.artist

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
            
            finalName = processor.process(templateName) ?: appInfo.name
            finalDetails = processor.process(templateDetails) ?: appInfo.details
            finalState = processor.process(templateState) ?: appInfo.state

            finalLargeImage = appInfo.largeImage
            finalSmallImage = appInfo.smallImage
            finalLargeText = appInfo.largeText
            finalSmallText = appInfo.smallText
            finalTimestamps = if (Prefs[Prefs.EXPERIMENTAL_RPC_ENABLE_TIMESTAMPS, true]) appInfo.time else null
        } else {
            // --- CLEAR ---
            if (kizzyRPC.isRpcRunning()) {
                kizzyRPC.closeRPC()
                updateNotification(getString(R.string.idling_notification))
            }
            return
        }

        val rpcDataIsEmpty = finalName.isNullOrEmpty() && finalDetails.isNullOrEmpty() && finalState.isNullOrEmpty()

        if (rpcDataIsEmpty) {
            if (kizzyRPC.isRpcRunning()) {
                kizzyRPC.closeRPC()
                updateNotification(getString(R.string.idling_notification))
            }
            return
        }

        // Update RPC
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

    private fun log(message: String) {
        android.util.Log.e(TAG, message)
        logger.i(TAG, message)
    }

    override fun onDestroy() {
        mediaSessionManager.removeOnActiveSessionsChangedListener(::activeSessionsListener)
        currentMediaController?.unregisterCallback(mediaControllerCallback)
        appDetectionJob?.cancel()
        scope.cancel()
        kizzyRPC.closeRPC()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}