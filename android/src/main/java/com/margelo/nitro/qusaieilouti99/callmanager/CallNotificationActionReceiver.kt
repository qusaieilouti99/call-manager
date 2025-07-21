// CallNotificationActionReceiver.kt
package com.margelo.nitro.qusaieilouti99.callmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        Log.d("CallNotificationActionReceiver", "onReceive called with action=${intent.action}, callId=$callId")
        when (intent.action) {
            "com.qusaieilouti99.callmanager.ANSWER_CALL" -> {
                Log.d("CallNotificationActionReceiver", "Answer action received")
                CallEngine.bringAppToForeground(context)
            }
            "com.qusaieilouti99.callmanager.DECLINE_CALL" -> {
                Log.d("CallNotificationActionReceiver", "Decline action received")
                CallEngine.cancelIncomingCallUI(context)
                CallEngine.stopForegroundService(context)
                CallEngine.disconnectTelecomCall(context, callId)
            }
        }
        CallEngine.cancelIncomingCallUI(context)
    }
}
