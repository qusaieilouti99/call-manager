package com.margelo.nitro.qusaieilouti99.callmanager

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telecom.CallAudioState
import android.util.Log
import java.util.UUID

class MyConnectionService : ConnectionService() {

    companion object {
        const val TAG = "MyConnectionService"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_CALL_TYPE = "callType"
        const val EXTRA_DISPLAY_NAME = "displayName"
        const val EXTRA_PICTURE_URL = "pictureUrl"
        const val EXTRA_IS_VIDEO_CALL_BOOLEAN = "isVideoCallBoolean"
    }

    override fun onCreateIncomingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection: requestExtras=${request.extras}")

        val callId = request.extras?.getString(EXTRA_CALL_ID) ?: UUID.randomUUID().toString()
        val callType = request.extras?.getString(EXTRA_CALL_TYPE) ?: "Audio"
        val displayName = request.extras?.getString(EXTRA_DISPLAY_NAME) ?: "Unknown"
        val pictureUrl = request.extras?.getString(EXTRA_PICTURE_URL)
        val isVideoCallBoolean = request.extras?.getBoolean(EXTRA_IS_VIDEO_CALL_BOOLEAN, false) ?: false

        Log.d(TAG, "Creating incoming connection: callId=$callId, type=$callType, name=$displayName")

        val connection = MyConnection(applicationContext, callId, callType, displayName, pictureUrl)

        val videoState = if (isVideoCallBoolean) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        connection.setVideoState(videoState)
        connection.setRinging()

        Log.d(TAG, "Created incoming connection for callId: $callId. Status: RINGING, VideoState: $videoState")
        return connection
    }

    override fun onCreateOutgoingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: requestExtras=${request.extras}")

        val callId = request.extras?.getString(EXTRA_CALL_ID) ?: UUID.randomUUID().toString()
        val callType = request.extras?.getString(EXTRA_CALL_TYPE) ?: "Audio"
        val displayName = request.extras?.getString(EXTRA_DISPLAY_NAME) ?: "Unknown"
        val pictureUrl = request.extras?.getString(EXTRA_PICTURE_URL)
        val isVideoCallBoolean = request.extras?.getBoolean(EXTRA_IS_VIDEO_CALL_BOOLEAN, false) ?: false

        Log.d(TAG, "Creating outgoing connection: callId=$callId, type=$callType, name=$displayName")

        val connection = MyConnection(applicationContext, callId, callType, displayName, pictureUrl)

        val videoState = if (isVideoCallBoolean) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        connection.setVideoState(videoState)
        connection.setDialing()

        Log.d(TAG, "Created outgoing connection for callId: $callId. Status: DIALING, VideoState: $videoState")

        if (request.extras?.getBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false) == true) {
            Log.d(TAG, "Hinting Telecom to start outgoing call with speakerphone as per request extras.")
            connection.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        }

        return connection
    }
}
