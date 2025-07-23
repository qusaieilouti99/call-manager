package com.margelo.nitro.qusaieilouti99.callmanager

import com.facebook.proguard.annotations.DoNotStrip
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

@DoNotStrip
class CallManager : HybridCallManagerSpec() {

    private val TAG = "CallManager"

    private var currentListener: Func_void_CallEventType_std__string? = null

    override fun endCall(callId: String) {
        Log.d(TAG, "endCall requested for callId: $callId")
        CallEngine.getAppContext()?.let {
            CallEngine.endCall(it, callId)
        } ?: Log.e(TAG, "App context not set for endCall.")
    }

    override fun silenceRingtone() {
        Log.d(TAG, "silenceRingtone requested.")
        CallEngine.getAppContext()?.let {
            CallEngine.stopRingtone()
        } ?: Log.e(TAG, "App context not set for silenceRingtone.")
    }

    override fun getAudioDevices(): AudioRoutesInfo {
        Log.d(TAG, "getAudioDevices requested.")
        return CallEngine.getAppContext()?.let {
            CallEngine.getAudioDevices()
        } ?: run {
            Log.e(TAG, "App context not set for getAudioDevices. Returning empty AudioRoutesInfo.")
            AudioRoutesInfo(emptyArray(), "Unknown")
        }
    }

    override fun setAudioRoute(route: String) {
        Log.d(TAG, "setAudioRoute requested for route: $route")
        CallEngine.getAppContext()?.let {
            CallEngine.setAudioRoute(it, route)
        } ?: Log.e(TAG, "App context not set for setAudioRoute.")
    }

    override fun keepScreenAwake(keepAwake: Boolean) {
        Log.d(TAG, "keepScreenAwake requested: $keepAwake")
        CallEngine.getAppContext()?.let {
            CallEngine.keepScreenAwake(it, keepAwake)
        } ?: Log.e(TAG, "App context not set for keepAwake.")
    }

    override fun addListener(
        listener: (event: CallEventType, payload: String) -> Unit
    ): () -> Unit {
        Log.d(TAG, "addListener called with listener: $listener")
        // Wrap the listener in your event system
        CallEngine.setEventHandler(listener)
        return {
            CallEngine.setEventHandler(null)
            Log.d(TAG, "Listener removed.")
        }
    }

    override fun startOutgoingCall(callId: String, callData: String) {
        Log.d(TAG, "startOutgoingCall requested: callId=$callId, callData=$callData")
        CallEngine.getAppContext()?.let {
            CallEngine.startOutgoingCall(it, callId, callData)
        } ?: Log.e(TAG, "App context not set for startOutgoingCall.")
    }

    override fun callAnswered(callId: String) {
        Log.d(TAG, "callAnswered (from JS) requested for callId: $callId")
        CallEngine.getAppContext()?.let {
            CallEngine.callAnsweredFromJS(it, callId)
        } ?: Log.e(TAG, "App context not set for callAnswered.")
    }
}
