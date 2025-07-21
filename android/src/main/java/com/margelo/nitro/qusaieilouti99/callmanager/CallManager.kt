package com.margelo.nitro.qusaieilouti99.callmanager

import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class CallManager : HybridCallManagerSpec() {

    // Store the current listener so we can remove it
    private var currentListener: ((CallEventType, String) -> Unit)? = null

    override fun endCall(callId: String) {
        CallEngine.endCall(CallEngine.getAppContext() ?: return, callId)
    }

    override fun silenceRingtone() {
        CallEngine.cancelIncomingCallUI(CallEngine.getAppContext() ?: return)
    }

    override fun getAudioDevices(): Array<String> {
        val context = CallEngine.getAppContext() ?: throw IllegalStateException("App context not set")
        return CallEngine.getAudioDevices(context).toTypedArray()
    }

    override fun setAudioRoute(route: String) {
        val context = CallEngine.getAppContext() ?: throw IllegalStateException("App context not set")
        CallEngine.setAudioRoute(context, route)
    }

    override fun keepScreenAwake(keepAwake: Boolean) {
        val context = CallEngine.getAppContext() ?: throw IllegalStateException("App context not set")
        CallEngine.keepScreenAwake(context, keepAwake)
    }

    override fun addListener(
        listener: (event: CallEventType, payload: String) -> Unit
    ): () -> Unit {
        // Register the single listener for all events
        currentListener = listener
        CallEngine.setEventHandler(listener)
        // Return a remove function
        return {
            if (currentListener === listener) {
                CallEngine.setEventHandler(null)
                currentListener = null
            }
        }
    }
}
