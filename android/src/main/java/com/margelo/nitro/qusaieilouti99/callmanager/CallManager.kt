package com.margelo.nitro.qusaieilouti99.callmanager

import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class CallManager : HybridCallManagerSpec() {

    private val TAG = "CallManager"

    // Simplified approach - rely on proper Application.onCreate() initialization
    // Remove all fallback context access attempts that don't work with Nitro modules
    private fun ensureInitialized() {
        if (!CallEngine.isInitialized()) {
            Log.e(TAG, "CallEngine not initialized! This should not happen if Application.onCreate() was called properly.")
            throw IllegalStateException(
                "CallEngine must be initialized in Application.onCreate(). " +
                "Make sure MainApplication.onCreate() calls CallEngine.initialize(this) before any native calls."
            )
        }
    }

    // --- All methods must call ensureInitialized() first ---

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
        CallEngine.keepScreenAwake(keepAwake)
    }

    override fun addListener(listener: (event: CallEventType, payload: String) -> Unit): () -> Unit {
        Log.d(TAG, "addListener called")
        ensureInitialized()
        CallEngine.setEventHandler(listener)
        return {
            CallEngine.setEventHandler(null)
            Log.d(TAG, "Listener removed.")
        }
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
        CallEngine.callAnsweredFromJS(callId)
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
        // do nothing for now
    }

    override fun registerVoIPTokenListener(listener: (payload: String) -> Unit): () -> Unit
        Log.d(TAG, "registerVoIPTokenListener called")
        return {
            Log.d(TAG, "registerVoIPTokenListener removed.")
        }
    }
}
