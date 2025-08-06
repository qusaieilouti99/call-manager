package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.Context
import android.os.ParcelUuid
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executor
import android.os.OutcomeReceiver
import android.telecom.CallEndpointException
import java.util.UUID

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

    init {
        connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_MUTE or
            Connection.CAPABILITY_HOLD

        setVideoState(if (callType == "Video") VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
        Log.d(TAG, "MyConnection for callId $callId created. Type: $callType, VideoState: ${getVideoState()}")

        CallEngine.addTelecomConnection(callId, this)
        Log.d(TAG, "MyConnection for callId $callId created and added to CallEngine.")
    }

    override fun onSilence() {
        super.onSilence()
        Log.d(TAG, "onSilence called by system for callId: $callId. Silencing ringtone.")
        CallEngine.silenceIncomingCall()
    }

    override fun onAnswer(videoState: Int) {
        super.onAnswer(videoState)
        Log.d(TAG, "Call answered via Telecom for callId: $callId. VideoState: $videoState")
        setActive()
        CallEngine.answerCall(callId)
    }

    override fun onReject() {
        super.onReject()
        Log.d(TAG, "Call rejected via Telecom for callId: $callId")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallEngine.endCall(callId)
    }

    override fun onDisconnect() {
        super.onDisconnect()
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

    override fun onMuteStateChanged(isMuted: Boolean) {
        super.onMuteStateChanged(isMuted)
        Log.d(TAG, "Mute state changed for callId: $callId. isMuted=$isMuted")
        // Inform CallEngine, which will then update AudioManager and emit the event.
        CallEngine.setMuted(callId, isMuted)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        Log.d(TAG, "Telecom reported active CallEndpoint for callId: $callId: ${callEndpoint.endpointName} (type: ${callEndpoint.endpointType})")
        CallEngine.onTelecomAudioRouteChanged(callId, callEndpoint)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        Log.d(TAG, "Telecom reported available CallEndpoints for callId: $callId: ${availableEndpoints.map { it.endpointName }}")
        CallEngine.onTelecomAvailableEndpointsChanged(availableEndpoints)
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
        // This is primarily for Telecom to tell us it expects *us* to show the UI.
        // Our existing `reportIncomingCall` already calls `showIncomingCallUI`.
        // This might be redundant or a signal for edge cases.
    }

    override fun onStateChanged(state: Int) {
        super.onStateChanged(state)
        Log.d(TAG, "Connection state changed for callId: $callId. New state: ${Connection.stateToString(state)}")

        when (state) {
            STATE_RINGING -> {
                Log.d(TAG, "Connection is now ringing for callId: $callId")
            }
            STATE_HOLDING -> {
                Log.d(TAG, "Connection is now holding for callId: $callId")
            }
            STATE_ACTIVE -> {
                Log.d(TAG, "Connection is now active for callId: $callId")
                // When the call becomes active, request the initial audio route via CallEngine.
                // CallEngine will determine the best route and then request it back to us.
                CallEngine.setInitialCallAudioRoute(callId, callType)
            }
            STATE_DISCONNECTED -> {
                Log.d(TAG, "Connection is now disconnected for callId: $callId")
                CallEngine.removeTelecomConnection(callId)
            }
        }
    }

    // Method to set audio route through telecom using CallEndpoint API
    fun setTelecomAudioRoute(endpoint: CallEndpoint) {
        Log.d(TAG, "Requesting telecom audio route change to: ${endpoint.endpointName} (type: ${endpoint.endpointType}) for callId: $callId")
        try {
            // Use Context.getMainExecutor() for callback execution on the main thread
            requestCallEndpointChange(endpoint, context.mainExecutor, object : OutcomeReceiver<Void?, CallEndpointException> {
                override fun onResult(result: Void?) {
                    Log.d(TAG, "CallEndpoint change request successful for ${endpoint.endpointName} for callId: $callId")
                    // Telecom will eventually call onCallEndpointChanged with the new active endpoint
                }

                override fun onError(error: CallEndpointException) {
                    Log.e(TAG, "CallEndpoint change request failed for ${endpoint.endpointName} for callId: $callId: ${error.message}", error)
                    // You might want to revert UI here or notify the user of failure
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error calling requestCallEndpointChange: ${e.message}", e)
        }
    }
}
