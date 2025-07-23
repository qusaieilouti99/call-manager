package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class CallActivity : Activity() {

    private enum class FinishReason { ANSWER, DECLINE, TIMEOUT, MANUAL_DISMISS }
    private var finishReason: FinishReason? = null
    private var callId: String = ""
    private var callType: String = "Audio"

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "CallActivity timeout triggered for callId: $callId")
        finishReason = FinishReason.TIMEOUT
        CallEngine.endCall(this, callId)
        finishCallActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "CallActivity onCreate")

        // Modern way to handle lock screen bypass (API 27+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // Legacy approach for older versions
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_call)

        callId = intent.getStringExtra("callId") ?: ""
        callType = intent.getStringExtra("callType") ?: "Audio"
        Log.d(TAG, "CallActivity received callId: $callId, callType: $callType")

        // FIXED: Immediate cleanup of notifications when CallActivity is shown
        CallEngine.cancelIncomingCallUI(this)

        val callerName = intent.getStringExtra("callerName") ?: "Unknown"
        val nameView = findViewById<TextView>(R.id.caller_name)
        val answerBtn = findViewById<Button>(R.id.answer_btn)
        val declineBtn = findViewById<Button>(R.id.decline_btn)

        nameView.text = callerName

        answerBtn.setOnClickListener {
            Log.d(TAG, "CallActivity: Answer button clicked for callId: $callId")
            finishReason = FinishReason.ANSWER

            // FIXED: Use single source of truth - this will handle all cleanup
            CallEngine.answerCall(this, callId)
            finishCallActivity()
        }

        declineBtn.setOnClickListener {
            Log.d(TAG, "CallActivity: Decline button clicked for callId: $callId")
            finishReason = FinishReason.DECLINE
            CallEngine.endCall(this, callId)
            finishCallActivity()
        }

        timeoutHandler.postDelayed(timeoutRunnable, 60_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallActivity onDestroy for callId: $callId. Reason: $finishReason")
        timeoutHandler.removeCallbacks(timeoutRunnable)

        // FIXED: Ensure cleanup happens regardless of how activity ends
        CallEngine.stopRingtone()
        CallEngine.cancelIncomingCallUI(this)
    }

    override fun onBackPressed() {
        Log.d(TAG, "CallActivity onBackPressed for callId: $callId. Treating as decline/dismiss.")
        finishReason = FinishReason.MANUAL_DISMISS
        CallEngine.endCall(this, callId)
        finishCallActivity()
    }

    private fun finishCallActivity() {
        Log.d(TAG, "Finishing CallActivity.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    companion object {
        private const val TAG = "CallActivity"
    }
}
