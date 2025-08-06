package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import android.app.KeyguardManager
import java.util.UUID
import android.provider.Settings // Import for Settings.canDrawOverlays and ACTION_MANAGE_OVERLAY_PERMISSION

/**
 * Core call‐management engine. Manages self-managed telecom calls,
 * audio routing, UI notifications, etc.
 *
 * Audio routing now primarily leverages Android Telecom's CallEndpoint API (API 34+),
 * allowing the system to manage the underlying audio device changes.
 */
object CallEngine {
  private const val TAG = "CallEngine"
  private const val PHONE_ACCOUNT_ID = "com.qusaieilouti99.callmanager.SELF_MANAGED"
  private const val NOTIF_CHANNEL_ID = "incoming_call_channel"
  private const val NOTIF_ID = 2001

  interface CallEndListener {
    fun onCallEnded(callId: String)
  }

  private val callEndListeners = CopyOnWriteArrayList<CallEndListener>()
  private val mainHandler = Handler(Looper.getMainLooper())

  fun registerCallEndListener(l: CallEndListener) {
    callEndListeners.add(l)
  }

  fun unregisterCallEndListener(l: CallEndListener) {
    callEndListeners.remove(l)
  }

  @Volatile private var appContext: Context? = null
  private val isInitialized = AtomicBoolean(false)
  private val initializationLock = Any()

  private var ringtone: android.media.Ringtone? = null
  private var ringbackPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private var audioManager: AudioManager? = null
  private var wakeLock: PowerManager.WakeLock? = null

  private val activeCalls = ConcurrentHashMap<String, CallInfo>()
  private val telecomConnections = ConcurrentHashMap<String, Connection>()
  private val callMetadata = ConcurrentHashMap<String, String>()
  private val callAnswerStates = ConcurrentHashMap<String, Boolean>()

  private var currentCallId: String? = null
  private var canMakeMultipleCalls: Boolean = false
  private var lockScreenBypassActive = false
  private val lockScreenBypassCallbacks = mutableSetOf<LockScreenBypassCallback>()
  private var eventHandler: ((CallEventType, String) -> Unit)? = null
  private val cachedEvents = mutableListOf<Pair<CallEventType, String>>()

  // Audio routing state for CallEndpoint API (API 34+)
  private var currentActiveCallEndpoint: CallEndpoint? = null
  private var availableCallEndpoints: List<CallEndpoint> = emptyList()
  private var wasManuallySetAudioRoute: Boolean = false
  private var callStartTime: Long = 0

  interface LockScreenBypassCallback {
    fun onLockScreenBypassChanged(shouldBypass: Boolean)
  }

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

  fun getContext(): Context? = appContext

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

  private fun supportsCallStyleNotifications(): Boolean {
    // CallStyle notifications are available from Android S (API 31)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()

    val supportedManufacturers = setOf(
      "google", "samsung", "oneplus", "motorola", "sony", "lg", "htc"
    )

    val supportedBrands = setOf(
      "google", "samsung", "oneplus", "motorola", "sony", "lg", "htc", "pixel"
    )

    val isSupported = supportedManufacturers.contains(manufacturer) ||
                     supportedBrands.contains(brand) ||
                     manufacturer.contains("google") ||
                     brand.contains("pixel")

    Log.d(TAG, "CallStyle support check - Manufacturer: $manufacturer, Brand: $brand, Supported: $isSupported")
    return isSupported
  }

  fun silenceIncomingCall() {
    Log.d(TAG, "Silencing incoming call ringtone via Connection.onSilence()")
    stopRingtone()
  }

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

  fun addTelecomConnection(callId: String, connection: Connection) {
    telecomConnections[callId] = connection
    Log.d(TAG, "Added Telecom Connection for callId: $callId")
  }

  fun removeTelecomConnection(callId: String) {
    telecomConnections.remove(callId)
    callAnswerStates.remove(callId) // Clean up answer state
    Log.d(TAG, "Removed Telecom Connection for callId: $callId")
  }

  fun getTelecomConnection(callId: String): Connection? = telecomConnections[callId]

  fun getCallAnswerState(callId: String): Boolean? = callAnswerStates.remove(callId)

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

    val incomingCall = activeCalls.values.find { it.state == CallState.INCOMING }
    if (incomingCall != null) {
      Log.d(TAG, "Incoming call collision detected. Auto-rejecting new call: $callId")
      rejectIncomingCallCollision(callId, "Another call is already incoming")
      return
    }

