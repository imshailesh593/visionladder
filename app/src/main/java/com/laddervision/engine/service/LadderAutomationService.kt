package com.laddervision.engine.service

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
                startForegroundWithNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData != null) {
                    startCaptureWithProjection(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopAutomation()
            }
            ACTION_KILL -> {
                emergencyKill()
            }
            ACTION_ONE_SHOT -> {
                triggerOneShot()
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
            .setContentTitle("Ladder Vision Engine Active")
            .setContentText("Sub-20ms frame ingestion & gesture injection pipeline running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "KILL SWITCH", killIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
        stopSelf()
    }

    fun stopAutomation() {
        captureEngine.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine.stopCapture()
        serviceScope.cancel()
        if (instance == this) {
            instance = null
        }
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
