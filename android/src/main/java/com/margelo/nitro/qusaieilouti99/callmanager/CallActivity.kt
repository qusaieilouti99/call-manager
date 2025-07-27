// File: CallActivity.kt
package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Full‐screen incoming‐call UI.  Implements CallEndListener
 * so it always auto‐finishes when the engine ends the call.
 */
class CallActivity : Activity(), CallEngine.CallEndListener {

  private enum class FinishReason {
    ANSWER, DECLINE, TIMEOUT, MANUAL_DISMISS, EXTERNAL_END
  }

  private var finishReason: FinishReason? = null
  private var callId: String = ""
  private var callType: String = "Audio"

  private val timeoutHandler = Handler(Looper.getMainLooper())
  private val timeoutRunnable = Runnable {
    Log.d(TAG, "CallActivity timeout triggered for callId: $callId")
    finishReason = FinishReason.TIMEOUT
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()
    CallEngine.endCall(callId)
    finishCallActivity()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(TAG, "CallActivity onCreate")

    // Lock‐screen bypass
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
      )
    }

    setContentView(R.layout.activity_call)

    // Read incoming‐call params
    callId = intent.getStringExtra("callId") ?: ""
    callType = intent.getStringExtra("callType") ?: "Audio"
    Log.d(TAG, "CallActivity received callId: $callId, callType: $callType")

    // Register for call‐end callbacks BEFORE timeout or user can dismiss
    CallEngine.registerCallEndListener(this)

    // Bind UI
    val callerName = intent.getStringExtra("callerName") ?: "Unknown"
    findViewById<TextView>(R.id.caller_name).text = callerName

    findViewById<Button>(R.id.answer_btn).setOnClickListener {
      Log.d(TAG, "Answer clicked for callId: $callId")
      finishReason = FinishReason.ANSWER
      CallEngine.stopRingtone()
      CallEngine.cancelIncomingCallUI()
      CallEngine.answerCall(callId)
      finishCallActivity()
    }

    findViewById<Button>(R.id.decline_btn).setOnClickListener {
      Log.d(TAG, "Decline clicked for callId: $callId")
      finishReason = FinishReason.DECLINE
      CallEngine.stopRingtone()
      CallEngine.cancelIncomingCallUI()
      CallEngine.endCall(callId)
      finishCallActivity()
    }

    // Start auto‐timeout
    timeoutHandler.postDelayed(timeoutRunnable, 60_000)
    Log.d(TAG, "CallActivity setup complete")
  }

  override fun onDestroy() {
    super.onDestroy()
    Log.d(TAG, "CallActivity onDestroy for callId: $callId. Reason: $finishReason")

    // Unregister listener
    CallEngine.unregisterCallEndListener(this)

    // Cancel timeout
    timeoutHandler.removeCallbacks(timeoutRunnable)

    // If user never answered, clean up ringtone/UI
    if (finishReason != FinishReason.ANSWER) {
      CallEngine.stopRingtone()
      CallEngine.cancelIncomingCallUI()
    }
  }

  override fun onBackPressed() {
    Log.d(TAG, "onBackPressed for callId: $callId → treat as decline")
    finishReason = FinishReason.MANUAL_DISMISS
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()
    CallEngine.endCall(callId)
    finishCallActivity()
  }

  /**
   * Called by CallEngine whenever ANY call ends.
   * We only care about our own callId.
   */
  override fun onCallEnded(endedCallId: String) {
    if (endedCallId == callId && !isFinishing) {
      Log.d(TAG, "CallActivity onCallEnded callback for callId: $callId")
      finishReason = FinishReason.EXTERNAL_END
      runOnUiThread { finishCallActivity() }
    }
  }

  private fun finishCallActivity() {
    if (isFinishing) {
      Log.d(TAG, "Already finishing, skip.")
      return
    }
    Log.d(TAG, "Finishing CallActivity for callId: $callId")
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
