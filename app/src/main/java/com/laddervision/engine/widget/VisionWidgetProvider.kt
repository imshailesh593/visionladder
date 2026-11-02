package com.laddervision.engine.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.RemoteViews
import com.laddervision.engine.R
import com.laddervision.engine.overlay.CalibrationHudService
import com.laddervision.engine.service.LadderAutomationService
import com.laddervision.engine.touch.LadderTouchService
import com.laddervision.engine.ui.MainActivity

class VisionWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "VisionWidgetProvider"
        const val ACTION_TOGGLE = "com.laddervision.action.TOGGLE_WIDGET_STATE"

        fun isAppRunning(): Boolean {
            val autoRunning = LadderAutomationService.instance != null
            val hookArmed = LadderTouchService.isHookArmedState.value
            return autoRunning || hookArmed
        }

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val componentName = ComponentName(context, VisionWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widgets", e)
            }
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val isRunning = isAppRunning()
            val views = RemoteViews(context.packageName, R.layout.widget_vision_switch)

            if (isRunning) {
                views.setTextViewText(R.id.widget_status_text, "● ACTIVE (ON)")
                views.setTextColor(R.id.widget_status_text, 0xFF10B981.toInt())
                views.setTextViewText(R.id.widget_toggle_btn, "ON")
                views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.widget_switch_btn_on)
            } else {
                views.setTextViewText(R.id.widget_status_text, "○ STANDBY (OFF)")
                views.setTextColor(R.id.widget_status_text, 0xFF94A3B8.toInt())
                views.setTextViewText(R.id.widget_toggle_btn, "OFF")
                views.setInt(R.id.widget_toggle_btn, "setBackgroundResource", R.drawable.widget_switch_btn_off)
            }

            // Click header to open app
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header_container, openAppPendingIntent)

            // Click switch button to toggle state
            val toggleIntent = Intent(context, VisionWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val currentlyRunning = isAppRunning()

            // Vibrate haptic click
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(45)
                }
            } catch (_: Exception) {}

            if (currentlyRunning) {
                // Turn OFF
                context.startService(Intent(context, LadderAutomationService::class.java).apply {
                    action = LadderAutomationService.ACTION_STOP
                })
                context.sendBroadcast(Intent(LadderTouchService.ACTION_DISABLE_TOUCH_HOOK))
                LadderTouchService.instance?.disarmTouchHook()
                context.stopService(Intent(context, CalibrationHudService::class.java))
                Log.i(TAG, "Turned OFF via Home Screen Switch Widget")
            } else {
                // Turn ON
                context.sendBroadcast(Intent(LadderTouchService.ACTION_ENABLE_TOUCH_HOOK))
                LadderTouchService.instance?.armTouchHook()

                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("EXTRA_AUTO_START", true)
                }
                context.startActivity(launchIntent)
                MainActivity.autoStartTrigger.value = true
                Log.i(TAG, "Turned ON via Home Screen Switch Widget")
            }

            updateAllWidgets(context)
        }
    }
}
