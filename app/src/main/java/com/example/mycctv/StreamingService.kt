package com.example.mycctv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import fi.iki.elonen.NanoHTTPD

class StreamingService : Service(), ConnectChecker {

    private val TAG = "StreamingService"
    private val binder = LocalBinder()

    private var rtspServerCamera2: RtspServerCamera2? = null
    private var onvifServer: OnvifServer? = null
    private var currentPort = DEFAULT_PORT

    // Kept so your Activity still compiles. Not used by the library.
    private var username = ""
    private var password = ""

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ---------------------------------------------------------------- ConnectChecker

    override fun onConnectionStarted(url: String) { Log.d(TAG, "onConnectionStarted: $url") }
    override fun onConnectionSuccess() { Log.d(TAG, "onConnectionSuccess") }
    override fun onConnectionFailed(reason: String) { Log.e(TAG, "onConnectionFailed: $reason") }
    override fun onNewBitrate(bitrate: Long) { /* too noisy to log */ }
    override fun onDisconnect() { Log.d(TAG, "onDisconnect") }
    override fun onAuthError() { Log.e(TAG, "onAuthError") }
    override fun onAuthSuccess() { Log.d(TAG, "onAuthSuccess") }

    // ---------------------------------------------------------------- Service lifecycle

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopStreaming()
        stopOnvifServer()
        stopPreview()
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Public API

    fun setCredentials(user: String, pass: String) {
        username = user
        password = pass
    }

    /**
     * The RTSP port is always DEFAULT_PORT (8554). The `port` parameter is ignored on purpose,
     * so the Activity can never put RTSP on the same port as ONVIF.
     */
    fun startPreview(openGlView: OpenGlView, @Suppress("UNUSED_PARAMETER") port: Int = DEFAULT_PORT) {
        val server = rtspServerCamera2 ?: RtspServerCamera2(openGlView, this, DEFAULT_PORT).also {
            currentPort = DEFAULT_PORT
            rtspServerCamera2 = it
        }

        if (server.isStreaming) {
            try {
                server.replaceView(openGlView)
            } catch (e: Exception) {
                Log.e(TAG, "replaceView(view) failed: ${e.message}", e)
            }
        }

        if (!server.isOnPreview) {
            try {
                server.startPreview()
            } catch (e: Exception) {
                Log.e(TAG, "startPreview failed: ${e.message}", e)
            }
        }
    }

    /** Call from the Activity's onStop() while streaming. */
    fun switchToBackground() {
        val server = rtspServerCamera2 ?: return
        if (!server.isStreaming) return
        try {
            server.replaceView(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "replaceView(context) failed: ${e.message}", e)
        }
    }

    fun startStreaming(port: Int = currentPort) {
        val server = rtspServerCamera2
        if (server == null) {
            Log.e(TAG, "startStreaming: call startPreview() first")
            return
        }
        if (server.isStreaming) {
            Log.d(TAG, "Already streaming")
            return
        }
        if (port != currentPort) {
            Log.w(TAG, "Port $port ignored: RTSP always uses $currentPort")
        }

        // Video only: width, height, fps, bitrate (bits/s), iFrameInterval (s), rotation
        val videoPrepared = server.prepareVideo(1280, 720, 25, 1_200_000, 1, 0)
        if (!videoPrepared) {
            Log.e(TAG, "prepareVideo failed: this phone could not start the H.264 encoder")
            return
        }

        acquireLocks()
        server.startStream()
        Log.e(TAG, "RTSP server started on port $currentPort")   // Log.e on purpose: always visible
        startOnvifServer()
    }

    fun stopStreaming() {
        val server = rtspServerCamera2 ?: return
        if (server.isStreaming) {
            server.stopStream()
        }
        stopOnvifServer()
        releaseLocks()
    }

    fun stopPreview() {
        val server = rtspServerCamera2 ?: return
        if (server.isStreaming) {
            Log.d(TAG, "stopPreview ignored: still streaming (use switchToBackground())")
            return
        }
        if (server.isOnPreview) server.stopPreview()
        rtspServerCamera2 = null
    }

    fun isStreaming(): Boolean = rtspServerCamera2?.isStreaming ?: false

    fun getPort(): Int = currentPort

    fun getRtspUrl(): String {
        val ip = NetworkUtils.getIPAddress(true)
        return "rtsp://$ip:$currentPort/live"
    }

    // ---------------------------------------------------------------- ONVIF

    private fun startOnvifServer() {
        Log.e(TAG, "startOnvifServer() called, ONVIF_PORT=$ONVIF_PORT, RTSP port=$currentPort")
        if (ONVIF_PORT == currentPort) {
            Log.e(TAG, "ONVIF and RTSP ports clash: not starting ONVIF")
            return
        }
        if (onvifServer != null) return
        try {
            val server = OnvifServer(port = ONVIF_PORT, rtspPort = currentPort, rtspPath = "/live")
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            onvifServer = server
            Log.e(TAG, "ONVIF server LISTENING on port $ONVIF_PORT")   // Log.e on purpose
        } catch (e: Exception) {
            Log.e(TAG, "ONVIF start failed: ${e.message}", e)
        }
    }

    private fun stopOnvifServer() {
        onvifServer?.stop()
        onvifServer = null
    }

    // ---------------------------------------------------------------- Locks

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mycctv:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mycctv:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    // ---------------------------------------------------------------- Notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IP Camera")
            .setContentText("Camera is streaming live")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun startAsForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "StreamingServiceChannel"
        const val DEFAULT_PORT = 8554   // RTSP
        const val ONVIF_PORT = 8899     // ONVIF (HTTP)
    }
}