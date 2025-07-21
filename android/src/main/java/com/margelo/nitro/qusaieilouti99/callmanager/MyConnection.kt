package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.Context
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.CallAudioState
import android.util.Log

class MyConnection(
    private val context: Context,
    private val callData: String
) : Connection() {

    companion object {
        const val TAG = "MyConnection"
    }

    override fun onAnswer() {
        Log.d(TAG, "Call answered")
        setActive()
        CallEngine.answerCall(context, callData)
        CallEngine.bringAppToForeground(context)
    }

    override fun onReject() {
        Log.d(TAG, "Call rejected")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallEngine.endCall(context, callData)
    }

    override fun onDisconnect() {
        Log.d(TAG, "Call disconnected")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallEngine.endCall(context, callData)
    }

    override fun onHold() {
        super.onHold()
        Log.d(TAG, "Call held")
        CallEngine.holdCall(context, callData)
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "Call unheld")
        CallEngine.unholdCall(context, callData)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        Log.d(TAG, "Audio state changed: muted=${state.isMuted}, route=${state.route}")
        if (state.isMuted) {
            CallEngine.muteCall(context, callData)
        } else {
            CallEngine.unmuteCall(context, callData)
        }
        // Only emit route change, not device list
        CallEngine.emitEvent(
            CallEventType.AUDIO_ROUTE_CHANGED,
            org.json.JSONObject().put("route", state.route)
        )
    }
}
