package com.margelo.nitro.qusaieilouti99.callmanager

import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@DoNotStrip
class CallManager : HybridCallManagerSpec() {

    private val TAG = "CallManager"
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun ensureInitialized() {
        if (!CallEngine.isInitialized()) {
            Log.e(TAG, "CallEngine not initialized! This should not happen if Application.onCreate() was called properly.")
            throw IllegalStateException(
                "CallEngine must be initialized in Application.onCreate(). " +
                    "Make sure MainApplication.onCreate() calls CallEngine.initialize(this) before any native calls."
            )
        }
    }

    override fun endCall(callId: String): Unit {
        Log.d(TAG, "endCall requested for callId: $callId")
        ensureInitialized()
        CallEngine.endCall(callId)
    }

    override fun endAllCalls(): Unit {
        Log.d(TAG, "endAllCalls requested")
        ensureInitialized()
        CallEngine.endAllCalls()
    }

    override fun silenceRingtone(): Unit {
        Log.d(TAG, "silenceRingtone requested.")
        ensureInitialized()
        CallEngine.stopRingtone()
    }

    override fun getAudioDevices(): AudioRoutesInfo {
        Log.d(TAG, "getAudioDevices requested.")
        ensureInitialized()
        return CallEngine.getAudioDevices()
    }

    override fun setAudioRoute(route: String): Unit {
        Log.d(TAG, "setAudioRoute requested for route: $route")
        ensureInitialized()
        CallEngine.setAudioRoute(route)
    }

    override fun keepScreenAwake(keepAwake: Boolean): Unit {
        Log.d(TAG, "keepScreenAwake requested: $keepAwake")
        ensureInitialized()
        CallEngine.setIdleTimerDisabled(keepAwake)
    }

    override fun addCallListener(listener: (event: CallEventType, payload: String) -> Unit): Unit {
        Log.d(TAG, "addCallListener called")
        ensureInitialized()

        val wrapped: (CallEventType, String) -> Unit = { event, payload ->
            mainScope.launch {
                try {
                    listener(event, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing JS call listener", e)
                }
            }
        }

        CallEngine.setEventHandler(wrapped)
    }

    override fun removeCallListener(): Unit {
        Log.d(TAG, "removeCallListener called")
        ensureInitialized()
        CallEngine.setEventHandler(null)
    }

    override fun registerVoIPTokenListener(listener: (payload: String) -> Unit): Unit {
        Log.d(TAG, "registerVoIPTokenListener called")

    }

    override fun removeVoipTokenListener(): Unit {
        Log.d(TAG, "removeVoipTokenListener called")

    }

    override fun startOutgoingCall(callId: String, callType: String, targetName: String, metadata: String?): Unit {
        Log.d(TAG, "startOutgoingCall requested: callId=$callId, callType=$callType, targetName=$targetName")
        ensureInitialized()
        CallEngine.startOutgoingCall(callId, callType, targetName, metadata)
    }

    override fun startCall(callId: String, callType: String, targetName: String, metadata: String?): Unit {
        Log.d(TAG, "startCall requested: callId=$callId, callType=$callType, targetName=$targetName")
        ensureInitialized()
        CallEngine.startCall(callId, callType, targetName, metadata)
    }

    override fun callAnswered(callId: String): Unit {
        Log.d(TAG, "callAnswered (from JS) requested for callId: $callId")
        ensureInitialized()
        CallEngine.answerCall(callId, isLocalAnswer = false)
    }

    override fun setOnHold(callId: String, onHold: Boolean): Unit {
        Log.d(TAG, "setOnHold requested for callId: $callId, onHold: $onHold")
        ensureInitialized()
        CallEngine.setOnHold(callId, onHold)
    }

    override fun setMuted(callId: String, muted: Boolean): Unit {
        Log.d(TAG, "setMuted requested for callId: $callId, muted: $muted")
        ensureInitialized()
        CallEngine.setMuted(callId, muted)
    }

    override fun updateDisplayCallInformation(callId: String, callerName: String): Unit {
        // no-op
    }

    override fun reportIncomingCall(
        callId: String,
        callType: String,
        targetName: String,
        metadata: String?,
        token: String?,
        rejectEndpoint: String?
    ) {
        Log.d(TAG, "reportIncomingCall requested: callId=$callId, callType=$callType, targetName=$targetName")
        ensureInitialized()
        CallEngine.reportIncomingCall(
            requireNotNull(CallEngine.getContext()) { "CallEngine must be initialized with context" },
            callId,
            callType,
            targetName,
            null,
            metadata,
            token,
            rejectEndpoint
        )
    }

    override fun hasActiveCall(): Boolean {
        Log.d(TAG, "hasActiveCall (from JS)")
        ensureInitialized()
        return CallEngine.isCallActive()
    }

    override fun requestOverlayPermissionAndroid(): Boolean {
        Log.d(TAG, "requestOverlayPermissionAndroid requested (launch settings)")
        ensureInitialized()
        val ctx = requireNotNull(CallEngine.getContext()) { "CallEngine must be initialized with context" }
        return CallEngine.launchOverlayPermissionSettings(ctx)
    }

    override fun hasOverlayPermissionAndroid(): Boolean {
        Log.d(TAG, "hasOverlayPermissionAndroid requested (check only)")
        ensureInitialized()
        val ctx = requireNotNull(CallEngine.getContext()) { "CallEngine must be initialized with context" }
        return CallEngine.checkOverlayPermissionGranted(ctx)
    }
}
