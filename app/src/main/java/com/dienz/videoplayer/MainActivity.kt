package com.dienz.videoplayer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var pickBtn: Button
    private lateinit var modeBtn: Button
    private val hideInfo = Runnable { info.visibility = View.GONE }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            play(it)
        }
    }

    // ---- gesture state ----
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var multi = false
    private var mode = 0              // 0 = belum tentu, 1 = seek, 2 = volume/kecerahan
    private var rightSide = false
    private var startVol = 0
    private var startBri = 0.5f
    private var startPos = 0L
    private var seekTarget = -1L
    private var lastTap = 0L

    // ---- zoom state ----
    private var zoom = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastMx = Float.NaN
    private var lastMy = Float.NaN

    // ---- mod paparan ----
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch 100%",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop (isi skrin)"
    )
    private var resizeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0x99000000.toInt())
            setPadding(48, 24, 48, 24)
            visibility = View.GONE
        }
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        pickBtn = Button(this).apply {
            text = "Pilih Video"
            setOnClickListener { picker.launch(arrayOf("video/*", "application/octet-stream")) }
        }
        root.addView(pickBtn, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        modeBtn = Button(this).apply {
            text = resizeModes[0].second
            visibility = View.GONE
            setOnClickListener { cycleResizeMode() }
        }
        root.addView(
            modeBtn,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 48, 48, 0) }
        )

        // butang mod paparan muncul/hilang bersama kawalan video
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { vis ->
                modeBtn.visibility = if (vis == View.VISIBLE && pickBtn.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        )

        setContentView(root)

        setupPlayer()
        setupGestures()
        force60Hz()

        intent?.data?.let { play(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { play(it) }
    }

    private fun setupPlayer() {
        // Hardware decoder diutamakan; kalau tak boleh decode, guna FFmpeg (software)
        val factory = NextRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, factory).build().also { playerView.player = it }
    }

    private fun play(uri: Uri) {
        pickBtn.visibility = View.GONE
        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    // ---------- 60Hz ----------
    private fun force60Hz() {
        @Suppress("DEPRECATION")
        val d = windowManager.defaultDisplay
        val cur = d.mode
        val m = d.supportedModes.firstOrNull {
            it.physicalWidth == cur.physicalWidth &&
                it.physicalHeight == cur.physicalHeight &&
                it.refreshRate.roundToInt() == 60
        } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = m.modeId }
    }

    // ---------- Fit / Stretch / Crop ----------
    private fun cycleResizeMode() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        val (m, label) = resizeModes[resizeIndex]
        playerView.resizeMode = m
        modeBtn.text = label
        zoom = 1f; tx = 0f; ty = 0f
        applyZoom()
        showInfo(label)
    }

    // ---------- Zoom ----------
    private fun applyZoom() {
        val s = playerView.videoSurfaceView ?: return
        val maxX = (zoom - 1f) * playerView.width / 2f
        val maxY = (zoom - 1f) * playerView.height / 2f
        tx = tx.coerceIn(-maxX, maxX)
        ty = ty.coerceIn(-maxY, maxY)
        s.scaleX = zoom
        s.scaleY = zoom
        s.translationX = tx
        s.translationY = ty
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ---------- Gesture ----------
    // kanan/kiri = seek, atas/bawah kanan = volume, atas/bawah kiri = kecerahan, dua jari = zoom
    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
                applyZoom()
                showInfo("Zoom  ${(zoom * 100).roundToInt()}%")
                return true
            }
        })

        playerView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    moved = false; multi = false; mode = 0; seekTarget = -1L
                    lastMx = Float.NaN; lastMy = Float.NaN
                    rightSide = e.x > v.width / 2f
                    startVol = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBri = if (b < 0f) 0.5f else b
                    startPos = player?.currentPosition ?: 0L
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true; mode = 0; seekTarget = -1L
                    lastMx = (e.getX(0) + e.getX(1)) / 2f
                    lastMy = (e.getY(0) + e.getY(1)) / 2f
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    lastMx = Float.NaN; lastMy = Float.NaN
                }

                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        // dua jari: geser gambar bila sedang zoom
                        val mx = (e.getX(0) + e.getX(1)) / 2f
                        val my = (e.getY(0) + e.getY(1)) / 2f
                        if (!lastMx.isNaN() && zoom > 1f) {
                            tx += mx - lastMx
                            ty += my - lastMy
                            applyZoom()
                        }
                        lastMx = mx; lastMy = my
                    } else if (!multi) {
                        val dx = e.x - downX
                        val dy = downY - e.y   // swipe ke atas = positif

                        if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                            mode = if (abs(dx) > abs(dy)) 1 else 2
                            moved = true
                        }

                        if (mode == 1) {
                            val dur = player?.duration ?: 0L
                            if (dur > 0) {
                                val deltaMs = (dx / v.width * 120_000f).toLong()
                                seekTarget = (startPos + deltaMs).coerceIn(0L, dur)
                                val diff = seekTarget - startPos
                                val sign = if (diff >= 0) "+" else "-"
                                showInfo("${fmt(seekTarget)}  ($sign${fmt(abs(diff))})")
                            }
                        } else if (mode == 2) {
                            val delta = dy / v.height
                            if (rightSide) {
                                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                val vol = (startVol + delta * max * 1.5f).roundToInt().coerceIn(0, max)
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, vol, 0)
                                showInfo("Volume  ${(vol * 100f / max).roundToInt()}%")
                            } else {
                                val bri = (startBri + delta * 1.5f).coerceIn(0.01f, 1f)
                                window.attributes = window.attributes.apply { screenBrightness = bri }
                                showInfo("Kecerahan  ${(bri * 100).roundToInt()}%")
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!multi) {
                        if (mode == 1 && seekTarget >= 0) {
                            player?.seekTo(seekTarget)
                        } else if (!moved) {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTap < 300) {
                                // ketuk dua kali = reset zoom
                                zoom = 1f; tx = 0f; ty = 0f
                                applyZoom()
                                showInfo("Zoom 100%")
                                lastTap = 0L
                            } else {
                                lastTap = now
                                if (playerView.isControllerFullyVisible) playerView.hideController()
                                else playerView.showController()
                            }
                        }
                    }
                    mode = 0; seekTarget = -1L
                }

                MotionEvent.ACTION_CANCEL -> {
                    mode = 0; seekTarget = -1L
                }
            }
            true
        }
    }

    private fun showInfo(text: String) {
        info.text = text
        info.visibility = View.VISIBLE
        info.removeCallbacks(hideInfo)
        info.postDelayed(hideInfo, 800)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
