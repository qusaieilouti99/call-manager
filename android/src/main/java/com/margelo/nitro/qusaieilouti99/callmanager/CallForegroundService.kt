package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class CallForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "call_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "CallForegroundService"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        val callId = intent?.getStringExtra("callId")
        val callType = intent?.getStringExtra("callType")
        val displayName = intent?.getStringExtra("displayName")
        val state = intent?.getStringExtra("state")

        val notification = if (callId != null && callType != null && displayName != null && state != null) {
            buildEnhancedNotification(callId, callType, displayName, state)
        } else {
            buildBasicNotification()
        }

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildBasicNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Service")
            .setContentText("Call service is running...")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun buildEnhancedNotification(callId: String, callType: String, displayName: String, state: String): Notification {
        Log.d(TAG, "Building notification for callId: $callId, state: $state")

        val endCallIntent = Intent(this, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.END_CALL"
            putExtra("callId", callId)
        }
        val endCallPendingIntent = PendingIntent.getBroadcast(
            this, 100, endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHeld = state == "HELD"
        val holdAction = if (isHeld) "UNHOLD_CALL" else "HOLD_CALL"
        val holdText = if (isHeld) "Resume" else "Hold"

        val holdIntent = Intent(this, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.$holdAction"
            putExtra("callId", callId)
        }
        val holdPendingIntent = PendingIntent.getBroadcast(
            this, 101, holdIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (state) {
            "ACTIVE" -> displayName
            "HELD" -> "$displayName (on hold)"
            "DIALING" -> "Calling $displayName..."
            "INCOMING" -> "Incoming call from $displayName"
            else -> displayName
        }

        val titleText = when (state) {
            "ACTIVE" -> "$callType Call Active"
            "HELD" -> "$callType Call Held"
            "DIALING" -> "Outgoing $callType Call"
            "INCOMING" -> "Incoming $callType Call"
            else -> "$callType Call"
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(titleText)
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Add action buttons for ACTIVE and HELD calls
        if (state == "ACTIVE" || state == "HELD") {
            notificationBuilder
                .addAction(
                    if (isHeld) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    holdText,
                    holdPendingIntent
                )
                .addAction(
                    android.R.drawable.sym_call_outgoing,
                    "End Call",
                    endCallPendingIntent
                )
        } else if (state == "DIALING") {
            notificationBuilder.addAction(
                android.R.drawable.sym_call_outgoing,
                "End Call",
                endCallPendingIntent
            )
        }

        return notificationBuilder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Foreground Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "Notification for ongoing calls"
            channel.setShowBadge(false)
            channel.enableLights(false)
            channel.enableVibration(false)

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val removed = rootIntent?.component?.className
        Log.d(TAG, "onTaskRemoved: $removed")

        // Only terminate if main app was removed, not CallActivity
        if (removed != CallActivity::class.java.name) {
            Log.d(TAG, "Main app task removed - terminating")
            CallEngine.onApplicationTerminate()

            // Force kill the process (nuclear option)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        stopForeground(true)

        // SIMPLIFIED: Don't call onApplicationTerminate here
        // Only onTaskRemoved should trigger app termination
    }
}
