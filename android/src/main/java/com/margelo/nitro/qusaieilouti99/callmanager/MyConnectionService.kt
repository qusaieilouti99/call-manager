package com.margelo.nitro.qusaieilouti99.callmanager

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telecom.CallAudioState // Explicit import
import android.util.Log // Explicit import

class MyConnectionService : ConnectionService() {

    companion object {
        const val TAG = "MyConnectionService"
        const val EXTRA_CALL_DATA = "callData"
        const val EXTRA_IS_VIDEO_CALL_BOOLEAN = "isVideoCallBoolean"
    }

    override fun onCreateIncomingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection: requestExtras=${request.extras}, accountHandle=${phoneAccountHandle}")
        val callData = request.extras?.getString(EXTRA_CALL_DATA) ?: ""
        val isVideoCallBoolean = request.extras?.getBoolean(EXTRA_IS_VIDEO_CALL_BOOLEAN, false) ?: false

        Log.d(TAG, "Incoming call data: $callData, isVideoCallBoolean (from extra): $isVideoCallBoolean")
        val connection = MyConnection(applicationContext, callData)

        val videoState = if (isVideoCallBoolean) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        connection.setVideoState(videoState)
        connection.setRinging()
        Log.d(TAG, "Created incoming connection for callId: ${connection.callId}. Status: RINGING, VideoState: $videoState") // Accesses internal callId
        return connection
    }

    override fun onCreateOutgoingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: requestExtras=${request.extras}, accountHandle=${phoneAccountHandle}")
        val callData = request.extras?.getString(EXTRA_CALL_DATA) ?: ""
        val isVideoCallBoolean = request.extras?.getBoolean(EXTRA_IS_VIDEO_CALL_BOOLEAN, false) ?: false

        Log.d(TAG, "Outgoing call data: $callData, isVideoCallBoolean (from extra): $isVideoCallBoolean")
        val connection = MyConnection(applicationContext, callData)

        val videoState = if (isVideoCallBoolean) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        connection.setVideoState(videoState)
        connection.setDialing()
        Log.d(TAG, "Created outgoing connection for callId: ${connection.callId}. Status: DIALING, VideoState: $videoState") // Accesses internal callId

        if (request.extras?.getBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false) == true) {
            Log.d(TAG, "Hinting Telecom to start outgoing call with speakerphone as per request extras.")
            connection.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }

        return connection
    }
}
