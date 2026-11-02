package com.laddervision.engine.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.laddervision.engine.R
import com.laddervision.engine.capture.FrameCaptureEngine
import com.laddervision.engine.model.DetectionTelemetry
import com.laddervision.engine.model.LadderConfig
import com.laddervision.engine.touch.LadderTouchService
import com.laddervision.engine.ui.MainActivity
import com.laddervision.engine.vision.NativeVisionCore
import com.laddervision.engine.widget.VisionWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LadderAutomationService : Service() {

    companion object {
        private const val TAG = "LadderAutomationService"
        private const val CHANNEL_ID = "ladder_vision_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.laddervision.action.START"
        const val ACTION_STOP = "com.laddervision.action.STOP"
        const val ACTION_KILL = "com.laddervision.action.KILL"
        const val ACTION_ONE_SHOT = "com.laddervision.action.ONE_SHOT"
        const val ACTION_UPDATE_CONFIG = "com.laddervision.action.UPDATE_CONFIG"
        const val ACTION_TRIGGER_STRIKE = "com.laddervision.action.TRIGGER_STRIKE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var instance: LadderAutomationService? = null
            private set

        private val _engineTelemetry = MutableStateFlow(DetectionTelemetry())
        val engineTelemetry: StateFlow<DetectionTelemetry> = _engineTelemetry.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var visionCore: NativeVisionCore
    private lateinit var captureEngine: FrameCaptureEngine
    private lateinit var config: LadderConfig

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = LadderConfig.load(this)
        visionCore = NativeVisionCore()
        captureEngine = FrameCaptureEngine(this, visionCore)

        createNotificationChannel()

        serviceScope.launch {
            captureEngine.telemetry.collect { tele ->
                _engineTelemetry.value = tele
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    try {
                        startForegroundWithNotification()
                        startCaptureWithProjection(resultCode, resultData)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MediaProjection capture", e)
                        stopAutomation()
                    }
                } else {
                    Log.w(TAG, "Cannot start 50Hz engine without valid MediaProjection consent (resultCode=$resultCode).")
                    stopAutomation()
                }
            }
            ACTION_STOP -> {
                stopAutomation()
            }
            ACTION_KILL -> {
                emergencyKill()
            }
            ACTION_ONE_SHOT, ACTION_TRIGGER_STRIKE -> {
                triggerOneShot()
            }
            ACTION_UPDATE_CONFIG -> {
                val newConfig = config.copy(
                    ladderLeft = intent.getIntExtra("ladderLeft", config.ladderLeft),
                    ladderTop = intent.getIntExtra("ladderTop", config.ladderTop),
                    ladderWidth = intent.getIntExtra("ladderWidth", config.ladderWidth),
                    ladderHeight = intent.getIntExtra("ladderHeight", config.ladderHeight),
                    rowHeight = intent.getIntExtra("rowHeight", config.rowHeight),
                    rowCount = intent.getIntExtra("rowCount", config.rowCount),
                    backColX = intent.getIntExtra("backColX", config.backColX),
                    layColX = intent.getIntExtra("layColX", config.layColX),
                    sampleBoxWidth = intent.getIntExtra("sampleBoxWidth", config.sampleBoxWidth),
                    sampleBoxHeight = intent.getIntExtra("sampleBoxHeight", config.sampleBoxHeight),
                    autoTradeEnabled = intent.getBooleanExtra("autoTradeEnabled", config.autoTradeEnabled),
                    cooldownMs = intent.getLongExtra("cooldownMs", config.cooldownMs)
                )
                updateConfig(newConfig)
                Log.i(TAG, "Config updated via ACTION_UPDATE_CONFIG: top=${newConfig.ladderTop}, rowH=${newConfig.rowHeight}, backX=${newConfig.backColX}, layX=${newConfig.layColX}, auto=${newConfig.autoTradeEnabled}")
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val killIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LadderAutomationService::class.java).apply { this.action = ACTION_KILL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vision Active")
            .setContentText("Sub-20ms frame ingestion & gesture injection pipeline running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "KILL SWITCH", killIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service start exception, falling back without type", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed fallback startForeground", e2)
            }
        }
    }

    private fun startCaptureWithProjection(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = projectionManager.getMediaProjection(resultCode, data) ?: return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        captureEngine.startCapture(
            projection = projection,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi
        )
        VisionWidgetProvider.updateAllWidgets(this)
    }

    fun updateConfig(newConfig: LadderConfig) {
        this.config = newConfig
        this.config.save(this)
        captureEngine.updateConfig(newConfig)
    }

    fun triggerOneShot() {
        captureEngine.triggerOneShotNow()
    }

    fun emergencyKill() {
        Log.w(TAG, "EMERGENCY KILL SWITCH INVOKED (< 5ms termination)")
        LadderTouchService.instance?.disarmImmediately()
        captureEngine.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        VisionWidgetProvider.updateAllWidgets(this)
        stopSelf()
    }

    fun stopAutomation() {
        captureEngine.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        VisionWidgetProvider.updateAllWidgets(this)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine.stopCapture()
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
        VisionWidgetProvider.updateAllWidgets(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_desc)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
