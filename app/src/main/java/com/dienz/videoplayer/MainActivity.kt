package com.dienz.videoplayer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private lateinit var pickBtn: Button
    private val hideInfo = Runnable { info.visibility = View.GONE }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            play(it)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var rightSide = false
    private var startVol = 0
    private var startBri = 0.5f

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

    private fun setupGestures() {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val slop = ViewConfiguration.get(this).scaledTouchSlop

        playerView.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    rightSide = e.x > v.width / 2f
                    startVol = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    val b = window.attributes.screenBrightness
                    startBri = if (b < 0f) 0.5f else b
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = downY - e.y
                    if (!moved && abs(dy) > slop) moved = true
                    if (moved) {
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
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (playerView.isControllerFullyVisible) playerView.hideController()
                        else playerView.showController()
                    }
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
