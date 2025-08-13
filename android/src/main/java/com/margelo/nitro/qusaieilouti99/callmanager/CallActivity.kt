package com.margelo.nitro.qusaieilouti99.callmanager

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class CallActivity : Activity(), CallEngine.CallEndListener {

  private enum class FinishReason {
    ANSWER, DECLINE, TIMEOUT, MANUAL_DISMISS, EXTERNAL_END
  }
  private var finishReason: FinishReason? = null
  private var callId = ""
  private var callType = "Audio"

  private val timeoutMs = 60_000L
  private val uiHandler = Handler(Looper.getMainLooper())
  private val timeoutRunnable = Runnable {
    finishReason = FinishReason.TIMEOUT
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()
    CallEngine.endCall(callId)
    finishCallActivity()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val isSamsungBypass = intent.getBooleanExtra(
      "SAMSUNG_LOCK_SCREEN_BYPASS", false
    )
    setupLockScreenBypass(isSamsungBypass)

    callId    = intent.getStringExtra("callId") ?: ""
    callType  = intent.getStringExtra("callType") ?: "Audio"
    val callerName = intent.getStringExtra("callerName") ?: "Unknown"
    val avatarUrl  = intent.getStringExtra("callerAvatar")

    CallEngine.registerCallEndListener(this)
    buildUi(callerName, avatarUrl)
    uiHandler.postDelayed(timeoutRunnable, timeoutMs)
    Log.d(TAG, "CallActivity setup complete for callId=$callId")
  }

  private fun buildUi(name: String, avatarUrl: String?) {
    val root = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }

    // 1) Full-screen background + blur
    val bg = ImageView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      scaleType = ImageView.ScaleType.CENTER_CROP

      // Apply blur effect only on API 31+ (Android S)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRenderEffect(
          android.graphics.RenderEffect.createBlurEffect(
            50f, 50f, android.graphics.Shader.TileMode.CLAMP
          )
        )
      }
      // For older versions, the blur will be handled in loadAndBlurBackground
    }
    root.addView(bg)
    loadAndBlurBackground(bg, avatarUrl)

    // 2) Dark scrim
    root.addView(View(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.parseColor("#80000000"))
    })

    // 3) Avatar + name + call type in top half
    val avatarSection = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        topMargin = dp(100)
      }
    }
    avatarSection.addView(createAvatarView(name, avatarUrl))
    avatarSection.addView(TextView(this).apply {
      text = name
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(16) }
    })
    avatarSection.addView(TextView(this).apply {
      text = if (callType.equals("video", true))
        "Incoming video call"
      else
        "Incoming audio call"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
      gravity = Gravity.CENTER
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(8) }
    })
    root.addView(avatarSection)

    // 4) Bottom buttons with extra bottom padding (64dp)
    val actions = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bottomMargin = dp(64)
      }
    }
    actions.addView(
      createCircleButton(
        android.R.drawable.ic_menu_close_clear_cancel,
        Color.parseColor("#F44336")
      ).apply { setOnClickListener { onDecline() } }
    )
    actions.addView(View(this).apply {
      layoutParams = LinearLayout.LayoutParams(dp(100), 0)
    })
    actions.addView(
      createCircleButton(
        android.R.drawable.ic_menu_call,
        Color.parseColor("#4CAF50")
      ).apply { setOnClickListener { onAnswer() } }
    )
    root.addView(actions)

    setContentView(root)
  }

  private fun createAvatarView(name: String, url: String?): FrameLayout {
    val size = dp(140)
    // Gradient purple background
    val gradientBg = GradientDrawable(
      GradientDrawable.Orientation.TL_BR,
      intArrayOf(
        Color.parseColor("#8E24AA"),
        Color.parseColor("#CE93D8")
      )
    ).apply { shape = GradientDrawable.OVAL }

    val container = FrameLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(size, size)
      background = gradientBg
      clipToOutline = true
    }

    // ImageView (only visible when URL != null)
    val iv = ImageView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      scaleType = ImageView.ScaleType.CENTER_CROP
    }
    container.addView(iv)

    // Initials TextView
    val initials = TextView(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      gravity = Gravity.CENTER
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
      typeface = Typeface.DEFAULT_BOLD
      text = getInitials(name)
    }
    container.addView(initials)

    if (url.isNullOrEmpty()) {
      iv.visibility = View.GONE
      initials.visibility = View.VISIBLE
    } else {
      iv.visibility = View.VISIBLE
      initials.visibility = View.GONE
      loadAvatar(iv, url)
    }

    return container
  }

  private fun getInitials(fullName: String): String {
    val parts = fullName.trim().split("\\s+".toRegex())
    return when (parts.size) {
      0    -> ""
      1    -> parts[0].substring(0, 1).uppercase()
      else -> (parts[0][0].toString() + parts[1][0].toString()).uppercase()
    }
  }

  private fun loadAvatar(iv: ImageView, url: String) {
    Thread {
      try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.doInput = true; conn.connect()
        val bmp = BitmapFactory.decodeStream(conn.inputStream)
        runOnUiThread { iv.setImageBitmap(bmp) }
      } catch (_: Exception) { }
    }.start()
  }

  private fun loadAndBlurBackground(iv: ImageView, url: String?) {
    Thread {
      val bmp: Bitmap? = try {
        if (!url.isNullOrEmpty()) {
          val c = URL(url).openConnection() as HttpURLConnection
          c.doInput = true; c.connect()
          BitmapFactory.decodeStream(c.inputStream)
        } else {
          BitmapFactory.decodeResource(
            resources, R.drawable.default_call_bg
          )
        }
      } catch (_: Exception) { null }
      bmp ?: return@Thread

      val finalBmp = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // For API 28-30, apply software blur since RenderEffect is not available
        val factor = 16
        val w = bmp.width / factor
        val h = bmp.height / factor
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
      } else {
        // For API 31+, RenderEffect handles blur
        bmp
      }

      runOnUiThread { iv.setImageBitmap(finalBmp) }
    }.start()
  }

  private fun createCircleButton(iconRes: Int, bgColor: Int): FrameLayout {
    val size = dp(70)
    return FrameLayout(this).apply {
      layoutParams = LinearLayout.LayoutParams(size, size)
      isClickable = true
      isFocusable = true
      foreground = makeCircleRipple()

      // circular background
      addView(View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        background = GradientDrawable().apply {
          shape = GradientDrawable.OVAL
          setColor(bgColor)
        }
      })

      // icon
      addView(ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(dp(36), dp(36))
          .apply { gravity = Gravity.CENTER }
        setImageResource(iconRes)
        setColorFilter(Color.WHITE)
      })
    }
  }

  private fun makeCircleRipple(): RippleDrawable {
    val mask = GradientDrawable().apply {
      shape = GradientDrawable.OVAL
      setColor(Color.WHITE)
    }
    val color = android.content.res.ColorStateList.valueOf(
      Color.parseColor("#33FFFFFF")
    )
    return RippleDrawable(color, null, mask)
  }

  private fun onAnswer() {
    finishReason = FinishReason.ANSWER
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()

    // Use the unified answer flow with isLocalAnswer = true
    CallEngine.answerCall(callId, isLocalAnswer = true)
    finishCallActivity()
  }

  private fun onDecline() {
    finishReason = FinishReason.DECLINE
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()
    CallEngine.endCall(callId)
    finishCallActivity()
  }

  private fun setupLockScreenBypass(isSamsung: Boolean) {
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
    if (isSamsung) {
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
          .requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
              override fun onDismissSucceeded() {
                Log.d(TAG, "Samsung keyguard dismissed")
              }
              override fun onDismissError() {
                Log.w(TAG, "Keyguard dismiss error")
              }
            })
      }
    }
  }

  override fun onBackPressed() {
    finishReason = FinishReason.MANUAL_DISMISS
    CallEngine.stopRingtone()
    CallEngine.cancelIncomingCallUI()
    CallEngine.endCall(callId)
    finishCallActivity()
  }

  override fun onCallEnded(endedCallId: String) {
    if (endedCallId == callId && !isFinishing) {
      finishReason = FinishReason.EXTERNAL_END
      runOnUiThread { finishCallActivity() }
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    CallEngine.unregisterCallEndListener(this)
    uiHandler.removeCallbacks(timeoutRunnable)
    if (finishReason != FinishReason.ANSWER) {
      CallEngine.stopRingtone()
      CallEngine.cancelIncomingCallUI()
    }
  }

  private fun finishCallActivity() {
    if (isFinishing) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      finishAndRemoveTask()
    } else {
      finish()
    }
  }

  private fun dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    v.toFloat(),
    resources.displayMetrics
  ).toInt()

  companion object {
    private const val TAG = "CallActivity"
  }
}
