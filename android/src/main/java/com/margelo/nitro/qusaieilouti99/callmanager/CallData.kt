// CallInfo.kt
package com.margelo.nitro.qusaieilouti99.callmanager

import org.json.JSONObject

data class CallInfo(
    val callId: String,
    val callType: String, // "Audio" or "Video"
    val displayName: String, // For UI display
    val pictureUrl: String? = null, // For UI display
    var state: CallState,
    val timestamp: Long = System.currentTimeMillis(),
    var wasHeldBySystem: Boolean = false,
    var isManuallySilenced: Boolean = false
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("callId", callId)
            put("callType", callType)
            put("displayName", displayName)
            pictureUrl?.let { put("pictureUrl", it) }
            put("state", state.name)
            put("timestamp", timestamp)
            put("wasHeldBySystem", wasHeldBySystem)
        }
    }
}

enum class CallState {
    INCOMING, DIALING, ACTIVE, HELD, ENDED
}
