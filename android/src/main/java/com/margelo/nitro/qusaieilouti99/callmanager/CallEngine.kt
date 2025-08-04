package com.margelo.nitro.qusaieilouti99.callmanager
import android.telecom.CallAudioState
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
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import android.app.KeyguardManager
import android.os.Vibrator
import android.os.VibrationEffect

/**
 * Core call‐management engine. Manages self-managed telecom calls,
 * audio routing, UI notifications, etc.
 *
 * Audio routing follows Android standards:
 * - Audio calls default to earpiece unless BT/headset connected
 * - Video calls default to speaker unless BT/headset connected
 * - System handles route changes when devices connect/disconnect
 * - Manual route changes are always respected
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

  private var currentCallId: String? = null
  private var canMakeMultipleCalls: Boolean = false
  private var lockScreenBypassActive = false
  private val lockScreenBypassCallbacks = mutableSetOf<LockScreenBypassCallback>()
  private var eventHandler: ((CallEventType, String) -> Unit)? = null
  private val cachedEvents = mutableListOf<Pair<CallEventType, String>>()

  // Audio routing state
  private var currentAudioRoute: String = "Earpiece"
  private var wasManuallySet: Boolean = false
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
    Log.d(TAG, "Removed Telecom Connection for callId: $callId")
  }

  fun getTelecomConnection(callId: String): Connection? = telecomConnections[callId]

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
    if (incomingCall != null && incomingCall.callId != callId) {
      Log.d(TAG, "Incoming call collision detected. Auto-rejecting new call: $callId")
      rejectIncomingCallCollision(callId, "Another call is already incoming")
      return
    }

    val activeCall = activeCalls.values.find {
      it.state == CallState.ACTIVE || it.state == CallState.HELD
    }
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

    setAudioMode()
    registerPhoneAccount()

    val telecomManager =
      context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
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
      startRingback()
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

    activeCalls[callId] =
      CallInfo(callId, callType, targetName, null, CallState.ACTIVE)
    currentCallId = callId
    callStartTime = System.currentTimeMillis()
    wasManuallySet = false
    Log.d(TAG, "Call $callId started as ACTIVE")

    registerPhoneAccount()
    setAudioMode()
    bringAppToForeground()
    startForegroundService()
    keepScreenAwake(true)

    // Register audio device callback to handle dynamic device changes
    registerAudioDeviceCallback()

    // Set initial audio route based on call type and available devices
    mainHandler.postDelayed({
      setInitialAudioRoute(callType)
    }, 500L)

    updateLockScreenBypass()
    emitOutgoingCallAnsweredWithMetadata(callId)
  }

  fun callAnsweredFromJS(callId: String) {
    Log.d(TAG, "callAnsweredFromJS: $callId - remote party answered")
    coreCallAnswered(callId, isLocalAnswer = false)
  }

  fun answerCall(callId: String) {
    Log.d(TAG, "answerCall: $callId - local party answering")
    coreCallAnswered(callId, isLocalAnswer = true)
  }

  private fun coreCallAnswered(callId: String, isLocalAnswer: Boolean) {
    Log.d(TAG, "coreCallAnswered: $callId, isLocalAnswer: $isLocalAnswer")
    val callInfo = activeCalls[callId]
    if (callInfo == null) {
      Log.w(TAG, "Cannot answer call $callId - not found in active calls")
      return
    }

    activeCalls[callId] = callInfo.copy(state = CallState.ACTIVE)
    currentCallId = callId
    callStartTime = System.currentTimeMillis()
    wasManuallySet = false
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

    // Register audio device callback to handle dynamic device changes
    registerAudioDeviceCallback()

    // Set initial audio route with proper timing
    mainHandler.postDelayed({
      setInitialAudioRoute(callInfo.callType)
    }, 800L)

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

  // ====== IMPROVED AUDIO ROUTING SYSTEM ======

  fun getAudioDevices(): AudioRoutesInfo {
    val context = requireContext()
    audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
      ?: return AudioRoutesInfo(emptyArray(), "Unknown")

    val devices = mutableSetOf<String>()
    var hasWiredHeadset = false
    var hasBluetoothDevice = false

    // Always available
    devices.add("Earpiece")
    devices.add("Speaker")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val outputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
      for (device in outputDevices) {
        when (device.type) {
          AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
          AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
            if (!hasBluetoothDevice) {
              devices.add("Bluetooth")
              hasBluetoothDevice = true
            }
          }
          AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
          AudioDeviceInfo.TYPE_WIRED_HEADSET,
          AudioDeviceInfo.TYPE_USB_HEADSET -> {
            if (!hasWiredHeadset) {
              devices.add("Headset")
              hasWiredHeadset = true
            }
          }
          AudioDeviceInfo.TYPE_BLE_HEADSET -> {
            if (!hasBluetoothDevice) {
              devices.add("Bluetooth")
              hasBluetoothDevice = true
            }
          }
        }
      }
    } else {
      // Fallback for older API levels
      @Suppress("DEPRECATION")
      if (audioManager?.isBluetoothA2dpOn == true || audioManager?.isBluetoothScoOn == true) {
        devices.add("Bluetooth")
        hasBluetoothDevice = true
      }
      @Suppress("DEPRECATION")
      if (audioManager?.isWiredHeadsetOn == true) {
        devices.add("Headset")
        hasWiredHeadset = true
      }
    }

    val current = getCurrentAudioRoute()
    Log.d(TAG, "Available audio devices: ${devices.toList()}, current: $current")

    val deviceHolders = devices.map { StringHolder(it) }.toTypedArray()
    return AudioRoutesInfo(deviceHolders, current)
  }

  // NEW: Handle telecom audio route changes
  fun onTelecomAudioRouteChanged(callId: String, audioState: CallAudioState) {
      Log.d(TAG, "Telecom audio route changed for $callId: route=${audioState.route}")

      val routeString = when (audioState.route) {
          CallAudioState.ROUTE_EARPIECE -> "Earpiece"
          CallAudioState.ROUTE_SPEAKER -> "Speaker"
          CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
          CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
          else -> "Unknown"
      }

      Log.d(TAG, "Emitting AUDIO_ROUTE_CHANGED: currentRoute=$routeString")
      emitAudioRouteChanged(routeString)
  }

  // NEW: Set initial audio route using telecom
  fun setInitialAudioRouteForCall(callId: String, callType: String) {
      Log.d(TAG, "Setting initial audio route for $callId, type: $callType")

      // Determine the desired route
      val desiredRoute = when {
          isBluetoothDeviceConnected() -> CallAudioState.ROUTE_BLUETOOTH
          isWiredHeadsetConnected() -> CallAudioState.ROUTE_WIRED_HEADSET
          callType.equals("Video", ignoreCase = true) -> CallAudioState.ROUTE_SPEAKER
          else -> CallAudioState.ROUTE_EARPIECE
      }

      // Use telecom connection to set the route
      telecomConnections[callId]?.let { connection ->
          if (connection is MyConnection) {
              mainHandler.postDelayed({
                  connection.setTelecomAudioRoute(desiredRoute)
                  Log.d(TAG, "Set initial telecom audio route to: $desiredRoute")
              }, 200)
          }
      }
  }

 // UPDATED: Use telecom for manual route changes
  fun setAudioRoute(route: String) {
     Log.d(TAG, "setAudioRoute called: $route (manual)")
     wasManuallySet = true

     val telecomRoute = when (route) {
         "Speaker" -> CallAudioState.ROUTE_SPEAKER
         "Earpiece" -> CallAudioState.ROUTE_EARPIECE
         "Bluetooth" -> CallAudioState.ROUTE_BLUETOOTH
         "Headset" -> CallAudioState.ROUTE_WIRED_HEADSET
         else -> {
             Log.w(TAG, "Unknown audio route: $route")
             return
         }
     }

     // Set route through active telecom connection
     currentCallId?.let { callId ->
         telecomConnections[callId]?.let { connection ->
             if (connection is MyConnection) {
                 connection.setTelecomAudioRoute(telecomRoute)
                 Log.d(TAG, "Set telecom audio route to: $telecomRoute for $route")
             }
         }
     }
 }

  private fun applyAudioRoute(route: String) {
    val ctx = requireContext()
    if (audioManager == null) {
      audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val am = audioManager!!

    // Ensure we're in the correct audio mode
    if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
      am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    val previousRoute = currentAudioRoute

    when (route) {
      "Speaker" -> {
        am.isSpeakerphoneOn = true
        if (am.isBluetoothScoOn) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        }
        currentAudioRoute = "Speaker"
        Log.d(TAG, "Audio route set to SPEAKER")
      }
      "Earpiece" -> {
        am.isSpeakerphoneOn = false
        if (am.isBluetoothScoOn) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        }
        currentAudioRoute = "Earpiece"
        Log.d(TAG, "Audio route set to EARPIECE")
      }
      "Bluetooth" -> {
        am.isSpeakerphoneOn = false
        if (!am.isBluetoothScoOn) {
          am.startBluetoothSco()
          am.isBluetoothScoOn = true
        }
        currentAudioRoute = "Bluetooth"
        Log.d(TAG, "Audio route set to BLUETOOTH")
      }
      "Headset" -> {
        am.isSpeakerphoneOn = false
        if (am.isBluetoothScoOn) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        }
        // For wired headsets, the system automatically routes audio when connected
        currentAudioRoute = "Headset"
        Log.d(TAG, "Audio route set to HEADSET")
      }
      else -> {
        Log.w(TAG, "Unknown audio route: $route")
        return
      }
    }

    // Only emit event if route actually changed
    if (currentAudioRoute != previousRoute) {
      emitAudioRouteChanged(currentAudioRoute)
    }
  }

  private fun getCurrentAudioRoute(): String {
    val am = audioManager ?: return "Unknown"

    // Check in order of priority: Bluetooth -> Headset -> Speaker -> Earpiece
    return when {
      am.isBluetoothScoOn -> "Bluetooth"
      isWiredHeadsetConnected() -> "Headset"
      am.isSpeakerphoneOn -> "Speaker"
      else -> "Earpiece"
    }
  }

  private fun isWiredHeadsetConnected(): Boolean {
    val am = audioManager ?: return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      return devices.any { device ->
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        device.type == AudioDeviceInfo.TYPE_USB_HEADSET
      }
    } else {
      @Suppress("DEPRECATION")
      return am.isWiredHeadsetOn
    }
  }

  private fun isBluetoothDeviceConnected(): Boolean {
    val am = audioManager ?: return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
      return devices.any { device ->
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
      }
    } else {
      @Suppress("DEPRECATION")
      return am.isBluetoothA2dpOn || am.isBluetoothScoOn
    }
  }

  private fun setInitialAudioRoute(callType: String) {
    Log.d(TAG, "Setting initial audio route for call type: $callType")

    // Don't override if user manually set a route
    if (wasManuallySet) {
      Log.d(TAG, "Audio route was manually set, skipping initial route")
      return
    }

    // Determine default route based on Android standards
    val defaultRoute = when {
      isBluetoothDeviceConnected() -> "Bluetooth"
      isWiredHeadsetConnected() -> "Headset"
      callType.equals("Video", ignoreCase = true) -> "Speaker"
      else -> "Earpiece" // Default for audio calls
    }

    Log.d(TAG, "Setting initial audio route to: $defaultRoute")
    applyAudioRoute(defaultRoute)
  }

  private fun setAudioMode() {
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION")
  }

  private fun resetAudioMode() {
    if (activeCalls.isEmpty()) {
      audioManager?.let { am ->
        am.mode = AudioManager.MODE_NORMAL
        if (am.isBluetoothScoOn) {
          am.stopBluetoothSco()
          am.isBluetoothScoOn = false
        }
        am.isSpeakerphoneOn = false
      }
      currentAudioRoute = "Earpiece"
      wasManuallySet = false
      unregisterAudioDeviceCallback()
      Log.d(TAG, "Audio mode reset to MODE_NORMAL")
    }
  }

  // UPDATED: Fix the method signature
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

  private val audioDeviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
      Log.d(TAG, "Audio devices added")
      handleAudioDeviceChange(addedDevices, true)
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
      Log.d(TAG, "Audio devices removed")
      handleAudioDeviceChange(removedDevices, false)
    }
  }

  private fun handleAudioDeviceChange(devices: Array<out AudioDeviceInfo>?, isAdded: Boolean) {
    if (devices == null || !isCallActive()) return

    val context = requireContext()
    val currentCallInfo = getCurrentActiveCall()
    if (currentCallInfo == null) {
      Log.d(TAG, "No active call, ignoring device change")
      return
    }

    val relevantDevices = devices.filter { device ->
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
      device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
      device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
      device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
      device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
      device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    if (relevantDevices.isEmpty()) {
      Log.d(TAG, "No relevant devices in change event")
      return
    }

    Log.d(TAG, "Relevant device change detected. Added: $isAdded, wasManuallySet: $wasManuallySet")

    if (isAdded && !wasManuallySet) {
      // Device connected - switch to it automatically if user hasn't manually set route
      val deviceType = relevantDevices.first().type
      val newRoute = when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset"
        else -> null
      }

      if (newRoute != null && newRoute != currentAudioRoute) {
        Log.d(TAG, "Auto-switching to newly connected device: $newRoute")
        // Add slight delay to ensure device is ready
        mainHandler.postDelayed({
          applyAudioRoute(newRoute)
        }, 300)
      }
    } else if (!isAdded) {
      // Device disconnected - fall back to appropriate route
      val disconnectedType = relevantDevices.first().type
      val wasCurrentRoute = when (disconnectedType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> currentAudioRoute == "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> currentAudioRoute == "Headset"
        else -> false
      }

      if (wasCurrentRoute) {
        Log.d(TAG, "Current audio device disconnected, falling back")
        // Reset manual flag since the manually selected device is gone
        wasManuallySet = false
        mainHandler.postDelayed({
          setInitialAudioRoute(currentCallInfo.callType)
        }, 300)
      }
    }

    // Always emit devices changed event
    emitAudioDevicesChanged()
  }

  private fun getCurrentActiveCall(): CallInfo? {
    return activeCalls.values.find { it.state == CallState.ACTIVE }
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

  fun registerAudioDeviceCallback() {
    if (isCallActive()) {
      val context = requireContext()
      audioManager = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      try {
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        Log.d(TAG, "Audio device callback registered")
      } catch (e: Exception) {
        Log.w(TAG, "Failed to register audio device callback: ${e.message}")
      }
    }
  }

  fun unregisterAudioDeviceCallback() {
    try {
      audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
      Log.d(TAG, "Audio device callback unregistered")
    } catch (e: Exception) {
      Log.w(TAG, "Failed to unregister audio device callback: ${e.message}")
    }
  }

  // ====== END AUDIO ROUTING SYSTEM ======

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

  private fun showIncomingCallUI(callId: String, callerName: String, callType: String, callerPicUrl: String?) {
    val context = requireContext()
    Log.d(TAG, "Showing incoming call UI for $callId")

    val useCallStyleNotification = supportsCallStyleNotifications()
    Log.d(TAG, "Using CallStyle notification: $useCallStyleNotification")

    if (isDeviceLocked(context) || !useCallStyleNotification) {
      Log.d(TAG, "Device is locked or CallStyle not supported - using overlay/fallback approach")
      showCallActivityOverlay(context, callId, callerName, callType, callerPicUrl)
    } else {
      Log.d(TAG, "Device is unlocked and supports CallStyle - using enhanced notification")
      showStandardNotification(context, callId, callerName, callType, callerPicUrl)
    }
    playRingtone()
  }

  private fun isDeviceLocked(context: Context): Boolean {
    val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    return keyguardManager.isKeyguardLocked
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      try {
        val tasks = activityManager.appTasks
        if (tasks.isNotEmpty()) {
          val taskInfo = tasks[0].taskInfo
          return taskInfo.topActivity?.className?.contains("MainActivity") == true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to get app tasks: ${e.message}")
      }
    } else {
      try {
        @Suppress("DEPRECATION")
        val tasks = activityManager.getRunningTasks(1)
        if (tasks.isNotEmpty()) {
          val runningTaskInfo = tasks[0]
          return runningTaskInfo.topActivity?.className?.contains("MainActivity") == true
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to get running tasks: ${e.message}")
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
    currentCallId = null
    cleanup()
    lockScreenBypassCallbacks.clear()
    eventHandler = null
    cachedEvents.clear()
    isInitialized.set(false)
    appContext = null
  }
}
