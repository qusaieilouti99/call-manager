package com.margelo.nitro.qusaieilouti99.callmanager

import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
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
        val requestedVideoState = request.extras?.getInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY) ?: VideoProfile.STATE_AUDIO_ONLY

        Log.d(TAG, "Creating incoming connection: callId=$callId, type=$callType, name=$displayName, requestedVideoState=$requestedVideoState")

        val connection = MyConnection(applicationContext, callId, callType, displayName, pictureUrl)

        // Set the video state based on what was requested in addNewIncomingCall.
        connection.setVideoState(requestedVideoState)
        connection.setRinging()

        Log.d(TAG, "Created incoming connection for callId: $callId. Status: RINGING, VideoState: ${requestedVideoState}")
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
        val requestedVideoState = request.extras?.getInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_AUDIO_ONLY) ?: VideoProfile.STATE_AUDIO_ONLY

        Log.d(TAG, "Creating outgoing connection: callId=$callId, type=$callType, name=$displayName, requestedVideoState=$requestedVideoState")

        val connection = MyConnection(applicationContext, callId, callType, displayName, pictureUrl)

        // Set the video state based on what was requested in placeCall.
        connection.setVideoState(requestedVideoState)
        connection.setDialing()

        Log.d(TAG, "Created outgoing connection for callId: $callId. Status: DIALING, VideoState: ${requestedVideoState}")

        // TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE is a hint to Telecom.
        // The actual audio route will be set by CallEngine.setInitialCallAudioRoute when the call
        // becomes active (or immediately for startCall scenario).
        // No direct `connection.setAudioRoute()` here as it's deprecated and handled by CallEndpoint flow.

        return connection
    }
}
