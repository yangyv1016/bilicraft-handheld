package com.bilicraft.handheld.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bilicraft.handheld.R
import kotlinx.coroutines.delay

/** 应用内播放随 APK 分发的彩蛋视频，不依赖网络或外部播放器。 */
class EasterEggVideoActivity : ComponentActivity() {
    private var activeMediaPlayer: MediaPlayer? = null
    private var resumeAfterPause = false
    private val fullScreenState = mutableStateOf(false)
    private var orientationBeforeFullScreen = Configuration.ORIENTATION_UNDEFINED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                EasterEggVideoPage(
                    isFullScreen = fullScreenState.value,
                    onFullScreenChange = ::setFullScreen,
                    onClose = { finish() },
                    onMediaPlayerCreated = { activeMediaPlayer = it }
                )
            }
        }
    }

    override fun onPause() {
        resumeAfterPause = runCatching { activeMediaPlayer?.isPlaying == true }.getOrDefault(false)
        runCatching { activeMediaPlayer?.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterPause) runCatching { activeMediaPlayer?.start() }
    }

    private fun setFullScreen(enabled: Boolean) {
        if (fullScreenState.value == enabled) return

        fullScreenState.value = enabled
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            orientationBeforeFullScreen = resources.configuration.orientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.setDecorFitsSystemWindows(window, false)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            requestedOrientation = when (orientationBeforeFullScreen) {
                Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Configuration.ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_USER
            }
            WindowCompat.setDecorFitsSystemWindows(window, true)
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        if (fullScreenState.value) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        runCatching { activeMediaPlayer?.stop() }
        runCatching { activeMediaPlayer?.release() }
        activeMediaPlayer = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, EasterEggVideoActivity::class.java)
    }
}