    val activeOrHeldCall = activeCalls.values.find {
      it.state == CallState.ACTIVE || it.state == CallState.HELD
    }
    if (activeOrHeldCall != null && !canMakeMultipleCalls) {
      Log.d(TAG, "Active/Held call exists when receiving incoming call. Auto-rejecting: $callId")
      rejectIncomingCallCollision(callId, "Another call is already active or held")
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

    activeCalls[callId] =
      CallInfo(callId, callType, displayName, pictureUrl, CallState.INCOMING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. State: INCOMING")

    showIncomingCallUI(callId, displayName, callType, pictureUrl)
    registerPhoneAccount()

    val telecomManager =
      requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()
    val extras = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, displayName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      pictureUrl?.let { putString(MyConnectionService.EXTRA_PICTURE_URL, it) }
      // TelecomManager.EXTRA_INCOMING_VIDEO_STATE is used here to hint the video state to Telecom
      putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
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

    activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.DIALING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. State: DIALING")

    registerPhoneAccount()

    val telecomManager =
      context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()
    val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, targetName, null)

    val outgoingExtrasForConnectionService = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, targetName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      metadata?.let { putString("metadata", it) }
    }

    val placeCallExtras = Bundle().apply {
      putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
      putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtrasForConnectionService)
      // Hint to Telecom whether to start with speakerphone based on video call type
      putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCall)
      putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    try {
      telecomManager.placeCall(addressUri, placeCallExtras)
      startForegroundService()
      bringAppToForeground()
      keepScreenAwake(true)
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

    val existingCallInfo = activeCalls[callId]
    if (existingCallInfo != null && existingCallInfo.state == CallState.INCOMING) {
      // Scenario 1: Call with this ID is already incoming, answer it.
      Log.d(TAG, "Call $callId is incoming, answering it directly via startCall.")
      answerCall(callId, isLocalAnswer = false) // Remote party answered
      return
    }

    // Scenario 2: Call is new or not incoming, treat as new outgoing call that is immediately active.
    Log.d(TAG, "Call $callId is new or not incoming. Initiating as outgoing and immediately active.")
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
        if (!validateOutgoingCallRequest()) {
            Log.w(TAG, "Rejecting startCall as outgoing - incoming/active call exists")
            emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
                put("callId", callId)
                put("reason", "Cannot start new active call while incoming or active call exists")
            })
            return
        }
    }

    val isVideoCall = callType == "Video"
    if (!canMakeMultipleCalls && activeCalls.isNotEmpty()) {
      activeCalls.values.forEach {
        if (it.state == CallState.ACTIVE) {
          holdCallInternal(it.callId, heldBySystem = false)
        }
      }
    }

    activeCalls[callId] = CallInfo(callId, callType, targetName, null, CallState.DIALING)
    currentCallId = callId
    Log.d(TAG, "Call $callId added to activeCalls. Initial state: DIALING (for Telecom)")

    registerPhoneAccount()

    val telecomManager = requireContext().getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    val phoneAccountHandle = getPhoneAccountHandle()
    val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, targetName, null)

    val outgoingExtrasForConnectionService = Bundle().apply {
      putString(MyConnectionService.EXTRA_CALL_ID, callId)
      putString(MyConnectionService.EXTRA_CALL_TYPE, callType)
      putString(MyConnectionService.EXTRA_DISPLAY_NAME, targetName)
      putBoolean(MyConnectionService.EXTRA_IS_VIDEO_CALL_BOOLEAN, isVideoCall)
      metadata?.let { putString("metadata", it) }
    }

    val placeCallExtras = Bundle().apply {
      putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
      putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtrasForConnectionService)
      putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, isVideoCall)
      putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, if (isVideoCall) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    try {
      telecomManager.placeCall(addressUri, placeCallExtras)
      startForegroundService()
      bringAppToForeground()
      keepScreenAwake(true)
      Log.d(TAG, "Successfully reported outgoing call (to be immediately active) to TelecomManager for $callId")

      // Immediately mark as answered for "startCall" behavior
      answerCall(callId, isLocalAnswer = false) // Remote party answered
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start call as active: ${e.message}", e)
      endCallInternal(callId)
    }
    updateLockScreenBypass()
  }

  // NEW UNIFIED ANSWER METHOD
  fun answerCall(callId: String, isLocalAnswer: Boolean = true) {
    Log.d(TAG, "answerCall: $callId, isLocalAnswer: $isLocalAnswer")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot answer call $callId - not found in active calls")
      return
    }

    // Store the isLocalAnswer state for the connection to use
    callAnswerStates[callId] = isLocalAnswer

    // Always call connection.onAnswer() to let Telecom handle the flow
    telecomConnections[callId]?.let { connection ->
      connection.onAnswer() // This will trigger MyConnection.onAnswer()
    } ?: run {
      Log.w(TAG, "No telecom connection found for $callId, falling back to direct answer")
      coreCallAnswered(callId, isLocalAnswer)
    }
  }

  // INTERNAL METHOD called by MyConnection.onAnswer()
  internal fun coreCallAnswered(callId: String, isLocalAnswer: Boolean) {
    Log.d(TAG, "coreCallAnswered: $callId, isLocalAnswer: $isLocalAnswer")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot answer call $callId - not found in active calls")
      return
    }

    activeCalls[callId] = callInfo.copy(state = CallState.ACTIVE)
    currentCallId = callId
    callStartTime = System.currentTimeMillis()
    wasManuallySetAudioRoute = false
    Log.d(TAG, "Call $callId set to ACTIVE state")

    stopRingtone()
    stopRingback()
    cancelIncomingCallUI()

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

    setAudioMode()

    // Set initial audio route using Telecom's CallEndpoint API
    setInitialCallAudioRoute(callId, callInfo.callType)

    if (isLocalAnswer) {
      emitCallAnsweredWithMetadata(callId)
    } else {
      emitOutgoingCallAnsweredWithMetadata(callId)
    }

    Log.d(TAG, "Call $callId successfully answered")
  }

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
    Log.d(TAG, "AudioManager microphone mute set to: $muted")

    if (wasMuted != muted) {
      val eventType = if (muted) CallEventType.CALL_MUTED else CallEventType.CALL_UNMUTED
      emitEvent(eventType, JSONObject().put("callId", callId))
      Log.d(TAG, "Call $callId mute state changed to: $muted")
    }
  }

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
    callAnswerStates.clear()
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
    activeCalls.remove(callId)
    callAnswerStates.remove(callId) // Clean up answer state

    stopRingback()
    stopRingtone()
    cancelIncomingCallUI()

    if (currentCallId == callId) {
      currentCallId =
        activeCalls.filter { it.value.state != CallState.ENDED }.keys.firstOrNull()
    }

    val context = requireContext()
    val closeActivityIntent = Intent("com.qusaieilouti99.callmanager.CLOSE_CALL_ACTIVITY")
      .setPackage(context.packageName)
      .putExtra("callId", callId)

    try {
      context.sendBroadcast(closeActivityIntent)
      Log.d(TAG, "Sent close broadcast for CallActivity: $callId")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to send close broadcast: ${e.message}")
    }

    telecomConnections[callId]?.let { connection ->
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

    for (listener in callEndListeners) {
      mainHandler.post {
        try {
          listener.onCallEnded(callId)
        } catch (_: Throwable) {
          // swallow
        }
      }
    }

    emitEvent(CallEventType.CALL_ENDED, JSONObject().apply {
      put("callId", callId)
      metadata?.let {
        try { put("metadata", JSONObject(it)) }
        catch (e: Exception) { put("metadata", it) }
      }
    })
  }

  // ====== IMPROVED AUDIO ROUTING SYSTEM (using CallEndpoint API) ======

  fun onTelecomAvailableEndpointsChanged(endpoints: List<CallEndpoint>) {
      availableCallEndpoints = endpoints
      Log.d(TAG, "Available CallEndpoints updated: ${endpoints.map { "${it.endpointName}(${mapCallEndpointTypeToString(it.endpointType)})" }}")
      emitAudioDevicesChanged()
  }

  fun onTelecomAudioRouteChanged(callId: String, callEndpoint: CallEndpoint) {
      Log.d(TAG, "Telecom audio route changed for $callId: endpoint=${callEndpoint.endpointName} (type=${mapCallEndpointTypeToString(callEndpoint.endpointType)})")
      currentActiveCallEndpoint = callEndpoint
      emitAudioRouteChanged(mapCallEndpointTypeToString(callEndpoint.endpointType))
  }

  fun getAudioDevices(): AudioRoutesInfo {
    val devices = availableCallEndpoints.map { StringHolder(mapCallEndpointTypeToString(it.endpointType)) }.toMutableSet()

    if (!devices.any { it.value == "Earpiece" }) devices.add(StringHolder("Earpiece"))
    if (!devices.any { it.value == "Speaker" }) devices.add(StringHolder("Speaker"))

    val current = currentActiveCallEndpoint?.let { mapCallEndpointTypeToString(it.endpointType) } ?: "Unknown"
    Log.d(TAG, "Available audio devices: ${devices.toList()}, current: $current")

    return AudioRoutesInfo(devices.toTypedArray(), current)
  }

  fun setAudioRoute(route: String) {
     Log.d(TAG, "setAudioRoute called: $route (manual)")
     wasManuallySetAudioRoute = true

     val telecomEndpointType = mapStringToCallEndpointType(route)

     val targetEndpoint = availableCallEndpoints.find { it.endpointType == telecomEndpointType }
         ?: getOrCreateGenericCallEndpoint(telecomEndpointType, route)

     if (targetEndpoint != null) {
         currentCallId?.let { callId ->
             telecomConnections[callId]?.let { connection ->
                 if (connection is MyConnection) {
                     Log.d(TAG, "Requesting manual telecom audio route to: ${targetEndpoint.endpointName} (type: ${mapCallEndpointTypeToString(targetEndpoint.endpointType)})")
                     connection.setTelecomAudioRoute(targetEndpoint)
                 } else {
                    Log.w(TAG, "Telecom connection for $callId is not MyConnection instance.")
                 }
             } ?: Log.w(TAG, "No telecom connection found for $callId to set audio route.")
         } ?: Log.w(TAG, "No current call ID to set audio route.")
     } else {
         Log.w(TAG, "Could not find or create a valid CallEndpoint for manual route: $route (type: $telecomEndpointType)")
     }
 }

  fun setInitialCallAudioRoute(callId: String, callType: String) {
      Log.d(TAG, "Setting initial audio route for callId: $callId, type: $callType")

      if (wasManuallySetAudioRoute) {
          Log.d(TAG, "Audio route was manually set, skipping initial route setting.")
          return
      }

      val targetEndpointType = when {
          isBluetoothDeviceConnected() -> CallEndpoint.TYPE_BLUETOOTH
          isWiredHeadsetConnected() -> CallEndpoint.TYPE_WIRED_HEADSET
          callType.equals("Video", ignoreCase = true) -> CallEndpoint.TYPE_SPEAKER
          else -> CallEndpoint.TYPE_EARPIECE
      }

      val targetEndpoint = availableCallEndpoints.find { it.endpointType == targetEndpointType }
          ?: getOrCreateGenericCallEndpoint(targetEndpointType, mapCallEndpointTypeToString(targetEndpointType))

      if (targetEndpoint != null) {
          mainHandler.postDelayed({
              telecomConnections[callId]?.let { connection ->
                  if (connection is MyConnection) {
                      Log.d(TAG, "Requesting initial telecom audio route to: ${targetEndpoint.endpointName} (type: ${mapCallEndpointTypeToString(targetEndpoint.endpointType)})")
                      connection.setTelecomAudioRoute(targetEndpoint)
                  }
              } ?: Log.w(TAG, "No telecom connection found for $callId during initial route setting.")
          }, 500L)
      } else {
          Log.w(TAG, "Could not find or create a valid CallEndpoint for initial route type: $targetEndpointType")
      }
  }

  private fun setAudioMode() {
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION")
  }

  private fun resetAudioMode() {
    if (activeCalls.isEmpty()) {
      audioManager?.let { am ->
        am.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.isBluetoothScoOn) {
          am.stopBluetoothSco()
        }
        am.isSpeakerphoneOn = false
      }
      currentActiveCallEndpoint = null
      availableCallEndpoints = emptyList()
      wasManuallySetAudioRoute = false
      Log.d(TAG, "Audio mode reset to MODE_NORMAL, audio endpoints reset.")
    }
  }

  private fun mapCallEndpointTypeToString(type: Int): String {
      return when (type) {
          CallEndpoint.TYPE_EARPIECE -> "Earpiece"
          CallEndpoint.TYPE_SPEAKER -> "Speaker"
          CallEndpoint.TYPE_BLUETOOTH -> "Bluetooth"
          CallEndpoint.TYPE_WIRED_HEADSET -> "Headset"
          CallEndpoint.TYPE_STREAMING -> "Streaming"
          else -> "Unknown"
      }
  }

  private fun mapStringToCallEndpointType(typeString: String): Int {
      return when (typeString) {
          "Earpiece" -> CallEndpoint.TYPE_EARPIECE
          "Speaker" -> CallEndpoint.TYPE_SPEAKER
          "Bluetooth" -> CallEndpoint.TYPE_BLUETOOTH
          "Headset" -> CallEndpoint.TYPE_WIRED_HEADSET
          "Streaming" -> CallEndpoint.TYPE_STREAMING
          else -> CallEndpoint.TYPE_UNKNOWN
      }
  }

  private fun getOrCreateGenericCallEndpoint(type: Int, name: String): CallEndpoint? {
      return when (type) {
          CallEndpoint.TYPE_EARPIECE -> CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Earpiece_Default".toByteArray())))
          CallEndpoint.TYPE_SPEAKER -> CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Speaker_Default".toByteArray())))
          CallEndpoint.TYPE_BLUETOOTH -> CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Bluetooth_Default".toByteArray())))
          CallEndpoint.TYPE_WIRED_HEADSET -> CallEndpoint(name, type, ParcelUuid(UUID.nameUUIDFromBytes("Headset_Default".toByteArray())))
          else -> null
      }
  }

  private fun isWiredHeadsetConnected(): Boolean {
    val am = audioManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      devices.any { device ->
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
      }
    } else {
        @Suppress("DEPRECATION")
        am.isWiredHeadsetOn
    }
  }

  private fun isBluetoothDeviceConnected(): Boolean {
    val am = audioManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      devices.any { device ->
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        device.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
      }
    } else {
        @Suppress("DEPRECATION")
        am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }
  }

  private fun emitAudioRouteChanged(currentRoute: String) {
      val info = getAudioDevices()
      val deviceStrings = info.devices.map { it.value }
      val payload = JSONObject().apply {
          put("devices", JSONArray(deviceStrings))
          put("currentRoute", currentRoute)
      }
      emitEvent(CallEventType.AUDIO_ROUTE_CHANGED, payload)
      Log.d(TAG, "Audio route changed: $currentRoute, available: $deviceStrings")
  }

  private fun emitAudioDevicesChanged() {
    val info = getAudioDevices()
    val deviceStrings = info.devices.map { it.value }
    val payload = JSONObject().apply {
      put("devices", JSONArray(deviceStrings))
      put("currentRoute", info.currentRoute)
    }
    emitEvent(CallEventType.AUDIO_DEVICES_CHANGED, payload)
    Log.d(TAG, "Audio devices changed: available: $deviceStrings")
  }

  // ====== END IMPROVED AUDIO ROUTING SYSTEM ======

  fun keepScreenAwake(keepAwake: Boolean) {
    val context = requireContext()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (keepAwake) {
      if (wakeLock == null || wakeLock!!.isHeld.not()) {
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
      (!canMakeMultipleCalls && (it.value.state == CallState.INCOMING || it.value.state == CallState.ACTIVE))
    }
  }

  private fun rejectIncomingCallCollision(callId: String, reason: String) {
    emitEvent(CallEventType.CALL_REJECTED, JSONObject().apply {
      put("callId", callId)
      put("reason", reason)
    })

    val existingCall = activeCalls[callId]
    if (existingCall == null) {
      callMetadata.remove(callId)
      Log.d(TAG, "Removed metadata for rejected call $callId (no existing call)")
    } else {
      Log.d(TAG, "Kept metadata for callId: $callId (existing call: ${existingCall.state})")
    }
  }

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
      channel.setBypassDnd(true)
      channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
      channel.setSound(null, null)
      channel.importance = NotificationManager.IMPORTANCE_HIGH

      val manager = context.getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  private fun showIncomingCallUI(callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    val context = requireContext()
    Log.d(TAG, "Showing incoming call UI for $callId")

    val useCallStyleNotification = supportsCallStyleNotifications()
    Log.d(TAG, "Using CallStyle notification: $useCallStyleNotification")

    val isDeviceLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.isKeyguardLocked
    } else {
        false
    }

    if (isDeviceLocked || !useCallStyleNotification) {
      Log.d(TAG, "Device is locked or CallStyle not supported/preferred - using overlay/fallback approach")
      showCallActivityOverlay(context, callId, callerName, callType, callerPicUrl)
    } else {
      Log.d(TAG, "Device is unlocked and supports CallStyle - using enhanced notification")
      showStandardNotification(context, callId, callerName, callType, callerPicUrl)
    }
    playRingtone()
  }

  private fun showCallActivityOverlay(context: Context, callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    val overlayIntent = Intent(context, CallActivity::class.java).apply {
      addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_NO_ANIMATION or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
      )
      putExtra("callId", callId)
      putExtra("callerName", callerName)
      putExtra("callType", callType)
      callerPicUrl?.let { putExtra("callerAvatar", it) }
      putExtra("LOCK_SCREEN_MODE", true)
    }

    try {
      // For SYSTEM_ALERT_WINDOW usage, the permission must be granted.
      // We are checking it in requestOverlayPermission.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
          Log.w(TAG, "Cannot show CallActivity overlay without SYSTEM_ALERT_WINDOW permission. Launching permission request.")
          // Instead of starting the activity which will fail, launch the permission request.
          requestOverlayPermission()
          showStandardNotification(context, callId, callerName, callType, callerPicUrl)
          return
      }

      val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "CallEngine:LockScreenWake"
      )
      wakeLock.acquire(5000)
      context.startActivity(overlayIntent)
      Log.d(TAG, "Successfully launched CallActivity overlay")
    } catch (e: Exception) {
      Log.e(TAG, "Overlay failed, falling back to standard notification: ${e.message}")
      showStandardNotification(context, callId, callerName, callType, callerPicUrl)
    }
  }

  private fun showStandardNotification(context: Context, callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    createNotificationChannel()
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    val fullScreenIntent = Intent(context, CallActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra("callId", callId)
      putExtra("callerName", callerName)
      putExtra("callType", callType)
      callerPicUrl?.let { putExtra("callerAvatar", it) }
    }

    val fullScreenPendingIntent = PendingIntent.getActivity(
      context, callId.hashCode(), fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

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

    val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsCallStyleNotifications()) {
      val person = android.app.Person.Builder()
        .setName(callerName)
        .setImportant(true)
        .build()
      Notification.Builder(context, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setStyle(
          Notification.CallStyle.forIncomingCall(
            person,
            declinePendingIntent,
            answerPendingIntent
          )
        )
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .setOngoing(true)
        .setAutoCancel(false)
        .setCategory(Notification.CATEGORY_CALL)
        .setPriority(Notification.PRIORITY_MAX)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setSound(null)
        .build()
    } else {
      Notification.Builder(context, NOTIF_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setContentTitle("Incoming Call")
        .setContentText(callerName)
        .setPriority(Notification.PRIORITY_MAX)
        .setCategory(Notification.CATEGORY_CALL)
        .setFullScreenIntent(fullScreenPendingIntent, true)
        .addAction(android.R.drawable.sym_action_call, "Answer", answerPendingIntent)
        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
        .setOngoing(true)
        .setAutoCancel(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        .build()
    }

    notificationManager.notify(NOTIF_ID, notification)
  }

  fun cancelIncomingCallUI() {
    val context = requireContext()
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancel(NOTIF_ID)
    stopRingtone()
  }

  private fun startForegroundService() {
    val context = requireContext()
    val currentCall = activeCalls.values.find {
      it.state == CallState.ACTIVE ||
      it.state == CallState.INCOMING ||
      it.state == CallState.DIALING ||
      it.state == CallState.HELD
    }

    val intent = Intent(context, CallForegroundService::class.java)
    currentCall?.let {
      intent.putExtra("callId", it.callId)
      intent.putExtra("callType", it.callType)
      intent.putExtra("displayName", it.displayName)
      intent.putExtra("state", it.state.name)
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
    startForegroundService()
  }

  private fun isMainActivityInForeground(): Boolean {
    val context = requireContext()
    val activityManager =
      context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
      try {
        val tasks = activityManager.appTasks
        if (tasks.isNotEmpty()) {
          val topActivityComponentName = tasks[0].taskInfo.topActivity
          return topActivityComponentName?.className?.contains("MainActivity") == true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to get app tasks for foreground check: ${e.message}")
      }
    } else {
      @Suppress("DEPRECATION")
      try {
        val tasks = activityManager.getRunningTasks(1)
        if (tasks.isNotEmpty()) {
          val runningTaskInfo = tasks[0]
          return runningTaskInfo.topActivity?.className?.contains("MainActivity") == true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to get running tasks for foreground check (deprecated): ${e.message}")
      }
    }
    return false
  }

  private fun bringAppToForeground() {
    if (isMainActivityInForeground()) {
      Log.d(TAG, "MainActivity is already in foreground, skipping")
      return
    }

    Log.d(TAG, "Bringing app to foreground")
    val context = requireContext()
    val packageName = context.packageName
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    launchIntent?.addFlags(
      Intent.FLAG_ACTIVITY_NEW_TASK or
      Intent.FLAG_ACTIVITY_CLEAR_TOP or
      Intent.FLAG_ACTIVITY_SINGLE_TOP
    )

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

  private fun registerPhoneAccount() {
    val context = requireContext()
    val telecomManager =
      context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
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

  private fun playRingtone() {
    val context = requireContext()
    audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    audioManager?.mode = AudioManager.MODE_RINGTONE

    vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    vibrator?.let { v ->
      val pattern = longArrayOf(0L, 500L, 500L)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createWaveform(pattern, 0))
      } else {
        @Suppress("DEPRECATION")
        v.vibrate(pattern, 0)
      }
    }

    try {
      val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      ringtone = RingtoneManager.getRingtone(context, uri)
      ringtone?.play()
      Log.d(TAG, "Ringtone started playing")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to play ringtone", e)
    }
  }

  fun stopRingtone() {
    try {
      ringtone?.stop()
      Log.d(TAG, "Ringtone stopped")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping ringtone", e)
    }
    ringtone = null

    vibrator?.cancel()
    vibrator = null
  }

  private fun startRingback() {
    val context = requireContext()
    if (ringbackPlayer?.isPlaying == true) return

    try {
      val ringbackUri =
        Uri.parse("android.resource://${context.packageName}/raw/ringback_tone")
      ringbackPlayer = MediaPlayer.create(context, ringbackUri)
      ringbackPlayer?.apply {
        isLooping = true
        start()
      }
      Log.d(TAG, "Ringback tone started playing")
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

  private fun cleanup() {
    Log.d(TAG, "Performing cleanup")
    stopForegroundService()
    keepScreenAwake(false)
    resetAudioMode()
  }

  fun onApplicationTerminate() {
    Log.d(TAG, "Application terminating")
    activeCalls.keys.toList().forEach { callId ->
      telecomConnections[callId]?.let { conn ->
        conn.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        conn.destroy()
      }
    }
    activeCalls.clear()
    telecomConnections.clear()
    callMetadata.clear()
    callAnswerStates.clear()
    currentCallId = null
    cleanup()
    lockScreenBypassCallbacks.clear()
    eventHandler = null
    cachedEvents.clear()
    isInitialized.set(false)
    appContext = null
    currentActiveCallEndpoint = null
    availableCallEndpoints = emptyList()
    wasManuallySetAudioRoute = false
  }

  // --- New Function for SYSTEM_ALERT_WINDOW permission ---
  fun requestOverlayPermission(): Boolean {
    val context = requireContext()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!Settings.canDrawOverlays(context)) {
        Log.d(TAG, "SYSTEM_ALERT_WINDOW permission not granted. Requesting it.")
        try {
          val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName)
          ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Required when starting from non-Activity context
          }
          context.startActivity(intent)
          Log.d(TAG, "Launched SYSTEM_ALERT_WINDOW permission settings.")
          false // Not granted yet, user needs to act
        } catch (e: Exception) {
          Log.e(TAG, "Failed to launch SYSTEM_ALERT_WINDOW permission settings: ${e.message}", e)
          false // Failed to launch, so not granted
        }
      } else {
        Log.d(TAG, "SYSTEM_ALERT_WINDOW permission already granted.")
        true
      }
    } else {
      // Permissions granted at install time for older Android versions
      Log.d(TAG, "SYSTEM_ALERT_WINDOW permission automatically granted on API < 23.")
      true
    }
  }
}
