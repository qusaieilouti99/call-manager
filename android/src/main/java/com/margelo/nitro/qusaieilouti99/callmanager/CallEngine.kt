package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.telecom.*
import android.util.Log
import android.graphics.Color
import android.app.Person
import java.util.concurrent.ConcurrentHashMap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

object CallEngine {
    private const val TAG = "CallEngine"
    private const val PHONE_ACCOUNT_ID = "com.qusaieilouti99.callmanager.SELF_MANAGED"
    private const val NOTIF_CHANNEL_ID = "incoming_call_channel"
    private const val NOTIF_ID = 2001
    private const val FOREGROUND_CHANNEL_ID = "call_foreground_channel"
    private const val FOREGROUND_NOTIF_ID = 1001

    private var ringtone: android.media.Ringtone? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // --- Multi-call state ---
    private val activeCalls = ConcurrentHashMap<String, CallInfo>() // callId -> CallInfo
    private var currentCallId: String? = null // The call currently in foreground (active/ringing)
    private var canMakeMultipleCalls: Boolean = true

    data class CallInfo(
        val callId: String,
        val callData: String,
        var state: CallState
    )

    enum class CallState {
        INCOMING, ACTIVE, HELD, ENDED
    }

    // --- Event handler for JS ---
    private var eventHandler: ((CallEventType, String) -> Unit)? = null

    fun setEventHandler(handler: ((CallEventType, String) -> Unit)?) {
        eventHandler = handler
    }

    fun emitEvent(type: CallEventType, data: JSONObject) {
        eventHandler?.invoke(type, data.toString())
    }

    fun getAppContext(): Context? = appContext

    // --- Public API ---

    fun setCanMakeMultipleCalls(allow: Boolean) {
        canMakeMultipleCalls = allow
    }

