package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.Context
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONObject
import java.util.UUID

class MyConnection(
    private val context: Context,
    callDataJson: String
) : Connection() {

    companion object {
        const val TAG = "MyConnection"
    }

    internal val callId: String = try {
        JSONObject(callDataJson).optString("callId", UUID.randomUUID().toString())
    } catch (e: Exception) {
        UUID.randomUUID().toString()
    }

    private val currentCallType: String
    private var lastAudioState: CallAudioState? = null

    init {
        currentCallType = try {
            JSONObject(callDataJson).optString("callType", "Audio")
        } catch (e: Exception) { "Audio" }

        connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or
                                Connection.CAPABILITY_MUTE or
                                Connection.CAPABILITY_HOLD

        if (currentCallType == "Video") {
            Log.d(TAG, "MyConnection for callId $callId initialized as VIDEO call.")
            setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
        } else {
            Log.d(TAG, "MyConnection for callId $callId initialized as AUDIO call.")
            setVideoState(VideoProfile.STATE_AUDIO_ONLY)
        }

        CallEngine.addTelecomConnection(callId, this)
        Log.d(TAG, "MyConnection for callId $callId created and added to CallEngine. Type: $currentCallType")
    }

    override fun onAnswer() {
        Log.d(TAG, "Call answered via Telecom for callId: $callId")
        setActive()
        CallEngine.answerCall(context, callId)
    }

    override fun onReject() {
        Log.d(TAG, "Call rejected via Telecom for callId: $callId")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallEngine.endCall(context, callId)
    }

    override fun onDisconnect() {
        Log.d(TAG, "Call disconnected via Telecom for callId: $callId")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallEngine.endCall(context, callId)
    }

    override fun onHold() {
        super.onHold()
        Log.d(TAG, "Call held via Telecom for callId: $callId")

        // This is called by the system when it wants to hold our call
        // Usually happens when a phone call comes in
        CallEngine.holdCall(context, callId)
    }

    override fun onUnhold() {
        super.onUnhold()
        Log.d(TAG, "Call unheld via Telecom for callId: $callId")

        // This is called by the system when it's safe to resume our call
        // Usually happens when a phone call ends
        CallEngine.unholdCall(context, callId)
    }

    override fun onCallAudioStateChanged(state: CallAudioState) {
        super.onCallAudioStateChanged(state)
        Log.d(TAG, "Audio state changed for callId: $callId. muted=${state.isMuted}, route=${state.route}")

        // Only process mute changes if they actually changed
        if (lastAudioState == null || lastAudioState!!.isMuted != state.isMuted) {
            if (state.isMuted) {
                CallEngine.muteCall(context, callId)
            } else {
                CallEngine.unmuteCall(context, callId)
            }
        }

        // Only process route changes if they actually changed
        if (lastAudioState == null || lastAudioState!!.route != state.route) {
            val routeName = when (state.route) {
                CallAudioState.ROUTE_SPEAKER -> "Speaker"
                CallAudioState.ROUTE_EARPIECE -> "Earpiece"
                CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
                CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
                else -> "Unknown"
            }

            CallEngine.emitEvent(
                CallEventType.AUDIO_ROUTE_CHANGED,
                JSONObject().put("callId", callId).put("route", routeName)
            )
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
        // Don't bring app to foreground for incoming calls automatically
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