@Composable
private fun EasterEggVideoPage(
    isFullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onMediaPlayerCreated: (MediaPlayer) -> Unit
) {
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsKeepAliveToken by remember { mutableIntStateOf(0) }
    val controlsInteractionSource = remember { MutableInteractionSource() }
    val latestIsFullScreen = rememberUpdatedState(isFullScreen)

    fun showControls() {
        controlsVisible = true
        controlsKeepAliveToken++
    }

    BackHandler(enabled = isFullScreen) {
        onFullScreenChange(false)
    }

    LaunchedEffect(isFullScreen, controlsKeepAliveToken) {
        if (isFullScreen) {
            controlsVisible = true
            delay(FULL_SCREEN_CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        } else {
            controlsVisible = true
        }
    }

    LaunchedEffect(prepared, seeking) {
        while (prepared) {
            val current = mediaPlayer
            if (!seeking && current != null) {
                positionMs = runCatching { current.currentPosition }.getOrDefault(positionMs)
                playing = runCatching { current.isPlaying }.getOrDefault(false)
            }
            delay(250)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!isFullScreen) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "彩蛋",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { viewContext ->
                    val videoTextureView = EmbeddedVideoTextureView(viewContext).apply {
                        keepScreenOn = true
                        val createdPlayer = MediaPlayer.create(
                            viewContext,
                            R.raw.easter_egg_eclipse
                        )
                        if (createdPlayer == null) {
                            errorMessage = "内置视频无法播放"
                        } else {
                            attachPlayer(createdPlayer)
                            setVideoSize(createdPlayer.videoWidth, createdPlayer.videoHeight)
                            createdPlayer.isLooping = false
                            createdPlayer.setOnVideoSizeChangedListener { _, width, height ->
                                setVideoSize(width, height)
                            }
                            createdPlayer.setOnCompletionListener {
                                positionMs = durationMs
                                playing = false
                                completed = true
                            }
                            createdPlayer.setOnErrorListener { _, _, _ ->
                                prepared = false
                                playing = false
                                errorMessage = "内置视频无法播放"
                                true
                            }
                            mediaPlayer = createdPlayer
                            onMediaPlayerCreated(createdPlayer)
                            durationMs = createdPlayer.duration.coerceAtLeast(0)
                            prepared = true
                            completed = false
                            post {
                                runCatching {
                                    createdPlayer.start()
                                    playing = true
                                }.onFailure {
                                    prepared = false
                                    errorMessage = "内置视频无法播放"
                                }
                            }
                        }
                    }
                    val overlayView = ComposeView(viewContext).apply {
                        setViewCompositionStrategy(
                            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                        )
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setContent {
                            MaterialTheme {
                                Box(Modifier.fillMaxSize()) {
                                    if (latestIsFullScreen.value) {
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable(
                                                    interactionSource = controlsInteractionSource,
                                                    indication = null
                                                ) {
                                                    if (controlsVisible) {
                                                        controlsVisible = false
                                                    } else {
                                                        showControls()
                                                    }
                                                }
                                        )
                                    }

                                    errorMessage?.let { message ->
                                        Text(
                                            text = message,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }

                                    if (
                                        errorMessage == null &&
                                        (!latestIsFullScreen.value || controlsVisible)
                                    ) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            color = Color.Black.copy(alpha = 0.74f),
                                            shape = MaterialTheme.shapes.large,
                                            shadowElevation = 6.dp
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Slider(
                                                    value = positionMs.toFloat().coerceIn(
                                                        0f,
                                                        durationMs.coerceAtLeast(1).toFloat()
                                                    ),
                                                    onValueChange = {
                                                        showControls()
                                                        seeking = true
                                                        positionMs = it.toInt()
                                                    },
                                                    onValueChangeFinished = {
                                                        showControls()
                                                        mediaPlayer?.seekTo(positionMs)
                                                        seeking = false
                                                    },
                                                    valueRange = 0f..durationMs.coerceAtLeast(1)
                                                        .toFloat(),
                                                    enabled = prepared
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            showControls()
                                                            val current = mediaPlayer
                                                                ?: return@IconButton
                                                            if (completed) {
                                                                current.seekTo(0)
                                                                positionMs = 0
                                                                completed = false
                                                                current.start()
                                                                playing = true
                                                            } else if (current.isPlaying) {
                                                                current.pause()
                                                                playing = false
                                                            } else {
                                                                current.start()
                                                                playing = true
                                                            }
                                                        },
                                                        enabled = prepared
                                                    ) {
                                                        Icon(
                                                            imageVector = when {
                                                                completed -> Icons.Default.Replay
                                                                playing -> Icons.Default.Pause
                                                                else -> Icons.Default.PlayArrow
                                                            },
                                                            contentDescription = when {
                                                                completed -> "重新播放"
                                                                playing -> "暂停"
                                                                else -> "播放"
                                                            },
                                                            tint = Color.White
                                                        )
                                                    }
                                                    Text(
                                                        text = "${formatVideoTime(positionMs)} / " +
                                                            formatVideoTime(durationMs),
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                    Spacer(Modifier.weight(1f))
                                                    IconButton(
                                                        onClick = {
                                                            showControls()
                                                            onFullScreenChange(
                                                                !latestIsFullScreen.value
                                                            )
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = if (
                                                                latestIsFullScreen.value
                                                            ) {
                                                                Icons.Default.FullscreenExit
                                                            } else {
                                                                Icons.Default.Fullscreen
                                                            },
                                                            contentDescription = if (
                                                                latestIsFullScreen.value
                                                            ) {
                                                                "退出全屏"
                                                            } else {
                                                                "全屏"
                                                            },
                                                            tint = Color.White
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    FrameLayout(viewContext).apply {
                        addView(
                            videoTextureView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        addView(
                            overlayView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** TextureView 不会像 SurfaceView 那样遮住 Compose 浮层。 */
private class EmbeddedVideoTextureView(context: Context) : TextureView(context) {
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                bindSurface(surfaceTexture)
                updateVideoTransform()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                runCatching { player?.setSurface(null) }
                playerSurface?.release()
                playerSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun attachPlayer(mediaPlayer: MediaPlayer) {
        player = mediaPlayer
        if (isAvailable) {
            surfaceTexture?.let(::bindSurface)
        }
    }

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        updateVideoTransform()
    }

    private fun bindSurface(surfaceTexture: SurfaceTexture) {
        playerSurface?.release()
        playerSurface = Surface(surfaceTexture)
        runCatching { player?.setSurface(playerSurface) }
    }

    private fun updateVideoTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val viewRatio = width.toFloat() / height.toFloat()
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (videoRatio > viewRatio) {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        } else {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        }

        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, width / 2f, height / 2f)
            }
        )
    }
}

private fun formatVideoTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private const val FULL_SCREEN_CONTROLS_TIMEOUT_MS = 3_000L
