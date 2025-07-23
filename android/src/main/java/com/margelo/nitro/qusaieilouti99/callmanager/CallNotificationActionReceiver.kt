package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallNotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "CallNotifActionReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        Log.d(TAG, "onReceive called with action=${intent.action}, callId=$callId")

        when (intent.action) {
            "com.qusaieilouti99.callmanager.ANSWER_CALL" -> {
                Log.d(TAG, "Answer action received for callId: $callId")
                val connection = CallEngine.getTelecomConnection(callId)
                if (connection != null) {
                    connection.onAnswer()
                    Log.d(TAG, "Call answered via Telecom connection for callId: $callId")
                } else {
                    Log.e(TAG, "No Telecom connection found for callId: $callId. Using direct answer.")
                    CallEngine.answerCall(context, callId)
                }
            }
            "com.qusaieilouti99.callmanager.DECLINE_CALL" -> {
                Log.d(TAG, "Decline action received for callId: $callId")
                CallEngine.endCall(context, callId)
            }
            "com.qusaieilouti99.callmanager.END_CALL" -> {
                Log.d(TAG, "End call action received for callId: $callId")
                CallEngine.endCall(context, callId)
            }
            "com.qusaieilouti99.callmanager.HOLD_CALL" -> {
                Log.d(TAG, "Hold call action received for callId: $callId")
                CallEngine.holdCall(context, callId)
            }
            "com.qusaieilouti99.callmanager.UNHOLD_CALL" -> {
                Log.d(TAG, "Unhold call action received for callId: $callId")
                CallEngine.unholdCall(context, callId)
            }
            else -> {
                Log.w(TAG, "Unknown action received: ${intent.action}")
            }
        }
    }
}
