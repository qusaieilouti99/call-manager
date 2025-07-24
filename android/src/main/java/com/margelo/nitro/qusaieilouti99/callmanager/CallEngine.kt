package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object CallEngine {
    private const val TAG = "CallEngine"
    private const val PHONE_ACCOUNT_ID = "com.qusaieilouti99.callmanager.SELF_MANAGED"
    private const val NOTIF_CHANNEL_ID = "incoming_call_channel"
    private const val NOTIF_ID = 2001

    // Core context - initialized once and maintained
    @Volatile
    private var appContext: Context? = null
    private val isInitialized = AtomicBoolean(false)
    private val initializationLock = Any()

    // Simplified Audio & Media Management (NO MANUAL AUDIO FOCUS)
    private var ringtone: android.media.Ringtone? = null
    private var ringbackPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Call State Management
    private val activeCalls = ConcurrentHashMap<String, CallInfo>()
    private val telecomConnections = ConcurrentHashMap<String, Connection>()
    private val callMetadata = ConcurrentHashMap<String, String>()

    private var currentCallId: String? = null
    private var canMakeMultipleCalls: Boolean = false

    // Audio State Tracking
    private var lastAudioRoutesInfo: AudioRoutesInfo? = null

    // Lock Screen Bypass
    private var lockScreenBypassActive = false
    private val lockScreenBypassCallbacks = mutableSetOf<LockScreenBypassCallback>()

    // Event System
    private var eventHandler: ((CallEventType, String) -> Unit)? = null
    private val cachedEvents = mutableListOf<Pair<CallEventType, String>>()

    interface LockScreenBypassCallback {
        fun onLockScreenBypassChanged(shouldBypass: Boolean)
    }

    // --- INITIALIZATION ---
    fun initialize(context: Context) {
        synchronized(initializationLock) {
            if (isInitialized.compareAndSet(false, true)) {
                appContext = context.applicationContext
                audioManager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                Log.d(TAG, "CallEngine initialized successfully")

                if (isCallActive()) {
                    startForegroundService()
                }
            }
        }
    }

    fun isInitialized(): Boolean = isInitialized.get()

    private fun requireContext(): Context {
        return appContext ?: throw IllegalStateException(
            "CallEngine not initialized. Call initialize() in Application.onCreate()"
        )
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
        Log.d(TAG, "Emitting event: $type")
        val dataString = data.toString()
        if (eventHandler != null) {
            eventHandler?.invoke(type, dataString)
        } else {
            Log.d(TAG, "No event handler, caching event: $type")
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
        Log.d(TAG, "Added Telecom Connection for callId: $callId")
    }

    fun removeTelecomConnection(callId: String) {
        telecomConnections.remove(callId)?.let {
            Log.d(TAG, "Removed Telecom Connection for callId: $callId")
        }
    }

    fun getTelecomConnection(callId: String): Connection? = telecomConnections[callId]

    // --- Public API ---
    fun setCanMakeMultipleCalls(allow: Boolean) {
        canMakeMultipleCalls = allow
        Log.d(TAG, "canMakeMultipleCalls set to: $allow")
    }

    fun getCurrentCallState(): String {
        val calls = getActiveCalls()
        val jsonArray = JSONArray()
        calls.forEach {
            jsonArray.put(it.toJsonObject())
        }
        return jsonArray.toString()
    }

    // --- Incoming Call Management ---
    fun reportIncomingCall(
        context: Context,
        callId: String,
        callType: String,
        displayName: String,
        pictureUrl: String? = null,
        metadata: String? = null
    ) {
        if (!isInitialized.get()) {
            initialize(context)
        }

        Log.d(TAG, "reportIncomingCall: callId=$callId, type=$callType, name=$displayName")

        metadata?.let { callMetadata[callId] = it }

        // Check for call collision
        val incomingCall = activeCalls.values.find { it.state == CallState.INCOMING }
        if (incomingCall != null && incomingCall.callId != callId) {
            Log.d(TAG, "Incoming call collision detected. Auto-rejecting new call: $callId")
            rejectIncomingCallCollision(callId, "Another call is already incoming")
            return
        }

        val activeCall = activeCalls.values.find { it.state == CallState.ACTIVE || it.state == CallState.HELD }
        if (activeCall != null && !canMakeMultipleCalls) {
            Log.d(TAG, "Active call exists when receiving incoming call. Auto-rejecting: $callId")
            rejectIncomingCallCollision(callId, "Another call is already active")
            return
        }

        val isVideoCall = callType == "Video"

        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            activeCalls.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    holdCallInternal(it.callId, heldBySystem = false)
                }
            }
        }

        activeCalls[callId] = CallInfo(callId, callType, displayName, pictureUrl, CallState.INCOMING)
        currentCallId = callId
        Log.d(TAG, "Call $callId added to activeCalls. State: INCOMING")

        showIncomingCallUI(callId, displayName, callType)
        registerPhoneAccount()

        val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle()
        val extras = Bundle().apply {
            putString(MyConnectionService.EXTRA_CALL_ID, callId)
            putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
            putString(MyConnectionService.EXTRA_DISPLAY_NAME, displayName)
            putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
            pictureUrl?.let { putString(MyConnectionService.EXTRA_PICTURE_URL, it) }
        }

        try {
            telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            startForegroundService()
            Log.d(TAG, "Successfully reported incoming call to TelecomManager for $callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report incoming call: ${e.message}", e)
            endCallInternal(callId)
        }

        updateLockScreenBypass()
    }

    // --- Outgoing Call Management ---
    fun startOutgoingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String? = null
    ) {
        val context = requireContext()
        Log.d(TAG, "startOutgoingCall: callId=$callId, type=$callType, target=$targetName")

        metadata?.let { callMetadata[callId] = it }

        if (!validateOutgoingCallRequest()) {
            Log.w(TAG, "Rejecting outgoing call - incoming/active call exists")
            emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
                put("callId", callId)
                put("reason", "Cannot start outgoing call while incoming or active call exists")
            })
            return
        }

        val isVideoCall = callType == "Video"
        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            activeCalls.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    holdCallInternal(it.callId, heldBySystem = false)
                }
            }
        }

        // Track dialing state
        activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.DIALING)
        currentCallId = callId
        Log.d(TAG, "Call $callId added to activeCalls. State: DIALING")

        // ONLY set audio mode - let system handle audio focus for self-managed calls
        setAudioMode()

        registerPhoneAccount()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle()
        val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, targetName, null)

        val outgoingExtras = Bundle().apply {
            putString(MyConnectionService.EXTRA_CALL_ID, callId)
            putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
            putString(MyConnectionService.EXTRA_DISPLAY_NAME, targetName)
            putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
            metadata?.let { putString("metadata", it) }
        }

        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCall)
        }

        try {
            telecomManager.placeCall(addressUri, extras)
            startForegroundService()

            // Start ringback (system will handle audio focus)
            startRingback()

            bringAppToForeground()
            keepScreenAwake(true)
            setInitialAudioRoute(callType)
            Log.d(TAG, "Successfully reported outgoing call to TelecomManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start outgoing call: ${e.message}", e)
            endCallInternal(callId)
        }

        updateLockScreenBypass()
    }

    fun startCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String? = null
    ) {
        Log.d(TAG, "startCall: callId=$callId, type=$callType, target=$targetName")

        metadata?.let { callMetadata[callId] = it }

        if (activeCalls.containsKey(callId)) {
            Log.w(TAG, "Call $callId already exists, cannot start again")
            return
        }

        if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
            activeCalls.values.forEach {
                if (it.state == CallState.ACTIVE) {
                    holdCallInternal(it.callId, heldBySystem = false)
                }
            }
        }

        // Start directly as active call
        activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.ACTIVE)
        currentCallId = callId
        Log.d(TAG, "Call $callId started as ACTIVE")

        registerPhoneAccount()
        setAudioMode()
        bringAppToForeground()
        startForegroundService()
        keepScreenAwake(true)
        setInitialAudioRoute(callType)
        updateLockScreenBypass()

        // Emit outgoing call answered event
        emitOutgoingCallAnsweredWithMetadata(callId)
    }

    // --- Call Answer Management (SIMPLIFIED - NO MANUAL AUDIO FOCUS) ---
    fun callAnsweredFromJS(callId: String) {
        Log.d(TAG, "callAnsweredFromJS: $callId - remote party answered")
        coreCallAnswered(callId, isLocalAnswer = false)
    }

    fun answerCall(callId: String) {
        Log.d(TAG, "answerCall: $callId - local party answering")
        coreCallAnswered(callId, isLocalAnswer = true)
    }

    // SIMPLIFIED: Let system handle audio focus for self-managed calls
    private fun coreCallAnswered(callId: String, isLocalAnswer: Boolean) {
        Log.d(TAG, "coreCallAnswered: $callId, isLocalAnswer: $isLocalAnswer")

        val callInfo = activeCalls[callId]
        if (callInfo == null) {
            Log.w(TAG, "Cannot answer call $callId - not found in active calls")
            return
        }

        // Set audio mode and let system handle audio focus
        setAudioMode()

        // Set call to ACTIVE
        activeCalls[callId] = callInfo.copy(state = CallState.ACTIVE)
        currentCallId = callId
        Log.d(TAG, "Call $callId set to ACTIVE state (system manages audio focus)")

        // Clean up media and UI
        stopRingtone()
        stopRingback()
        cancelIncomingCallUI()

        // Handle multiple calls
        if (!canMakeMultipleCalls) {
            activeCalls.filter { it.key != callId }.values.forEach { otherCall ->
                if (otherCall.state == CallState.ACTIVE) {
                    holdCallInternal(otherCall.callId, heldBySystem = false)
                }
            }
        }

        bringAppToForeground()
        startForegroundService()
        keepScreenAwake(true)
        updateLockScreenBypass()

        // Emit events based on call direction
        if (isLocalAnswer) {
            emitCallAnsweredWithMetadata(callId)
        } else {
            emitOutgoingCallAnsweredWithMetadata(callId)
        }

        Log.d(TAG, "Call $callId successfully answered")
    }

    // For incoming calls (local answer)
    private fun emitCallAnsweredWithMetadata(callId: String) {
        val callInfo = activeCalls[callId] ?: return
        val metadata = callMetadata[callId]

        emitEvent(CallEventType.CALL_ANSWERED, JSONObject().apply {
            put("callId", callId)
            put("callType", callInfo.callType)
            put("displayName", callInfo.displayName)
            callInfo.pictureUrl?.let { put("pictureUrl", it) }
            metadata?.let {
                try {
                    put("metadata", JSONObject(it))
                } catch (e: Exception) {
                    put("metadata", it)
                }
            }
        })
    }

    // For outgoing calls (remote answer)
    private fun emitOutgoingCallAnsweredWithMetadata(callId: String) {
        val callInfo = activeCalls[callId] ?: return
        val metadata = callMetadata[callId]

        emitEvent(CallEventType.OUTGOING_CALL_ANSWERED, JSONObject().apply {
            put("callId", callId)
            put("callType", callInfo.callType)
            put("displayName", callInfo.displayName)
            callInfo.pictureUrl?.let { put("pictureUrl", it) }
            metadata?.let {
                try {
                    put("metadata", JSONObject(it))
                } catch (e: Exception) {
                    put("metadata", it)
                }
            }
        })
    }

    // --- Call Control Methods ---
    fun holdCall(callId: String) {
        holdCallInternal(callId, heldBySystem = false)
    }

    fun setOnHold(callId: String, onHold: Boolean) {
        Log.d(TAG, "setOnHold: $callId, onHold: $onHold")
        val callInfo = activeCalls[callId]
        if (callInfo == null) {
            Log.w(TAG, "Cannot set hold state for call $callId - not found")
            return
        }

        if (onHold && callInfo.state == CallState.ACTIVE) {
            holdCallInternal(callId, heldBySystem = false)
        } else if (!onHold && callInfo.state == CallState.HELD) {
            unholdCallInternal(callId, resumedBySystem = false)
        }
    }

    private fun holdCallInternal(callId: String, heldBySystem: Boolean) {
        Log.d(TAG, "holdCallInternal: $callId, heldBySystem: $heldBySystem")
        val callInfo = activeCalls[callId]
        if (callInfo?.state != CallState.ACTIVE) {
            Log.w(TAG, "Cannot hold call $callId - not in active state")
            return
        }

        activeCalls[callId] = callInfo.copy(
            state = CallState.HELD,
            wasHeldBySystem = heldBySystem
        )

        telecomConnections[callId]?.setOnHold()
        updateForegroundNotification()
        emitEvent(CallEventType.CALL_HELD, JSONObject().put("callId", callId))
        updateLockScreenBypass()
    }

    fun unholdCall(callId: String) {
        unholdCallInternal(callId, resumedBySystem = false)
    }

    private fun unholdCallInternal(callId: String, resumedBySystem: Boolean) {
        Log.d(TAG, "unholdCallInternal: $callId, resumedBySystem: $resumedBySystem")
        val callInfo = activeCalls[callId]
        if (callInfo?.state != CallState.HELD) {
            Log.w(TAG, "Cannot unhold call $callId - not in held state")
            return
        }

        activeCalls[callId] = callInfo.copy(
            state = CallState.ACTIVE,
            wasHeldBySystem = false
        )

        telecomConnections[callId]?.setActive()
        updateForegroundNotification()
        emitEvent(CallEventType.CALL_UNHELD, JSONObject().put("callId", callId))
        updateLockScreenBypass()
    }

    fun muteCall(callId: String) {
        setMutedInternal(callId, true)
    }

    fun unmuteCall(callId: String) {
        setMutedInternal(callId, false)
    }

    fun setMuted(callId: String, muted: Boolean) {
        setMutedInternal(callId, muted)
    }

    private fun setMutedInternal(callId: String, muted: Boolean) {
        val callInfo = activeCalls[callId]
        if (callInfo == null) {
            Log.w(TAG, "Cannot set mute state for call $callId - not found")
            return
        }

        val context = requireContext()
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val wasMuted = audioManager?.isMicrophoneMute ?: false
        audioManager?.isMicrophoneMute = muted

        if (wasMuted != muted) {
            val eventType = if (muted) CallEventType.CALL_MUTED else CallEventType.CALL_UNMUTED
            emitEvent(eventType, JSONObject().put("callId", callId))
            Log.d(TAG, "Call $callId mute state changed to: $muted")
        }
    }

    // --- Call End Management ---
    fun endCall(callId: String) {
        Log.d(TAG, "endCall: $callId")
        endCallInternal(callId)
    }

    fun endAllCalls() {
        Log.d(TAG, "endAllCalls: Ending all active calls")
        if (activeCalls.isEmpty()) return

        activeCalls.keys.toList().forEach { callId ->
            endCallInternal(callId)
        }

        activeCalls.clear()
        telecomConnections.clear()
        callMetadata.clear()
        currentCallId = null

        cleanup()
        updateLockScreenBypass()
    }

    private fun endCallInternal(callId: String) {
        Log.d(TAG, "endCallInternal: $callId")

        val callInfo = activeCalls[callId] ?: run {
            Log.w(TAG, "Call $callId not found in active calls")
            return
        }

        val metadata = callMetadata.remove(callId)

        activeCalls[callId] = callInfo.copy(state = CallState.ENDED)
        activeCalls.remove(callId)

        stopRingback()
        stopRingtone()
        cancelIncomingCallUI()

        if (currentCallId == callId) {
            currentCallId = activeCalls.filter { it.value.state != CallState.ENDED }.keys.firstOrNull()
        }

        val connection = telecomConnections[callId]
        if (connection != null) {
            connection.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            connection.destroy()
            removeTelecomConnection(callId)
        }

        if (activeCalls.isEmpty()) {
            cleanup()
        } else {
            updateForegroundNotification()
        }

        updateLockScreenBypass()

        // Emit end event with metadata
        emitEvent(CallEventType.CALL_ENDED, JSONObject().apply {
            put("callId", callId)
            metadata?.let {
                try {
                    put("metadata", JSONObject(it))
                } catch (e: Exception) {
                    put("metadata", it)
                }
            }
        })
    }

    // --- Enhanced Audio Management ---
    fun getAudioDevices(): AudioRoutesInfo {
        val context = requireContext()
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: run {
            return AudioRoutesInfo(emptyArray(), "Unknown")
        }

        val devices = mutableSetOf<String>()

        // ALWAYS include Speaker and Earpiece for phone calls
        devices.add("Speaker")
        devices.add("Earpiece")

        // Check for additional connected devices
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
                    // Speaker and Earpiece already added above if they are reported, but explicit add handles cases where they are not
                }
            }
        } else {
            // For older versions, check for Bluetooth and Headset
            @Suppress("DEPRECATION")
            if (audioManager?.isBluetoothA2dpOn == true || audioManager?.isBluetoothScoOn == true) {
                devices.add("Bluetooth")
            }
            @Suppress("DEPRECATION")
            if (audioManager?.isWiredHeadsetOn == true) {
                devices.add("Headset")
            }
        }

        val currentRoute = getCurrentAudioRoute()
        Log.d(TAG, "Available audio devices: ${devices.toList()}, current route: $currentRoute")

        // Update last known audio routes info
        lastAudioRoutesInfo = AudioRoutesInfo(devices.toTypedArray(), currentRoute)

        return AudioRoutesInfo(devices.toTypedArray(), currentRoute)
    }

    fun setAudioRoute(route: String) {
        val context = requireContext()
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "Setting audio route to: $route")

        val previousRoute = getCurrentAudioRoute()

        // Reset all routes first
        audioManager?.isSpeakerphoneOn = false
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false

        when (route) {
            "Speaker" -> {
                audioManager?.isSpeakerphoneOn = true
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            "Earpiece" -> {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                // Earpiece is the default - just ensure speaker and bluetooth are off
            }
            "Bluetooth" -> {
                audioManager?.startBluetoothSco()
                audioManager?.isBluetoothScoOn = true
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            "Headset" -> {
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                // Headset routing is automatic when connected
            }
            else -> {
                Log.w(TAG, "Unknown audio route: $route")
                return
            }
        }

        val newRoute = getCurrentAudioRoute()
        if (previousRoute != newRoute) {
            // Emit unified event with full context
            emitAudioRouteChanged()
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

    private fun setInitialAudioRoute(callType: String) {
        val availableDevices = getAudioDevices() // This will update lastAudioRoutesInfo

        val defaultRoute = when {
            availableDevices.devices.contains("Bluetooth") -> "Bluetooth"
            availableDevices.devices.contains("Headset") -> "Headset"
            callType == "Video" -> "Speaker"
            else -> "Earpiece"
        }

        Log.d(TAG, "Setting initial audio route: $defaultRoute")
        setAudioRoute(defaultRoute)
    }

    private fun setAudioMode() {
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION (system handles audio focus)")
    }

    private fun resetAudioMode() {
        if (activeCalls.isEmpty()) {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
            audioManager?.isSpeakerphoneOn = false
            Log.d(TAG, "Audio mode reset to MODE_NORMAL")
        }
    }

    // UNIFIED event emission - always sends full audio context
    private fun emitAudioRouteChanged() {
        // Re-calculate latest state to ensure accuracy
        val audioInfo = getAudioDevices() // This updates lastAudioRoutesInfo internally
        val jsonPayload = JSONObject().apply {
            put("devices", JSONArray(audioInfo.devices.toList()))
            put("currentRoute", audioInfo.currentRoute)
        }
        emitEvent(CallEventType.AUDIO_ROUTE_CHANGED, jsonPayload)
        Log.d(TAG, "Audio route changed: ${audioInfo.currentRoute}, available: ${audioInfo.devices.toList()}")
    }

    // --- Audio Device Callback ---
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices added")
            emitAudioDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices removed")
            emitAudioDevicesChanged()
        }
    }

    // Event for when physical devices are added/removed - includes full audio context
    private fun emitAudioDevicesChanged() {
        // Re-calculate latest state to ensure accuracy
        val audioInfo = getAudioDevices() // This updates lastAudioRoutesInfo internally
        val jsonPayload = JSONObject().apply {
            put("devices", JSONArray(audioInfo.devices.toList()))
            put("currentRoute", audioInfo.currentRoute)
        }
        emitEvent(CallEventType.AUDIO_DEVICES_CHANGED, jsonPayload)
        Log.d(TAG, "Audio devices changed: available: ${audioInfo.devices.toList()}")
    }


    fun registerAudioDeviceCallback() {
        val context = requireContext()
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun unregisterAudioDeviceCallback() {
        val context = requireContext()
        audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    // --- Screen Management ---
    fun keepScreenAwake(keepAwake: Boolean) {
        val context = requireContext()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (keepAwake) {
            if (wakeLock == null || !wakeLock!!.isHeld) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CallEngine:WakeLock"
                )
                wakeLock?.acquire(10 * 60 * 1000L)
                Log.d(TAG, "Acquired SCREEN_DIM_WAKE_LOCK")
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Released SCREEN_DIM_WAKE_LOCK")
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

    private fun rejectIncomingCallCollision(callId: String, reason: String) {
        callMetadata.remove(callId)
        emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
            put("callId", callId)
            put("reason", reason)
        })
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        val context = requireContext()
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
        }
    }

    private fun showIncomingCallUI(callId: String, callerName: String, callType: String) {
        val context = requireContext()
        Log.d(TAG, "Showing incoming call UI for $callId")
        createNotificationChannel()
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
            playRingtone()
        }

        setInitialAudioRoute(callType)
    }

    fun cancelIncomingCallUI() {
        val context = requireContext()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIF_ID)
        stopRingtone()
    }

    // --- Service Management ---
    private fun startForegroundService() {
        val context = requireContext()
        val currentCall = activeCalls.values.find {
            it.state == CallState.ACTIVE || it.state == CallState.INCOMING ||
            it.state == CallState.DIALING || it.state == CallState.HELD
        }

        val intent = Intent(context, CallForegroundService::class.java)
        if (currentCall != null) {
            intent.putExtra("callId", currentCall.callId)
            intent.putExtra("callType", currentCall.callType)
            intent.putExtra("displayName", currentCall.displayName)
            intent.putExtra("state", currentCall.state.name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopForegroundService() {
        val context = requireContext()
        val intent = Intent(context, CallForegroundService::class.java)
        context.stopService(intent)
    }

    private fun updateForegroundNotification() {
        startForegroundService() // Just restart the service with updated info
    }

    private fun isMainActivityInForeground(): Boolean {
        val context = requireContext()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val tasks = activityManager.getAppTasks()
            if (tasks.isNotEmpty()) {
                val taskInfo = tasks[0].taskInfo
                return taskInfo.topActivity?.className?.contains("MainActivity") == true
            }
        } else {
            @Suppress("DEPRECATION")
            val tasks = activityManager.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                return tasks[0].topActivity?.className?.contains("MainActivity") == true
            }
        }
        return false
    }

    private fun bringAppToForeground() {
        // Check if MainActivity is already in foreground
        if (isMainActivityInForeground()) {
            Log.d(TAG, "MainActivity is already in foreground, skipping bringAppToForeground()")
            return
        }

        Log.d(TAG, "MainActivity is not in foreground, bringing to foreground")

        val context = requireContext()
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        if (isCallActive()) {
            launchIntent?.putExtra("BYPASS_LOCK_SCREEN", true)
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
    private fun registerPhoneAccount() {
        val context = requireContext()
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val phoneAccountHandle = getPhoneAccountHandle()

        if (telecomManager.getPhoneAccount(phoneAccountHandle) == null) {
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "PingMe Call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()

            try {
                telecomManager.registerPhoneAccount(phoneAccount)
                Log.d(TAG, "PhoneAccount registered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneAccount: ${e.message}", e)
            }
        }
    }

    private fun getPhoneAccountHandle(): PhoneAccountHandle {
        val context = requireContext()
        return PhoneAccountHandle(
            ComponentName(context, MyConnectionService::class.java),
            PHONE_ACCOUNT_ID
        )
    }

    // --- Media Management ---
    private fun playRingtone() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return // System handles it
        }

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}")
        }
    }

    fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
        ringtone = null
    }

    private fun startRingback() {
        val context = requireContext()
        if (ringbackPlayer?.isPlaying == true) return

        try {
            val ringbackUri = Uri.parse("android.resource://${context.packageName}/raw/ringback_tone")
            ringbackPlayer = MediaPlayer.create(context, ringbackUri)
            ringbackPlayer?.apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringback tone: ${e.message}")
        }
    }

    private fun stopRingback() {
        try {
            ringbackPlayer?.stop()
            ringbackPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringback: ${e.message}")
        } finally {
            ringbackPlayer = null
        }
    }

    // --- Cleanup ---
    private fun cleanup() {
        Log.d(TAG, "Performing cleanup")
        stopForegroundService()
        keepScreenAwake(false)
        resetAudioMode()
    }

    // --- Lifecycle Management ---
    fun onApplicationTerminate() {
        Log.d(TAG, "Application terminating")

        // End all calls properly
        activeCalls.keys.toList().forEach { callId ->
            val connection = telecomConnections[callId]
            connection?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            connection?.destroy()
        }

        // Clear all state
        activeCalls.clear()
        telecomConnections.clear()
        callMetadata.clear()
        currentCallId = null

        cleanup()

        // Clear callbacks
        lockScreenBypassCallbacks.clear()
        eventHandler = null
        cachedEvents.clear()

        // Reset initialization
        isInitialized.set(false)
        appContext = null
    }
}
