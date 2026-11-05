package com.example.peepguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class FaceWatchService : LifecycleService() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var lastRun = 0L
    private var multiFaceFrames = 0

    // Snooze / Paused states
    private var pausedUntil = 0L
    private var allowSecondFaceUntil = 0L
    private var pauseTimer: CountDownTimer? = null

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val channelId = "peepguard"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "PeepGuard", NotificationManager.IMPORTANCE_LOW)
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PeepGuard Active")
            .setContentText("Protecting your screen against onlookers")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )

        Guard.onActiveChanged = { active ->
            if (active) startCamera() else stopCamera()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        multiFaceFrames = 0
        hideOverlay()
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image

        // Ignore if entirely paused or checking too rapidly
        if (now < pausedUntil || now - lastRun < 400 || media == null) {
            proxy.close()
            return
        }
        lastRun = now

        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val faceCount = faces.size
                val allowSecond = (now < allowSecondFaceUntil)

                val isThreat = if (allowSecond) {
                    faceCount > 2 // Allow up to 2 faces (you + friend)
                } else {
                    faceCount >= 2 // Standard: alert on 2+ faces
                }

                if (isThreat) {
                    multiFaceFrames++
                } else {
                    multiFaceFrames = 0
                }

                if (multiFaceFrames >= 2) {
                    showBlurredOverlay()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun showBlurredOverlay() {
        ContextCompat.getMainExecutor(this).execute {
            if (overlayView != null) return@execute

            val dp = resources.displayMetrics.density
            fun toPx(v: Int) = (v * dp).toInt()

            // Root Container with Dim / Translucent Dark Blurred-glass Style
            val rootLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#E60A0A0E")) // High opacity dark scrim to obscure content
                setPadding(toPx(24), toPx(32), toPx(24), toPx(32))
            }

            // Card Panel
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(toPx(24), toPx(28), toPx(24), toPx(28))
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#22232E"))
                    cornerRadius = dp * 18f
                    setStroke(toPx(1), Color.parseColor("#444654"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Warning Header Icon & Text
            val title = TextView(this).apply {
                text = "⚠️ Peeping Detected!"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#FF5252"))
                gravity = Gravity.CENTER
            }

            val subtitle = TextView(this).apply {
                text = "Another face detected behind you.\nChoose an action below to continue:"
                textSize = 14f
                setTextColor(Color.parseColor("#D0D0D5"))
                gravity = Gravity.CENTER
                setPadding(0, toPx(8), 0, toPx(20))
            }

            card.addView(title)
            card.addView(subtitle)

            // Timer Options Setup (30s, 1m, 3m, 5m, 10m, 15m, 30m)
            val timerOptions = listOf(
                "30 Seconds" to 30 * 1000L,
                "1 Minute" to 60 * 1000L,
                "3 Minutes" to 3 * 60 * 1000L,
                "5 Minutes" to 5 * 60 * 1000L,
                "10 Minutes" to 10 * 60 * 1000L,
                "15 Minutes" to 15 * 60 * 1000L,
                "30 Minutes" to 30 * 60 * 1000L
            )

            val spinnerLabel = TextView(this).apply {
                text = "Select Duration:"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#9E9EAF"))
                setPadding(0, 0, 0, toPx(6))
            }
            card.addView(spinnerLabel)

            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@FaceWatchService,
                    android.R.layout.simple_spinner_dropdown_item,
                    timerOptions.map { it.first }
                )
                val spinBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#333544"))
                    cornerRadius = dp * 8f
                }
                background = spinBg
                setPadding(toPx(12), toPx(10), toPx(12), toPx(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(48)
                ).apply {
                    bottomMargin = toPx(24)
                }
            }
            card.addView(spinner)

            // Button Container (Friend & Ignore)
            val btnContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Friend Button: allows the 2nd face for chosen time
            val friendBtn = Button(this).apply {
                text = "Friend (Allow 2nd Face)"
                isAllCaps = false
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#2E7D32")) // Forest green
                    cornerRadius = dp * 10f
                }
                background = btnBg
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    toPx(48),
                    1f
                ).apply {
                    marginEnd = toPx(8)
                }
                setOnClickListener {
                    val duration = timerOptions[spinner.selectedItemPosition].second
                    allowSecondFaceUntil = System.currentTimeMillis() + duration
                    scheduleBannerNotice("Friend mode enabled: 2nd face allowed for ${timerOptions[spinner.selectedItemPosition].first}")
                    hideOverlay()
                }
            }

            // Ignore Button: disables scanning completely for chosen time
            val ignoreBtn = Button(this).apply {
                text = "Ignore (Pause Scan)"
                isAllCaps = false
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                val btnBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#D32F2F")) // Red
                    cornerRadius = dp * 10f
                }
                background = btnBg
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    toPx(48),
                    1f
                ).apply {
                    marginStart = toPx(8)
                }
                setOnClickListener {
                    val duration = timerOptions[spinner.selectedItemPosition].second
                    pausedUntil = System.currentTimeMillis() + duration
                    scheduleBannerNotice("Scanning paused for ${timerOptions[spinner.selectedItemPosition].first}")
                    hideOverlay()
                }
            }

            btnContainer.addView(friendBtn)
            btnContainer.addView(ignoreBtn)
            card.addView(btnContainer)
            rootLayout.addView(card)

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                // Use hardware blur behind on Android 12 (API 31+) if supported
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    blurBehindRadius = 40
                }
            }

            try {
                windowManager?.addView(rootLayout, layoutParams)
                overlayView = rootLayout
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scheduleBannerNotice(message: String) {
        ContextCompat.getMainExecutor(this).execute {
            val dp = resources.displayMetrics.density
            val banner = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#37474F"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16, (28 * dp).toInt(), 16, (14 * dp).toInt())
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP }

            try {
                windowManager?.addView(banner, lp)
                pauseTimer?.cancel()
                pauseTimer = object : CountDownTimer(3500, 1000) {
                    override fun onTick(millisUntilFinished: Long) {}
                    override fun onFinish() {
                        try { windowManager?.removeView(banner) } catch (_: Exception) {}
                    }
                }.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun hideOverlay() {
        ContextCompat.getMainExecutor(this).execute {
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) {}
            }
            overlayView = null
            multiFaceFrames = 0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        pauseTimer?.cancel()
        Guard.onActiveChanged = null
        stopCamera()
        detector.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
