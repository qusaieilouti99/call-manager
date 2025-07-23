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
import org.json.JSONObject

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

        // Check if we have call info in the intent
        val callId = intent?.getStringExtra("callId")
        val callData = intent?.getStringExtra("callData")
        val state = intent?.getStringExtra("state")

        val notification = if (callId != null && callData != null && state != null) {
            Log.d(TAG, "Building enhanced notification with call info: $callId")
            buildEnhancedNotification(callId, callData, state)
        } else {
            Log.d(TAG, "Building basic notification - no call info available")
            buildBasicNotification()
        }

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Service onBind")
        return null
    }

    private fun buildBasicNotification(): Notification {
        Log.d(TAG, "Building basic foreground notification.")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Call Service")
            .setContentText("Call service is running...")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    private fun buildEnhancedNotification(callId: String, callData: String, state: String): Notification {
        Log.d(TAG, "Building enhanced foreground notification for callId: $callId, state: $state")

        val callerName = try {
            JSONObject(callData).optString("name", "Unknown Caller")
        } catch (e: Exception) {
            "Unknown Caller"
        }

        val callType = try {
            JSONObject(callData).optString("callType", "Audio")
        } catch (e: Exception) {
            "Audio"
        }

        // Create end call action
        val endCallIntent = Intent(this, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.END_CALL"
            putExtra("callId", callId)
        }
        val endCallPendingIntent = PendingIntent.getBroadcast(
            this, 100, endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create hold/unhold action based on current state
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

        // Create main activity intent
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val mainPendingIntent = mainIntent?.let {
            PendingIntent.getActivity(
                this, 102, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val statusText = when (state) {
            "ACTIVE" -> "$callerName"
            "HELD" -> "$callerName (on hold)"
            "DIALING" -> "Calling $callerName..."
            "INCOMING" -> "Incoming call from $callerName"
            else -> callerName
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
            .setWhen(System.currentTimeMillis())

        // Add actions for ACTIVE and HELD calls only
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
            // For dialing calls, only show end call
            notificationBuilder.addAction(
                android.R.drawable.sym_call_outgoing,
                "End Call",
                endCallPendingIntent
            )
        }

        // Set content intent to open the main app
        mainPendingIntent?.let {
            notificationBuilder.setContentIntent(it)
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
            Log.d(TAG, "Foreground notification channel '$CHANNEL_ID' created/updated.")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: User swiped app from recents. Ending all calls.")
        CallEngine.endAllCalls(this)
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy. Stopping foreground.")
        stopForeground(true)
    }
}
