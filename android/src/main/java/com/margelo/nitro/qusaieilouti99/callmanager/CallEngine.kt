package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CallEngine {
    private const val TAG = "CallEngine"
    private const val PHONE_ACCOUNT_ID = "com.qusaieilouti99.callmanager.SELF_MANAGED"
    private const val NOTIF_CHANNEL_ID = "incoming_call_channel"
    private const val NOTIF_ID = 2001
    private const val FOREGROUND_CHANNEL_ID = "call_foreground_channel"
    private const val FOREGROUND_NOTIF_ID = 1001

    // Audio & Media
    private var ringtone: android.media.Ringtone? = null
    private var ringbackPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Call State Management
    private val activeCalls = ConcurrentHashMap<String, CallInfo>()
    private val telecomConnections = ConcurrentHashMap<String, Connection>()
    private var currentCallId: String? = null
    private var canMakeMultipleCalls: Boolean = false

    // Audio State Tracking
    private var lastAudioRoutesInfo: AudioRoutesInfo? = null
    private var lastMuteState: Boolean = false
    private var hasAudioFocus: Boolean = false

    // System Call State Tracking
    private var isSystemCallActive: Boolean = false

    // Lock Screen Bypass
    private var lockScreenBypassActive = false
    private val lockScreenBypassCallbacks = mutableSetOf<LockScreenBypassCallback>()

    // Event System
    private var eventHandler: ((CallEventType, String) -> Unit)? = null
    private val cachedEvents = mutableListOf<Pair<CallEventType, String>>()

    // Operation State
    private val operationInProgress = AtomicBoolean(false)

    data class CallInfo(
        val callId: String,
        val callData: String,
        var state: CallState,
        val callType: String = "Audio",
        val timestamp: Long = System.currentTimeMillis(),
        var wasHeldBySystem: Boolean = false
    )

    enum class CallState {
        INCOMING, DIALING, ACTIVE, HELD, ENDED
    }

    interface LockScreenBypassCallback {
        fun onLockScreenBypassChanged(shouldBypass: Boolean)
    }

    // --- Audio Focus Management ---
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                handleAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                handleAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                handleAudioFocusGain()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Can duck audio - don't hold call
                Log.d(TAG, "Audio focus loss - can duck, not holding call")
            }
        }
    }

    private fun handleAudioFocusLoss() {
        Log.d(TAG, "Audio focus lost - likely system call active")
        hasAudioFocus = false
        isSystemCallActive = true

        // Hold all active calls instead of ending them
        activeCalls.values.filter { it.state == CallState.ACTIVE }.forEach { call ->
            if (!call.wasHeldBySystem) {
                call.wasHeldBySystem = true
                call.state = CallState.HELD

                val connection = telecomConnections[call.callId]
                connection?.setOnHold()

                emitEvent(CallEventType.CALL_HELD, JSONObject().apply {
                    put("callId", call.callId)
                })

                notifySpecificCallStateChanged(appContext!!, call.callId, CallState.HELD)
                Log.d(TAG, "Call ${call.callId} held by system due to audio focus loss")
            }
        }

        stopRingback()
        updateForegroundNotification(appContext!!)
    }

    private fun handleAudioFocusGain() {
        Log.d(TAG, "Audio focus regained - system call likely ended")
        hasAudioFocus = true
        isSystemCallActive = false

        // Automatically resume calls that were held by system after a short delay
        Handler(Looper.getMainLooper()).postDelayed({
            activeCalls.values.filter { it.state == CallState.HELD && it.wasHeldBySystem }.forEach { call ->
                Log.d(TAG, "Auto-resuming call ${call.callId} after system call ended")
                call.wasHeldBySystem = false
                call.state = CallState.ACTIVE

                val connection = telecomConnections[call.callId]
                connection?.setActive()

                emitEvent(CallEventType.CALL_UNHELD, JSONObject().apply {
                    put("callId", call.callId)
                })

                notifySpecificCallStateChanged(appContext!!, call.callId, CallState.ACTIVE)
            }
            updateForegroundNotification(appContext!!)
        }, 1000) // 1 second delay to ensure system is ready
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = audioManager ?: appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            }
            val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "Audio focus request result: $result")
            hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "Audio focus request result (legacy): $result")
            hasAudioFocus
        }
    }

    private fun abandonAudioFocus() {
        audioManager = audioManager ?: appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        Log.d(TAG, "Audio focus abandoned")
    }

    // --- Event System ---
    fun setEventHandler(handler: ((CallEventType, String) -> Unit)?) {
        Log.d(TAG, "setEventHandler called. Handler present: ${handler != null}")
        eventHandler = handler
        handler?.let { h ->
            if (cachedEvents.isNotEmpty()) {
                Log.d(TAG, "Emitting ${cachedEvents.size} cached events.")
                cachedEvents.forEach { (type, data) -> h.invoke(type, data) }
                cachedEvents.clear()
            }
        }
    }

    fun emitEvent(type: CallEventType, data: JSONObject) {
        Log.d(TAG, "Emitting event: $type, data: $data")
        val dataString = data.toString()
        if (eventHandler != null) {
            eventHandler?.invoke(type, dataString)
        } else {
            Log.d(TAG, "No event handler registered, caching event: $type")
            cachedEvents.add(Pair(type, dataString))
        }
    }

    // --- Lock Screen Bypass Management ---
    fun registerLockScreenBypassCallback(callback: LockScreenBypassCallback) {
        lockScreenBypassCallbacks.add(callback)
    }

    fun unregisterLockScreenBypassCallback(callback: LockScreenBypassCallback) {
        lockScreenBypassCallbacks.remove(callback)
    }

    private fun updateLockScreenBypass() {
        val shouldBypass = isCallActive()
        if (lockScreenBypassActive != shouldBypass) {
            lockScreenBypassActive = shouldBypass
            Log.d(TAG, "Lock screen bypass state changed: $lockScreenBypassActive")
            lockScreenBypassCallbacks.forEach { callback ->
                try {
                    callback.onLockScreenBypassChanged(shouldBypass)
                } catch (e: Exception) {
                    Log.w(TAG, "Error notifying lock screen bypass callback", e)
                }
            }
        }
    }

    fun isLockScreenBypassActive(): Boolean = lockScreenBypassActive

    // --- Telecom Connection Management ---
    fun addTelecomConnection(callId: String, connection: Connection) {
        telecomConnections[callId] = connection
        Log.d(TAG, "Added Telecom Connection for callId: $callId. Total: ${telecomConnections.size}")
    }

    fun removeTelecomConnection(callId: String) {
        telecomConnections.remove(callId)?.let {
            Log.d(TAG, "Removed Telecom Connection for callId: $callId. Total: ${telecomConnections.size}")
        }
    }

    fun getTelecomConnection(callId: String): Connection? = telecomConnections[callId]

    fun getAppContext(): Context? = appContext

    // --- Public API ---
    fun setCanMakeMultipleCalls(allow: Boolean) {
        canMakeMultipleCalls = allow
        Log.d(TAG, "canMakeMultipleCalls set to: $allow")
    }

    fun getCurrentCallState(): String {
        val calls = getActiveCalls()
        val jsonArray = JSONArray()
        calls.forEach {
            val obj = JSONObject()
            obj.put("callId", it.callId)
            obj.put("callData", it.callData)
            obj.put("state", it.state.name)
            obj.put("callType", it.callType)
            jsonArray.put(obj)
        }
        val result = jsonArray.toString()
        Log.d(TAG, "Current call state: $result")
        return result
    }

    // --- Incoming Call Management ---
    fun reportIncomingCall(context: Context, callId: String, callData: String) {
        appContext = context.applicationContext
        Log.d(TAG, "reportIncomingCall: $callId, $callData")

        // Check for call collision - reject second incoming call automatically
        val incomingCall = activeCalls.values.find { it.state == CallState.INCOMING }
        if (incomingCall != null && incomingCall.callId != callId) {
            Log.d(TAG, "Incoming call collision detected. Auto-rejecting new call: $callId")
            rejectIncomingCallCollision(callId, "Another call is already incoming")
            return
        }

        // Check if there's an active call when receiving incoming
        val activeCall = activeCalls.values.find { it.state == CallState.ACTIVE || it.state == CallState.HELD }
        if (activeCall != null && !canMakeMultipleCalls) {
            Log.d(TAG, "Active call exists when receiving incoming call. Auto-rejecting: $callId")
            rejectIncomingCallCollision(callId, "Another call is already active")
            return
        }

        val callerName = extractCallerName(callData)
        val parsedCallType = extractCallType(callData)
        val isVideoCallBoolean = parsedCallType == "Video"

        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            Log.d(TAG, "Can't make multiple calls, holding existing calls.")
            activeCalls.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    it.state = CallState.HELD
                }
            }
        }

        activeCalls[callId] = CallInfo(callId, callData, CallState.INCOMING, parsedCallType)
        currentCallId = callId
        Log.d(TAG, "Call $callId added to activeCalls. State: INCOMING, callType: $parsedCallType")

        showIncomingCallUI(context, callId, callerName, parsedCallType)
        registerPhoneAccount(context)

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle(context)
        val extras = Bundle().apply {
            putString(MyConnectionService.EXTRA_CALL_DATA, callData)
            putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCallBoolean)
        }

        try {
            telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            startForegroundService(context)
            Log.d(TAG, "Successfully reported incoming call to TelecomManager for $callId")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Failed to report incoming call. Check MANAGE_OWN_CALLS permission: ${e.message}", e)
            endCall(context, callId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report incoming call: ${e.message}", e)
            endCall(context, callId)
        }

        updateLockScreenBypass()
        notifySpecificCallStateChanged(context, callId, CallState.INCOMING)
    }

    // --- Outgoing Call Management ---
    fun startOutgoingCall(context: Context, callId: String, callData: String) {
        appContext = context.applicationContext
        Log.d(TAG, "startOutgoingCall: $callId, $callData")

        // Validate outgoing call request
        if (!validateOutgoingCallRequest()) {
            Log.w(TAG, "Rejecting outgoing call - incoming/active call exists")
            emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
                put("callId", callId)
                put("reason", "Cannot start outgoing call while incoming or active call exists")
            })
            return
        }

        val targetName = extractCallerName(callData)
        val parsedCallType = extractCallType(callData)
        val isVideoCallBoolean = parsedCallType == "Video"

        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            Log.d(TAG, "Can't make multiple calls, holding existing calls before outgoing.")
            activeCalls.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    it.state = CallState.HELD
                }
            }
        }

        activeCalls[callId] = CallInfo(callId, callData, CallState.DIALING, parsedCallType)
        currentCallId = callId
        Log.d(TAG, "Call $callId added to activeCalls. State: DIALING, callType: $parsedCallType")

        registerPhoneAccount(context)
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle(context)
        val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, callId, null)

        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            putString(MyConnectionService.EXTRA_CALL_DATA, callData)
            putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCallBoolean)
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCallBoolean)
        }

        try {
            telecomManager.placeCall(addressUri, extras)
            startForegroundService(context)
            Log.d(TAG, "Successfully reported outgoing call to TelecomManager via placeCall for $callId")

            // Request audio focus and start ringback
            requestAudioFocus()
            startRingback()

            bringAppToForeground(context)
            keepScreenAwake(context, true)
            setInitialAudioRoute(context, parsedCallType)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Failed to start outgoing call via placeCall. Check MANAGE_OWN_CALLS permission: ${e.message}", e)
            endCall(context, callId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start outgoing call via placeCall: ${e.message}", e)
            endCall(context, callId)
        }

        updateLockScreenBypass()
        notifySpecificCallStateChanged(context, callId, CallState.DIALING)
    }

    // --- Call Answer Management ---
    fun callAnsweredFromJS(context: Context, callId: String) {
        Log.d(TAG, "callAnsweredFromJS: $callId - remote party answered")
        coreCallAnswered(context, callId, isLocalAnswer = false)
    }

    fun answerCall(context: Context, callId: String) {
        Log.d(TAG, "answerCall: $callId - local party answering")
        coreCallAnswered(context, callId, isLocalAnswer = true)
    }

    private fun coreCallAnswered(context: Context, callId: String, isLocalAnswer: Boolean) {
        Log.d(TAG, "coreCallAnswered: $callId, isLocalAnswer: $isLocalAnswer")

        val callInfo = activeCalls[callId]
        if (callInfo == null) {
            Log.w(TAG, "Cannot answer call $callId - not found in active calls")
            return
        }

        // Stop all ringtones and notifications immediately
        stopRingtone()
        stopRingback()
        cancelIncomingCallUI(context)

        // Request audio focus when answering
        requestAudioFocus()

        // Update call state
        activeCalls[callId]?.state = CallState.ACTIVE
        currentCallId = callId

        if (!canMakeMultipleCalls) {
            activeCalls.filter { it.key != callId }.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    it.state = CallState.HELD
                }
            }
        }

        // Bring app to foreground when call is answered
        bringAppToForeground(context)
        startForegroundService(context)
        keepScreenAwake(context, true)
        resetAudioMode(context)

        updateLockScreenBypass()

        // Update foreground notification with call info
        updateForegroundNotification(context)

        // Emit event with full call data instead of just callId
        emitEvent(CallEventType.CALL_ANSWERED, JSONObject().apply {
            put("callId", callId)
            put("callData", callInfo.callData)
            put("callType", callInfo.callType)
        })

        notifySpecificCallStateChanged(context, callId, CallState.ACTIVE)
        Log.d(TAG, "Call $callId successfully answered and UI cleaned up")
    }

    // --- Call Control Methods ---
    fun holdCall(context: Context, callId: String) {
        Log.d(TAG, "holdCall: $callId")
        val callInfo = activeCalls[callId]
        if (callInfo?.state != CallState.ACTIVE) {
            Log.w(TAG, "Cannot hold call $callId - not in active state (current: ${callInfo?.state})")
            return
        }

        activeCalls[callId]?.state = CallState.HELD
        val connection = telecomConnections[callId]
        connection?.setOnHold()

        updateForegroundNotification(context)
        emitEvent(CallEventType.CALL_HELD, JSONObject().put("callId", callId))
        updateLockScreenBypass()
        notifySpecificCallStateChanged(context, callId, CallState.HELD)
    }

    fun unholdCall(context: Context, callId: String) {
        Log.d(TAG, "unholdCall: $callId")
        val callInfo = activeCalls[callId]
        if (callInfo?.state != CallState.HELD) {
            Log.w(TAG, "Cannot unhold call $callId - not in held state (current: ${callInfo?.state})")
            return
        }

        // Try to request audio focus, but don't fail if we can't get it immediately
        // The system will handle audio routing properly
        if (!hasAudioFocus) {
            Log.d(TAG, "Attempting to request audio focus for unhold")
            requestAudioFocus()
        }

        activeCalls[callId]?.state = CallState.ACTIVE
        activeCalls[callId]?.wasHeldBySystem = false
        val connection = telecomConnections[callId]
        connection?.setActive()

        updateForegroundNotification(context)
        emitEvent(CallEventType.CALL_UNHELD, JSONObject().put("callId", callId))
        updateLockScreenBypass()
        notifySpecificCallStateChanged(context, callId, CallState.ACTIVE)

        Log.d(TAG, "Call $callId successfully unheld")
    }

    fun muteCall(context: Context, callId: String) {
        Log.d(TAG, "muteCall: $callId")
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Only emit event if mute state actually changes
        val wasMuted = audioManager?.isMicrophoneMute ?: false
        audioManager?.isMicrophoneMute = true

        if (!wasMuted) {
            lastMuteState = true
            emitEvent(CallEventType.CALL_MUTED, JSONObject().put("callId", callId))
        }
    }

    fun unmuteCall(context: Context, callId: String) {
        Log.d(TAG, "unmuteCall: $callId")
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Only emit event if mute state actually changes
        val wasMuted = audioManager?.isMicrophoneMute ?: false
        audioManager?.isMicrophoneMute = false

        if (wasMuted) {
            lastMuteState = false
            emitEvent(CallEventType.CALL_UNMUTED, JSONObject().put("callId", callId))
        }
    }

    // --- Call End Management ---
    fun endCall(context: Context, callId: String) {
        appContext = context.applicationContext
        Log.d(TAG, "endCall: $callId")
        coreEndCall(context, callId)
    }

    fun endAllCalls(context: Context) {
        Log.d(TAG, "endAllCalls: Ending all active calls.")
        if (activeCalls.isEmpty()) {
            Log.d(TAG, "No active calls, nothing to do.")
            return
        }

        activeCalls.keys.toList().forEach { callId ->
            coreEndCall(context, callId)
        }

        activeCalls.clear()
        telecomConnections.clear()
        currentCallId = null

        finalCleanup(context)
        updateLockScreenBypass()
    }

    private fun coreEndCall(context: Context, callId: String) {
        Log.d(TAG, "coreEndCall: $callId")

        val callInfo = activeCalls[callId] ?: run {
            Log.w(TAG, "Call $callId not found in active calls")
            return
        }

        // Update call state
        activeCalls[callId]?.state = CallState.ENDED
        activeCalls.remove(callId)
        Log.d(TAG, "Call $callId removed from activeCalls. Remaining: ${activeCalls.size}")

        // Stop ringtones and notifications
        stopRingback()
        stopRingtone()
        cancelIncomingCallUI(context)

        // Update current call
        if (currentCallId == callId) {
            currentCallId = activeCalls.filter { it.value.state != CallState.ENDED }.keys.firstOrNull()
            Log.d(TAG, "Current call was $callId. New currentCallId: $currentCallId")
        }

        // Handle telecom connection
        val connection = telecomConnections[callId]
        if (connection != null) {
            connection.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            connection.destroy()
            removeTelecomConnection(callId)
            Log.d(TAG, "Telecom Connection for $callId disconnected and destroyed.")
        }

        // If no more calls, do final cleanup
        if (activeCalls.isEmpty()) {
            finalCleanup(context)
        } else {
            updateForegroundNotification(context)
        }

        updateLockScreenBypass()
        emitEvent(CallEventType.CALL_ENDED, JSONObject().put("callId", callId))
        notifySpecificCallStateChanged(context, callId, CallState.ENDED)
    }

    // --- Audio Management ---
    fun getAudioDevices(): AudioRoutesInfo {
        audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
            Log.e(TAG, "getAudioDevices: AudioManager is null or appContext is not set. Returning default.")
            return AudioRoutesInfo(emptyArray(), "Unknown")
        }

        val devices = mutableSetOf<String>()
        var currentRoute = "Earpiece" // Default

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDeviceInfoList = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            audioDeviceInfoList?.forEach { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        devices.add("Bluetooth")
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                        devices.add("Headset")
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                        devices.add("Speaker")
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> {
                        devices.add("Earpiece")
                    }
                }
            }
        } else {
            devices.addAll(listOf("Speaker", "Earpiece"))
        }

        // Determine current route
        currentRoute = when {
            audioManager?.isBluetoothScoOn == true -> "Bluetooth"
            audioManager?.isSpeakerphoneOn == true -> "Speaker"
            audioManager?.isWiredHeadsetOn == true -> "Headset"
            else -> "Earpiece"
        }

        val result = AudioRoutesInfo(devices.toTypedArray(), currentRoute)
        Log.d(TAG, "Audio devices info: $result")
        return result
    }

    fun setAudioRoute(context: Context, route: String) {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "Attempting to set audio route to: $route. Current mode: ${audioManager?.mode}")

        val previousRoute = getCurrentAudioRoute()

        // Reset all routes first
        audioManager?.isSpeakerphoneOn = false
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false

        when (route) {
            "Speaker" -> {
                Log.d(TAG, "Setting audio route to Speaker.")
                audioManager?.isSpeakerphoneOn = true
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            "Earpiece" -> {
                Log.d(TAG, "Setting audio route to Earpiece.")
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            "Bluetooth" -> {
                Log.d(TAG, "Setting audio route to Bluetooth.")
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            "Headset" -> {
                Log.d(TAG, "Setting audio route to Headset (wired).")
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            else -> {
                Log.w(TAG, "Unknown audio route: $route. No action taken.")
                return
            }
        }

        // Only emit event if route actually changed
        val newRoute = getCurrentAudioRoute()
        if (previousRoute != newRoute) {
            emitEvent(CallEventType.AUDIO_ROUTE_CHANGED, JSONObject().put("route", newRoute))
        }
    }

    private fun getCurrentAudioRoute(): String {
        return when {
            audioManager?.isBluetoothScoOn == true -> "Bluetooth"
            audioManager?.isSpeakerphoneOn == true -> "Speaker"
            audioManager?.isWiredHeadsetOn == true -> "Headset"
            else -> "Earpiece"
        }
    }

    private fun setInitialAudioRoute(context: Context, callType: String) {
        // Get available audio devices to determine priority
        val availableDevices = getAudioDevices()

        val defaultRoute = when {
            // Prioritize Bluetooth if available (latest connected device)
            availableDevices.devices.contains("Bluetooth") -> "Bluetooth"
            // Then wired headset
            availableDevices.devices.contains("Headset") -> "Headset"
            // For video calls, default to speaker if no priority device
            callType == "Video" -> "Speaker"
            // For audio calls, default to earpiece if no priority device
            else -> "Earpiece"
        }

        Log.d(TAG, "Setting initial audio route for $callType call: $defaultRoute")
        setAudioRoute(context, defaultRoute)
    }

    fun resetAudioMode(context: Context) {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (activeCalls.isEmpty()) {
            Log.d(TAG, "Resetting audio mode to NORMAL as no active calls remain.")
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
            audioManager?.isSpeakerphoneOn = false
            abandonAudioFocus()
        } else {
            Log.d(TAG, "Audio mode not reset; ${activeCalls.size} calls still active.")
        }
    }

    // --- Audio Device Callback ---
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices added. Checking for changes.")
            emitAudioDevicesChangedIfNeeded()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices removed. Checking for changes.")
            emitAudioDevicesChangedIfNeeded()
        }
    }

    fun registerAudioDeviceCallback(context: Context) {
        appContext = context.applicationContext
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        Log.d(TAG, "Audio device callback registered.")
    }

    fun unregisterAudioDeviceCallback(context: Context) {
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        Log.d(TAG, "Audio device callback unregistered.")
    }

    private fun emitAudioDevicesChangedIfNeeded() {
        val context = appContext ?: return
        val currentAudioInfo = getAudioDevices()

        // Only emit if something actually changed
        if (lastAudioRoutesInfo == null ||
            !currentAudioInfo.devices.contentEquals(lastAudioRoutesInfo!!.devices) ||
            currentAudioInfo.currentRoute != lastAudioRoutesInfo!!.currentRoute) {

            lastAudioRoutesInfo = currentAudioInfo
            val jsonPayload = JSONObject().apply {
                put("devices", JSONArray(currentAudioInfo.devices.toList()))
                put("currentRoute", currentAudioInfo.currentRoute)
            }
            emitEvent(CallEventType.AUDIO_DEVICES_CHANGED, jsonPayload)
        }
    }

    // --- Screen Management ---
    fun keepScreenAwake(context: Context, keepAwake: Boolean) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (keepAwake) {
            if (wakeLock == null || !wakeLock!!.isHeld) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CallEngine:WakeLock"
                )
                wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes */)
                Log.d(TAG, "Acquired SCREEN_DIM_WAKE_LOCK.")
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Released SCREEN_DIM_WAKE_LOCK.")
                }
            }
            wakeLock = null
        }
    }

    // --- Utility Methods ---
    fun getActiveCalls(): List<CallInfo> = activeCalls.values.toList()
    fun getCurrentCallId(): String? = currentCallId
    fun isCallActive(): Boolean = activeCalls.any {
        it.value.state == CallState.ACTIVE ||
        it.value.state == CallState.INCOMING ||
        it.value.state == CallState.DIALING ||
        it.value.state == CallState.HELD
    }

    private fun validateOutgoingCallRequest(): Boolean {
        return !activeCalls.any {
            it.value.state == CallState.INCOMING || it.value.state == CallState.ACTIVE
        }
    }

    private fun extractCallerName(callData: String): String {
        return try {
            JSONObject(callData).optString("name", "Unknown")
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun extractCallType(callData: String): String {
        return try {
            JSONObject(callData).optString("callType", "Audio")
        } catch (e: Exception) {
            "Audio"
        }
    }

    private fun rejectIncomingCallCollision(callId: String, reason: String) {
        // Provide space for server HTTP request
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // TODO: Add your server HTTP request here
                Log.d(TAG, "Server rejection request would be made here for callId: $callId, reason: $reason")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send rejection to server", e)
            }
        }

        // Emit rejection event
        emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
            put("callId", callId)
            put("reason", reason)
        })
    }

    // --- Notification Management ---
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
            } else {
                channel.setSound(null, null)
                channel.importance = NotificationManager.IMPORTANCE_HIGH
            }

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel '$NOTIF_CHANNEL_ID' created/updated.")
        }
    }

    fun showIncomingCallUI(context: Context, callId: String, callerName: String, callType: String) {
        Log.d(TAG, "Showing incoming call UI for $callId, caller: $callerName, callType: $callType")
        createNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val answerIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.ANSWER_CALL"
            putExtra("callId", callId)
        }
        val answerPendingIntent = PendingIntent.getBroadcast(
            context, 0, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallNotificationActionReceiver::class.java).apply {
            action = "com.qusaieilouti99.callmanager.DECLINE_CALL"
            putExtra("callId", callId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context, 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("callType", callType)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = android.app.Person.Builder().setName(callerName).setImportant(true).build()
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            playRingtone(context)
        }

        setInitialAudioRoute(context, callType)
    }

    fun cancelIncomingCallUI(context: Context) {
        Log.d(TAG, "Cancelling incoming call UI.")
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIF_ID)
        stopRingtone()
    }

    // --- Service Management ---
    fun startForegroundService(context: Context) {
        Log.d(TAG, "Starting CallForegroundService.")

        // Find the current active call to pass its info
        val currentCall = activeCalls.values.find {
            it.state == CallState.ACTIVE || it.state == CallState.INCOMING ||
            it.state == CallState.DIALING || it.state == CallState.HELD
        }

        val intent = Intent(context, CallForegroundService::class.java)

        if (currentCall != null) {
            intent.putExtra("callId", currentCall.callId)
            intent.putExtra("callData", currentCall.callData)
            intent.putExtra("state", currentCall.state.name)
            Log.d(TAG, "Starting foreground service with call info: ${currentCall.callId}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopForegroundService(context: Context) {
        Log.d(TAG, "Stopping CallForegroundService.")
        val intent = Intent(context, CallForegroundService::class.java)
        context.stopService(intent)
    }

    fun bringAppToForeground(context: Context) {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (isCallActive()) {
            launchIntent?.putExtra("BYPASS_LOCK_SCREEN", true)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            Log.d(TAG, "App brought to foreground with lock screen bypass request for active call")
        } else {
            launchIntent?.removeExtra("BYPASS_LOCK_SCREEN")
            Log.d(TAG, "App brought to foreground without lock screen bypass")
        }

        try {
            context.startActivity(launchIntent)
            Handler(Looper.getMainLooper()).postDelayed({
                updateLockScreenBypass()
            }, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bring app to foreground: ${e.message}")
        }
    }

    // --- Phone Account Management ---
    private fun registerPhoneAccount(context: Context) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle(context)

        if (telecomManager.getPhoneAccount(phoneAccountHandle) == null) {
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "PingMe Call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()

            try {
                telecomManager.registerPhoneAccount(phoneAccount)
                Log.d(TAG, "PhoneAccount registered successfully.")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Cannot register PhoneAccount. Missing MANAGE_OWN_CALLS permission?", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneAccount: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "PhoneAccount already registered.")
        }
    }

    private fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(context, MyConnectionService::class.java),
            PHONE_ACCOUNT_ID
        )
    }

    // --- Media Management ---
    fun playRingtone(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "playRingtone: Android S+ detected, system will handle ringtone via Telecom.")
            return
        }

        try {
            Log.d(TAG, "Playing ringtone (for Android < S).")
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}", e)
        }
    }

    fun stopRingtone() {
        try {
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
                Log.d(TAG, "Ringtone stopped.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}", e)
        }
        ringtone = null
    }

    private fun startRingback() {
        if (ringbackPlayer?.isPlaying == true) {
            Log.d(TAG, "Ringback tone already playing.")
            return
        }

        try {
            val ringbackUri = Uri.parse("android.resource://${appContext?.packageName}/raw/ringback_tone")
            ringbackPlayer = MediaPlayer.create(appContext, ringbackUri)
            if (ringbackPlayer == null) {
                Log.e(TAG, "Failed to create MediaPlayer for ringback. Check raw/ringback_tone.mp3 exists.")
                return
            }

            ringbackPlayer?.apply {
                isLooping = true
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                start()
                Log.d(TAG, "Ringback tone started.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringback tone: ${e.message}", e)
        }
    }

    private fun stopRingback() {
        try {
            if (ringbackPlayer?.isPlaying == true) {
                ringbackPlayer?.stop()
                ringbackPlayer?.release()
                Log.d(TAG, "Ringback tone stopped and released.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringback tone: ${e.message}", e)
        } finally {
            ringbackPlayer = null
        }
    }

    // --- Event Management ---
    private fun notifySpecificCallStateChanged(context: Context, callId: String, newState: CallState) {
        val callInfo = activeCalls[callId] ?: return

        val jsonPayload = JSONObject().apply {
            put("callId", callId)
            put("callData", callInfo.callData)
            put("state", newState.name)
            put("callType", callInfo.callType)
        }

        Log.d(TAG, "Specific call state changed. Emitting CALL_STATE_CHANGED for $callId: $newState")
        emitEvent(CallEventType.CALL_STATE_CHANGED, jsonPayload)
    }

    private fun updateForegroundNotification(context: Context) {
        val activeCall = activeCalls.values.find { it.state == CallState.ACTIVE }
        val heldCall = activeCalls.values.find { it.state == CallState.HELD }

        val callToShow = activeCall ?: heldCall
        callToShow?.let {
            val intent = Intent(context, CallForegroundService::class.java)
            intent.putExtra("UPDATE_NOTIFICATION", true)
            intent.putExtra("callId", it.callId)
            intent.putExtra("callData", it.callData)
            intent.putExtra("state", it.state.name)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun finalCleanup(context: Context) {
        Log.d(TAG, "Performing final cleanup - no active calls remaining")
        stopForegroundService(context)
        keepScreenAwake(context, false)
        resetAudioMode(context)
        isSystemCallActive = false
    }
}
