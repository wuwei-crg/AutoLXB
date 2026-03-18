package com.example.lxb_ignition.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.lxb_ignition.MainActivity
import com.example.lxb_ignition.R

/**
 * Foreground task runtime indicator for Cortex execution.
 * - Shows clear status in notification area.
 * - Holds a partial wakelock while running.
 */
class TaskRuntimeService : Service() {

    companion object {
        const val ACTION_START = "com.example.lxb_ignition.action.TASK_RUNTIME_START"
        const val ACTION_UPDATE = "com.example.lxb_ignition.action.TASK_RUNTIME_UPDATE"
        const val ACTION_STOP = "com.example.lxb_ignition.action.TASK_RUNTIME_STOP"

        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_DETAIL = "detail"

        private const val CHANNEL_ID = "lxb_task_runtime"
        private const val CHANNEL_NAME = "LXB Task Runtime"
        private const val NOTIFICATION_ID = 1002
        private const val WAKELOCK_TAG = "LXB::TaskRuntimeWakeLock"
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private var currentTaskId: String = ""
    private var currentPhase: String = "IDLE"
    private var currentDetail: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentTaskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                currentPhase = intent.getStringExtra(EXTRA_PHASE).orEmpty().ifEmpty { "RUNNING" }
                currentDetail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                startOrUpdateForeground()
            }

            ACTION_UPDATE -> {
                val tid = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
                if (tid.isNotEmpty()) {
                    currentTaskId = tid
                }
                val phase = intent.getStringExtra(EXTRA_PHASE).orEmpty()
                if (phase.isNotEmpty()) {
                    currentPhase = phase
                }
                currentDetail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                startOrUpdateForeground()
            }

            ACTION_STOP -> {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startOrUpdateForeground()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startOrUpdateForeground() {
        val notification = buildNotification(currentTaskId, currentPhase, currentDetail)
        if (!started) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            acquireWakeLock()
            started = true
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(taskId: String, phase: String, detail: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (phase.uppercase()) {
            "SETTLING" -> "LXB Running - Settling UI"
            "VISION_LLM" -> "LXB Running - Calling LLM/VLM"
            "ROUTING" -> "LXB Running - Routing"
            "APP_RESOLVE" -> "LXB Running - Resolving App"
            "ROUTE_PLAN" -> "LXB Running - Planning Route"
            "FAILED" -> "LXB Task Failed"
            "DONE" -> "LXB Task Done"
            "CANCELLING" -> "LXB Task Cancelling"
            else -> "LXB Running"
        }

        val content = buildString {
            if (taskId.isNotEmpty()) append("task=").append(taskId.take(8)).append("... ")
            append("phase=").append(phase.ifEmpty { "RUNNING" })
            if (detail.isNotEmpty()) append(" | ").append(detail)
        }.ifEmpty { "LXB task is running." }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Shows LXB task runtime status."
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }
}

