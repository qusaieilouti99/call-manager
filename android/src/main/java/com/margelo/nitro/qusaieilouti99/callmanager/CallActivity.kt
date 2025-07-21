// CallActivity.kt
package com.margelo.nitro.qusaieilouti99.callmanager
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class CallActivity : Activity() {

    private enum class FinishReason { ANSWER, DECLINE, TIMEOUT, MANUAL }
    private var finishReason: FinishReason? = null

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        finishReason = FinishReason.TIMEOUT
        finishCallActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        setContentView(R.layout.activity_call)

        CallEngine.cancelIncomingCallUI(this)
        CallEngine.playRingtone(this)

        val callerName = intent.getStringExtra("callerName") ?: "Unknown"
        val nameView = findViewById<TextView>(R.id.caller_name)
        val answerBtn = findViewById<Button>(R.id.answer_btn)
        val declineBtn = findViewById<Button>(R.id.decline_btn)

        nameView.text = callerName

        answerBtn.setOnClickListener {
            CallEngine.bringAppToForeground(this)
            finishReason = FinishReason.ANSWER
            finishCallActivity()
        }

        declineBtn.setOnClickListener {
            CallEngine.cancelIncomingCallUI(this)
            finishReason = FinishReason.DECLINE
            finishCallActivity()
        }

        timeoutHandler.postDelayed(timeoutRunnable, 60_000)
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler.removeCallbacks(timeoutRunnable)
        CallEngine.stopRingtone()
        CallEngine.cancelIncomingCallUI(this)
        // Only clean up call if not answered
        if (finishReason == FinishReason.DECLINE || finishReason == FinishReason.TIMEOUT || finishReason == FinishReason.MANUAL) {
            CallEngine.stopForegroundService(this)
            CallEngine.disconnectTelecomCall(this, intent.getStringExtra("callId") ?: "")
        }
    }

    override fun onBackPressed() {
        finishReason = FinishReason.MANUAL
        finishCallActivity()
    }

    private fun finishCallActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}
