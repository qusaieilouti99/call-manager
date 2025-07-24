package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.Context
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONObject

class MyConnection(
    private val context: Context,
    val callId: String,
    val callType: String,
    val displayName: String,
    val pictureUrl: String?
) : Connection() {

    companion object {
        const val TAG = "MyConnection"
    }

    private var lastAudioState: CallAudioState? = null

    init {
        connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_MUTE or
            Connection.CAPABILITY_HOLD

        if (callType == "Video") {
            Log.d(TAG, "MyConnection for callId $callId initialized as VIDEO call.")
            setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
        } else {
            Log.d(TAG, "MyConnection for callId $callId initialized as AUDIO call.")
            setVideoState(VideoProfile.STATE_AUDIO_ONLY)
        }

        CallEngine.addTelecomConnection(callId, this)
        Log.d(TAG, "MyConnection for callId $callId created and added to CallEngine. Type: $callType")
    }

    override fun onAnswer() {
        Log.d(TAG, "Call answered via Telecom for callId: $callId")
        setActive()
        CallEngine.answerCall(callId)
    }

    override fun onReject() {
        Log.d(TAG, "Call rejected via Telecom for callId: $callId")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallEngine.endCall(callId)
    }

    override fun onDisconnect() {
        Log.d(TAG, "Call disconnected via Telecom for callId: $callId")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallEngine.endCall(callId)
    }

    override fun onHold() {
        super.onHold()
        Log.d(TAG, "Call held via Telecom for callId: $callId")
        CallEngine.holdCall(callId)
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "Call unheld via Telecom for callId: $callId")
        CallEngine.unholdCall(callId)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        Log.d(TAG, "Audio state changed for callId: $callId. muted=${state.isMuted}, route=${state.route}")

        if (lastAudioState == null || lastAudioState!!.isMuted != state.isMuted) {
            if (state.isMuted) {
                CallEngine.muteCall(callId)
            } else {
                CallEngine.unmuteCall(callId)
            }
        }

        // Only react to route change if it's different.
        // DO NOT emit AUDIO_ROUTE_CHANGED from here, let CallEngine's
        // AudioDeviceCallback handle it for consistency and to avoid duplication.
        if (lastAudioState == null || lastAudioState!!.route != state.route) {
            Log.d(TAG, "System audio route changed for callId: $callId. Telecom route: ${state.route}")
        }

        lastAudioState = state
    }

    override fun onPlayDtmfTone(digit: Char) {
        super.onPlayDtmfTone(digit)
        Log.d(TAG, "Playing DTMF tone: $digit for callId: $callId")
        CallEngine.emitEvent(
            CallEventType.DTMF_TONE,
            JSONObject().put("callId", callId).put("digit", digit.toString())
        )
    }

    override fun onStopDtmfTone() {
        super.onStopDtmfTone()
        Log.d(TAG, "Stopping DTMF tone for callId: $callId")
    }

    override fun onShowIncomingCallUi() {
        super.onShowIncomingCallUi()
        Log.d(TAG, "onShowIncomingCallUi for callId: $callId")
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        Log.d(TAG, "Connection state changed for callId: $callId. New state: $state")

        when (state) {
            STATE_HOLDING -> {
                Log.d(TAG, "Connection is now holding for callId: $callId")
            }
            STATE_ACTIVE -> {
                Log.d(TAG, "Connection is now active for callId: $callId")
            }
            STATE_DISCONNECTED -> {
                Log.d(TAG, "Connection is now disconnected for callId: $callId")
                CallEngine.removeTelecomConnection(callId)
            }
        }
    }
}
