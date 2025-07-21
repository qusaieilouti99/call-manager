// MyConnectionService.kt
package com.margelo.nitro.qusaieilouti99.callmanager

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

class MyConnectionService : ConnectionService() {

    companion object {
        const val TAG = "MyConnectionService"
        const val EXTRA_CALL_DATA = "callData"
    }

    override fun onCreateIncomingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection: ${request.extras}")
        val callData = request.extras?.getString(EXTRA_CALL_DATA) ?: ""
        val connection = MyConnection(applicationContext, callData)
        connection.setRinging()
        return connection
    }

    override fun onCreateOutgoingConnection(
        phoneAccountHandle: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: ${request.extras}")
        val callData = request.extras?.getString(EXTRA_CALL_DATA) ?: ""
        val connection = MyConnection(applicationContext, callData)
        connection.setDialing()
        return connection
    }
}