    fun getCurrentCallState(): String {
        val calls = getActiveCalls()
        if (calls.isEmpty()) return ""
        val jsonArray = JSONArray()
        calls.forEach {
            val obj = JSONObject()
            obj.put("callId", it.callId)
            obj.put("callData", it.callData)
            obj.put("state", it.state.name)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    fun reportIncomingCall(context: Context, callId: String, callData: String) {
        appContext = context.applicationContext
        Log.d(TAG, "reportIncomingCall: $callId, $callData")
        val callerName = try {
            val json = JSONObject(callData)
            json.optString("name", "Unknown")
        } catch (e: Exception) { "Unknown" }

        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            activeCalls.values.forEach { it.state = CallState.HELD }
        }

        activeCalls[callId] = CallInfo(callId, callData, CallState.INCOMING)
        currentCallId = callId

        showIncomingCallUI(context, callId, callerName)
        registerPhoneAccount(context)
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle(context)
        val extras = Bundle().apply { putString(MyConnectionService.EXTRA_CALL_DATA, callData) }
        try {
            telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            startForegroundService(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report incoming call: ${e.message}")
        }
        notifyCallStateChanged(context)
    }

    fun answerCall(context: Context, callId: String) {
        Log.d(TAG, "answerCall: $callId")
        activeCalls[callId]?.state = CallState.ACTIVE
        currentCallId = callId
        if (!canMakeMultipleCalls) {
            activeCalls.filter { it.key != callId }.values.forEach { it.state = CallState.HELD }
        }
        emitEvent(CallEventType.CALL_ANSWERED, JSONObject().put("callId", callId))
        notifyCallStateChanged(context)
    }

    fun holdCall(context: Context, callId: String) {
        Log.d(TAG, "holdCall: $callId")
        activeCalls[callId]?.state = CallState.HELD
        emitEvent(CallEventType.CALL_HELD, JSONObject().put("callId", callId))
        notifyCallStateChanged(context)
    }

    fun unholdCall(context: Context, callId: String) {
        Log.d(TAG, "unholdCall: $callId")
        activeCalls[callId]?.state = CallState.ACTIVE
        emitEvent(CallEventType.CALL_UNHELD, JSONObject().put("callId", callId))
        notifyCallStateChanged(context)
    }

    fun muteCall(context: Context, callId: String) {
        emitEvent(CallEventType.CALL_MUTED, JSONObject().put("callId", callId))
    }

    fun unmuteCall(context: Context, callId: String) {
        emitEvent(CallEventType.CALL_UNMUTED, JSONObject().put("callId", callId))
    }

    fun endCall(context: Context, callId: String) {
        Log.d(TAG, "endCall: $callId")
        activeCalls[callId]?.state = CallState.ENDED
        activeCalls.remove(callId)
        if (currentCallId == callId) {
            currentCallId = activeCalls.keys.firstOrNull()
        }
        cancelIncomingCallUI(context)
        stopForegroundService(context)
        disconnectTelecomCall(context, callId)
        emitEvent(CallEventType.CALL_ENDED, JSONObject().put("callId", callId))
        notifyCallStateChanged(context)
    }

    fun endAllCalls(context: Context) {
        if (activeCalls.isEmpty()) {
            Log.d(TAG, "endAllCalls: No active calls, nothing to do.")
            return
        }
        Log.d(TAG, "endAllCalls: Ending all active calls.")
        activeCalls.keys.toList().forEach { endCall(context, it) }
        activeCalls.clear()
        currentCallId = null
        cancelIncomingCallUI(context)
        stopForegroundService(context)
        notifyCallStateChanged(context)
    }

    fun getActiveCalls(): List<CallInfo> = activeCalls.values.toList()
    fun getCurrentCallId(): String? = currentCallId
    fun isCallActive(): Boolean = activeCalls.any { it.value.state == CallState.ACTIVE || it.value.state == CallState.INCOMING }

    // --- Notification/UI/Foreground ---

    fun showIncomingCallUI(context: Context, callId: String, callerName: String) {
        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val answerIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.ANSWER_CALL"
            putExtra("callId", callId)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(context, 0, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val declineIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.DECLINE_CALL"
            putExtra("callId", callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(context, 1, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(context, 2, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = Person.Builder().setName(callerName).setImportant(true).build()
            Notification.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setStyle(Notification.CallStyle.forIncomingCall(person, declinePendingIntent, answerPendingIntent))
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
        } else {
            Notification.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming Call")
                .setContentText(callerName)
                .setPriority(Notification.PRIORITY_HIGH)
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .addAction(android.R.drawable.sym_action_call, "Answer", answerPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
        }

        notificationManager.notify(NOTIF_ID, notification)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) playRingtone(context)
    }

    fun cancelIncomingCallUI(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIF_ID)
        stopRingtone()
    }

    fun startForegroundService(context: Context) {
        val intent = Intent(context, CallForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stopForegroundService(context: Context) {
        val intent = Intent(context, CallForegroundService::class.java)
        context.stopService(intent)
    }

    fun disconnectTelecomCall(context: Context, callId: String?) {
        Log.d(TAG, "disconnectTelecomCall called for callId=$callId")
    }

    fun bringAppToForeground(context: Context) {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
        Log.d(TAG, "App brought to foreground via launchIntent")
    }

    // --- Audio Device Management ---

    fun getAudioDevices(context: Context): List<String> {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> devices.add("Bluetooth")
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> devices.add("Headset")
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> devices.add("Speaker")
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> devices.add("Earpiece")
                }
            }
        } else {
            devices.add("Speaker")
            devices.add("Earpiece")
        }
        return devices.distinct()
    }

    fun setAudioRoute(context: Context, route: String) {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (route) {
            "Speaker" -> audioManager?.isSpeakerphoneOn = true
            "Earpiece" -> audioManager?.isSpeakerphoneOn = false
            "Bluetooth" -> audioManager?.startBluetoothSco()
            "Headset" -> { /* usually auto-selected */ }
        }
        emitEvent(CallEventType.AUDIO_ROUTE_CHANGED, JSONObject().put("route", route))
    }

    // --- Screen Awake Management ---
    fun keepScreenAwake(context: Context, keepAwake: Boolean) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (keepAwake) {
            if (wakeLock == null || !wakeLock!!.isHeld) {
                wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CallEngine:WakeLock"
                )
                wakeLock?.acquire()
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    // --- Audio Device Change Listener ---
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            emitAudioDevicesChanged()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            emitAudioDevicesChanged()
        }
    }

    fun registerAudioDeviceCallback(context: Context) {
        appContext = context.applicationContext
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun unregisterAudioDeviceCallback(context: Context) {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    fun emitAudioDevicesChanged() {
        val context = appContext ?: return
        val devices = getAudioDevices(context)
        val json = JSONObject().put("devices", JSONArray(devices))
        emitEvent(CallEventType.AUDIO_DEVICES_CHANGED, json)
    }

    // --- Call State Change Notification ---
    private fun notifyCallStateChanged(context: Context) {
        val calls = getActiveCalls()
        val jsonArray = JSONArray()
        calls.forEach {
            val obj = JSONObject()
            obj.put("callId", it.callId)
            obj.put("callData", it.callData)
            obj.put("state", it.state.name)
            jsonArray.put(obj)
        }
        emitEvent(CallEventType.CALL_STATE_CHANGED, JSONObject().put("calls", jsonArray))
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Incoming Call Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Notifications for incoming calls"
            channel.enableLights(true)
            channel.lightColor = Color.GREEN
            channel.enableVibration(true)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                channel.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun registerPhoneAccount(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle(context)
        if (telecomManager.getPhoneAccount(phoneAccountHandle) == null) {
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "PingMe Call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(phoneAccount)
        }
    }

    private fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, MyConnectionService::class.java),
            PHONE_ACCOUNT_ID
        )
    }

    fun playRingtone(context: Context) {
        try {
            Log.d(TAG, "Playing ringtone")
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}")
        }
    }

    fun stopRingtone() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }
}
