package github.leavesczy.alphavideoplayer

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import github.leavesczy.alphavideoplayer.internal.render.AlphaVideoRenderer
import github.leavesczy.alphavideoplayer.internal.widget.AlphaVideoGLTextureView
import github.leavesczy.alphavideoplayer.internal.widget.IAlphaVideoRenderView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates half-frame alpha playback on a dedicated player thread via [AlphaVideoMediaPlayer]
 * and OpenGL. Attach with [AlphaVideoView.setPlayer].
 * Backends: [AlphaVideoSystemPlayer],
 * [github.leavesczy.alphavideoplayer.exoplayer.AlphaVideoExoPlayer].
 *
 * Public methods may be called from any thread; [AlphaVideo.Listener] runs on the main thread.
 * No playlist. After [release], further commands are ignored.
 * [AlphaVideoView.setPlayer]`(null)` only detaches rendering — call [release] to free resources.
 */
abstract class AlphaVideoPlayer protected constructor(
    context: Context,
    private val mediaPlayer: AlphaVideoMediaPlayer
) {

    companion object {

        private const val COMPONENT = "AlphaVideoPlayer"

        private const val NEAR_EOS_SLACK_MS = 50L
    }

    private val appContext = context.applicationContext

    private var mediaItem: AlphaVideo.MediaItem? = null

    /** Held until the OES decoder surface exists. */
    private var pendingMediaItem: AlphaVideo.MediaItem? = null

    private var playerState = PlayerState.NOT_PREPARED

    private var playWhenReady = false

    private val released = AtomicBoolean(false)

    /**
     * Session id for orchestrator commands. Stale Prepared / FirstFrame / Completed / Error are
     * ignored. Decoder listeners are gated separately in [AlphaVideoMediaPlayer].
     */
    @Volatile
    private var playbackGeneration = 0

    private var awaitingPrepared = false

    private var surfaceHold: SurfaceHold = SurfaceHold.None

    @Volatile
    private var listener: AlphaVideo.Listener? = null

    private lateinit var renderView: IAlphaVideoRenderView

    private var currentSurface: Surface? = null

    private val playThread = HandlerThread(
        "AlphaVideoPlayer",
        Process.THREAD_PRIORITY_DISPLAY
    ).also { thread -> thread.start() }

    private val playHandler = Handler(playThread.looper)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val mediaPlayerCallback = object : AlphaVideoMediaPlayer.Callback {

        override fun onPrepared() {
            offer(command = PlayCommand.Prepared(generation = playbackGeneration))
        }

        override fun onFirstFrame() {
            if (released.get()) {
                return
            }
            offer(command = PlayCommand.FirstFrame(generation = playbackGeneration))
        }

        override fun onCompleted() {
            offer(command = PlayCommand.Completed(generation = playbackGeneration))
        }

        override fun onError(what: Int, extra: Int, message: String) {
            val detail = message.ifBlank { "what=$what extra=$extra" }
            offer(
                command = PlayCommand.Error(
                    message = "media player error: $detail",
                    what = what,
                    extra = extra,
                    generation = playbackGeneration
                )
            )
        }
    }

    init {
        initRenderView()
        offer(command = PlayCommand.Init)
    }

    fun setListener(listener: AlphaVideo.Listener?) {
        this.listener = listener
    }

    /** Linear gain in `0f..1f`; default `0f`. */
    fun setVolume(volume: Float) {
        offer(
            command = PlayCommand.SetVolume(
                volume = volume.coerceIn(
                    minimumValue = 0f,
                    maximumValue = 1f
                )
            )
        )
    }

    /** Replaces the item and resets the session; does not prepare or emit [AlphaVideo.Listener.onCompleted]. */
    fun setMediaItem(mediaItem: AlphaVideo.MediaItem) {
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "api setMediaItem source=${mediaItem.source} " +
                    "scaleType=${mediaItem.scaleType} layout=${mediaItem.layout} " +
                    "looping=${mediaItem.looping}"
        )
        offer(command = PlayCommand.SetMediaItem(mediaItem = mediaItem))
    }

    /** Clears GL sampling/geometry, then prepares (or defers until the decoder surface is ready). */
    fun prepare() {
        AlphaVideo.Log.i(component = COMPONENT, message = "api prepare")
        offer(command = PlayCommand.Prepare)
    }

    /**
     * Shows the render view, then starts, resumes, or prepare-then-starts.
     * Without a decoder surface, keeps play intent until the surface is ready.
     */
    fun play() {
        AlphaVideo.Log.i(component = COMPONENT, message = "api play")
        postToMain {
            showRenderView()
        }
        offer(command = PlayCommand.Play)
    }

    /** Clears play intent; does not emit [AlphaVideo.Listener.onCompleted]. */
    fun pause() {
        AlphaVideo.Log.i(component = COMPONENT, message = "api pause")
        offer(command = PlayCommand.Pause)
    }

    /** [AlphaVideo.Listener.onCompleted] only if playback had started (playing or paused). */
    fun stop() {
        AlphaVideo.Log.i(component = COMPONENT, message = "api stop")
        offer(command = PlayCommand.Stop)
    }

    /** Frees decoder and GL resources. Idempotent. */
    fun release() {
        if (!released.compareAndSet(false, true)) {
            AlphaVideo.Log.d(
                component = COMPONENT,
                message = "api release ignored (already released)"
            )
            return
        }
        AlphaVideo.Log.i(component = COMPONENT, message = "api release")
        offer(command = PlayCommand.Destroy)
    }

    internal fun attachRenderView(parentView: ViewGroup) {
        renderView.addParentView(parentView = parentView)
    }

    internal fun detachRenderView(parentView: ViewGroup) {
        renderView.removeParentView(parentView = parentView)
    }

    internal fun surfacePrepared(surface: Surface) {
        offer(command = PlayCommand.SurfacePrepared(surface = surface))
    }

    internal fun surfaceDestroyed() {
        offer(command = PlayCommand.SurfaceDestroyed)
    }

    internal fun renderSetupFailed(errorInfo: String) {
        offer(
            command = PlayCommand.Error(
                message = errorInfo,
                what = 0,
                extra = 0,
                generation = playbackGeneration
            )
        )
    }

    private fun initRenderView() {
        renderView = AlphaVideoGLTextureView(
            context = appContext,
            attrs = null
        )
        renderView.setLayoutParams(
            params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        renderView.setPlayer(player = this)
        renderView.setVideoRenderer(renderer = AlphaVideoRenderer(renderView = renderView))
    }

    private fun initMediaPlayer() {
        mediaPlayer.setCallback(callback = mediaPlayerCallback)
        mediaPlayer.init()
        mediaPlayer.setLooping(looping = false)
    }

    private fun showRenderView() {
        renderView.setVisibility(visibility = View.VISIBLE)
        renderView.bringToFront()
    }

    private fun offer(command: PlayCommand) {
        if (released.get() && command !is PlayCommand.Destroy) {
            return
        }
        playHandler.post {
            handleCommand(command = command)
        }
    }

    private fun handleCommand(command: PlayCommand) {
        when (command) {
            PlayCommand.Init -> {
                try {
                    initMediaPlayer()
                } catch (e: Exception) {
                    logError(throwable = e, message = "init media player failure")
                    failPlayback(message = "init media player failure: ${e.message ?: e}")
                }
            }

            is PlayCommand.SetVolume -> {
                mediaPlayer.setVolume(volume = command.volume)
            }

            is PlayCommand.SetMediaItem -> {
                mediaItem = command.mediaItem
                playWhenReady = false
                if (playerState != PlayerState.RELEASE) {
                    resetPlaybackSession(
                        nextState = PlayerState.NOT_PREPARED,
                        decoderAction = DecoderAction.StopThenReset,
                        clearPlayWhenReady = false
                    )
                } else {
                    pendingMediaItem = null
                    clearSurfaceHold()
                }
            }

            PlayCommand.Prepare -> {
                val item = mediaItem ?: return
                runSetSource {
                    applyMediaItem(mediaItem = item, startWhenReady = false)
                }
            }

            PlayCommand.Play -> {
                playWhenReady = true
                when (playerState) {
                    PlayerState.PAUSED,
                    PlayerState.PREPARED -> {
                        if (currentSurface != null) {
                            startPlay()
                        }
                    }

                    PlayerState.STARTED -> Unit
                    else -> {
                        val item = mediaItem ?: return
                        runSetSource {
                            applyMediaItem(mediaItem = item, startWhenReady = true)
                        }
                    }
                }
            }

            is PlayCommand.SurfacePrepared -> onDecoderSurfacePrepared(surface = command.surface)

            PlayCommand.SurfaceDestroyed -> onDecoderSurfaceDestroyed()

            is PlayCommand.Prepared -> {
                if (command.generation != playbackGeneration ||
                    playerState != PlayerState.NOT_PREPARED ||
                    !awaitingPrepared
                ) {
                    AlphaVideo.Log.d(
                        component = COMPONENT,
                        message = "Prepared dropped gen=${command.generation}/$playbackGeneration " +
                                "state=$playerState awaiting=$awaitingPrepared"
                    )
                    return
                }
                awaitingPrepared = false
                try {
                    parseVideoSize()
                    playerState = PlayerState.PREPARED
                    AlphaVideo.Log.i(
                        component = COMPONENT,
                        message = "state PREPARED gen=$playbackGeneration playWhenReady=$playWhenReady"
                    )
                    if (playWhenReady) {
                        startPlay()
                    }
                } catch (e: Exception) {
                    logError(throwable = e, message = "start video failure")
                    failPlayback(message = "start video failure: ${e.message ?: e}")
                }
            }

            is PlayCommand.FirstFrame -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideo.Log.d(
                        component = COMPONENT,
                        message = "FirstFrame dropped gen=${command.generation}/$playbackGeneration"
                    )
                    return
                }
                when (playerState) {
                    PlayerState.STARTED,
                    PlayerState.PAUSED -> {
                        AlphaVideo.Log.d(
                            component = COMPONENT,
                            message = "firstFrame accepted state=$playerState gen=$playbackGeneration"
                        )
                        renderView.onFirstFrame()
                    }

                    else -> {
                        AlphaVideo.Log.d(
                            component = COMPONENT,
                            message = "FirstFrame ignored state=$playerState gen=$playbackGeneration"
                        )
                    }
                }
            }

            PlayCommand.Pause -> {
                playWhenReady = false
                dropSurfaceResumeIntent()
                if (playerState == PlayerState.STARTED) {
                    runCatching { mediaPlayer.pause() }
                    playerState = PlayerState.PAUSED
                    AlphaVideo.Log.i(component = COMPONENT, message = "state PAUSED")
                }
            }

            PlayCommand.Stop -> {
                playWhenReady = false
                stopInternal(emitCompleted = true)
            }

            is PlayCommand.Completed -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideo.Log.d(
                        component = COMPONENT,
                        message = "Completed dropped gen=${command.generation}/$playbackGeneration"
                    )
                    return
                }
                if (playerState != PlayerState.STARTED &&
                    playerState != PlayerState.PAUSED
                ) {
                    AlphaVideo.Log.d(
                        component = COMPONENT,
                        message = "Completed ignored state=$playerState gen=$playbackGeneration"
                    )
                    return
                }
                resetPlaybackSession(
                    nextState = PlayerState.ENDED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = true
                )
                emitCompleted()
            }

            is PlayCommand.Error -> {
                if (command.generation != playbackGeneration) {
                    AlphaVideo.Log.d(
                        component = COMPONENT,
                        message = "Error dropped gen=${command.generation}/$playbackGeneration " +
                                "msg=${command.message}"
                    )
                    return
                }
                failPlayback(
                    message = command.message,
                    what = command.what,
                    extra = command.extra
                )
            }

            PlayCommand.Destroy -> destroyInternal()
        }
    }

    private fun applyMediaItem(mediaItem: AlphaVideo.MediaItem, startWhenReady: Boolean) {
        if (startWhenReady) {
            playWhenReady = true
        }
        invalidatePlayback()
        pendingMediaItem = null
        clearSurfaceHold()
        runCatching { mediaPlayer.reset() }
        renderView.onCompletion()
        playerState = PlayerState.NOT_PREPARED
        if (!bindDecoderSurface(surface = currentSurface)) {
            return
        }
        bindMediaItemOutput(mediaItem = mediaItem, requireSurface = true)
    }

    private fun handlePendingMediaItem(): Boolean {
        val item = pendingMediaItem ?: return false
        pendingMediaItem = null
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "pendingMediaItem applied source=${item.source}"
        )
        runSetSource {
            bindMediaItemOutput(mediaItem = item, requireSurface = false)
        }
        return true
    }

    private fun bindMediaItemOutput(mediaItem: AlphaVideo.MediaItem, requireSurface: Boolean) {
        val sourcePath = resolveSourcePath(source = mediaItem.source) ?: return
        renderView.setScaleType(scaleType = mediaItem.scaleType)
        renderView.setLayout(layout = mediaItem.layout)
        mediaPlayer.setLooping(looping = mediaItem.looping)
        if (requireSurface && !renderView.isSurfaceCreated()) {
            pendingMediaItem = mediaItem
            AlphaVideo.Log.i(
                component = COMPONENT,
                message = "pendingMediaItem deferred (no decoder surface) source=${mediaItem.source}"
            )
            return
        }
        mediaPlayer.setSource(path = sourcePath)
        prepareMediaPlayer()
    }

    /** Absolute file path or `asset:///<path>`; `null` if [failPlayback] was already invoked. */
    private fun resolveSourcePath(source: AlphaVideo.Source): String? {
        return when (source) {
            is AlphaVideo.Source.File -> {
                val videoFile = source.file
                if (!videoFile.exists()) {
                    failPlayback(message = "file does not exist: ${videoFile.absolutePath}")
                    return null
                }
                videoFile.absolutePath
            }

            is AlphaVideo.Source.Asset -> {
                val assetPath = source.path
                if (assetPath.isBlank()) {
                    failPlayback(message = "asset path is empty")
                    return null
                }
                try {
                    appContext.assets.open(assetPath).close()
                } catch (e: Exception) {
                    failPlayback(
                        message = "asset does not exist: $assetPath (${e.message ?: e})"
                    )
                    return null
                }
                "asset:///$assetPath"
            }
        }
    }

    private fun prepareMediaPlayer() {
        if (playerState != PlayerState.NOT_PREPARED) {
            return
        }
        awaitingPrepared = true
        mediaPlayer.prepare()
    }

    /** False when a non-null surface could not be attached (after [failPlayback]). */
    private fun bindDecoderSurface(surface: Surface?): Boolean {
        val ok = mediaPlayer.setSurface(surface = surface)
        if (!ok && surface != null) {
            failPlayback(message = "setSurface failure")
            return false
        }
        return true
    }

    private fun startPlay() {
        if (currentSurface == null) {
            return
        }
        when (playerState) {
            PlayerState.PREPARED -> {
                try {
                    mediaPlayer.start()
                    playerState = PlayerState.STARTED
                    AlphaVideo.Log.i(
                        component = COMPONENT,
                        message = "state STARTED gen=$playbackGeneration"
                    )
                    rearmFirstFrameIfHeld()
                    val callback = listener
                    postToMain {
                        AlphaVideo.Log.i(component = COMPONENT, message = "listener onStart")
                        callback?.onStart()
                    }
                } catch (e: Exception) {
                    logError(throwable = e, message = "start failure")
                    failPlayback(message = "start failure: ${e.message ?: e}")
                }
            }

            PlayerState.PAUSED -> {
                try {
                    mediaPlayer.start()
                    playerState = PlayerState.STARTED
                    AlphaVideo.Log.i(
                        component = COMPONENT,
                        message = "state STARTED (resume) gen=$playbackGeneration"
                    )
                    rearmFirstFrameIfHeld()
                } catch (e: Exception) {
                    logError(throwable = e, message = "resume failure")
                    failPlayback(message = "resume failure: ${e.message ?: e}")
                }
            }

            else -> Unit
        }
    }

    private fun rearmFirstFrameIfHeld() {
        if (surfaceHold !is SurfaceHold.Lost) {
            return
        }
        clearSurfaceHold()
        renderView.onFirstFrame()
    }

    private fun stopInternal(emitCompleted: Boolean) {
        when (playerState) {
            PlayerState.STARTED,
            PlayerState.PAUSED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Stop,
                    clearPlayWhenReady = false
                )
                if (emitCompleted) {
                    emitCompleted()
                }
            }

            PlayerState.NOT_PREPARED,
            PlayerState.PREPARED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = false
                )
            }

            PlayerState.ENDED -> {
                resetPlaybackSession(
                    nextState = PlayerState.STOPPED,
                    decoderAction = DecoderAction.Reset,
                    clearPlayWhenReady = true
                )
            }

            else -> Unit
        }
    }

    private fun failPlayback(message: String, what: Int = 0, extra: Int = 0) {
        if (playerState == PlayerState.RELEASE) {
            return
        }
        resetPlaybackSession(
            nextState = PlayerState.STOPPED,
            decoderAction = DecoderAction.Reset,
            clearPlayWhenReady = true
        )
        emitError(message = message, what = what, extra = extra)
    }

    private fun resetPlaybackSession(
        nextState: PlayerState,
        decoderAction: DecoderAction,
        clearPlayWhenReady: Boolean
    ) {
        val fromState = playerState
        invalidatePlayback()
        pendingMediaItem = null
        clearSurfaceHold()
        if (clearPlayWhenReady) {
            playWhenReady = false
        }
        when (decoderAction) {
            DecoderAction.Stop -> runCatching { mediaPlayer.stop() }
            DecoderAction.Reset -> runCatching { mediaPlayer.reset() }
            DecoderAction.StopThenReset -> {
                when (playerState) {
                    PlayerState.STARTED,
                    PlayerState.PAUSED -> runCatching { mediaPlayer.stop() }

                    else -> Unit
                }
                runCatching { mediaPlayer.reset() }
            }
        }
        renderView.onCompletion()
        playerState = nextState
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "session reset $fromState->$nextState action=$decoderAction " +
                    "gen=$playbackGeneration playWhenReady=$playWhenReady"
        )
    }

    private fun invalidatePlayback() {
        playbackGeneration++
        awaitingPrepared = false
    }

    private fun onDecoderSurfacePrepared(surface: Surface) {
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "surface prepared state=$playerState hold=$surfaceHold " +
                    "playWhenReady=$playWhenReady pending=${pendingMediaItem != null}"
        )
        currentSurface = surface
        when (val hold = surfaceHold) {
            is SurfaceHold.Lost -> {
                if (hold.pausedDecoder &&
                    playerState == PlayerState.PAUSED &&
                    shouldCompleteAfterSurfaceLoss(remainingAtLossMs = hold.remainingAtLossMs)
                ) {
                    clearSurfaceHold()
                    playWhenReady = false
                    if (!bindDecoderSurface(surface = surface)) {
                        return
                    }
                    stopInternal(emitCompleted = true)
                    return
                }
                if (hold.pausedDecoder) {
                    surfaceHold = SurfaceHold.Lost(
                        pausedDecoder = false,
                        remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
                    )
                }
            }

            SurfaceHold.None -> Unit
        }
        if (!bindDecoderSurface(surface = surface)) {
            return
        }
        if (handlePendingMediaItem()) {
            return
        }
        if (playWhenReady &&
            (playerState == PlayerState.PREPARED || playerState == PlayerState.PAUSED)
        ) {
            startPlay()
        }
    }

    private fun onDecoderSurfaceDestroyed() {
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "surface destroyed state=$playerState playWhenReady=$playWhenReady"
        )
        currentSurface = null
        renderView.onSurfaceLost()
        bindDecoderSurface(surface = null)
        if (playerState == PlayerState.STARTED) {
            val remaining = remainingDurationMs()
            runCatching { mediaPlayer.pause() }
            playerState = PlayerState.PAUSED
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = true,
                remainingAtLossMs = remaining
            )
        } else {
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = false,
                remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
            )
        }
    }

    private fun remainingDurationMs(): Long {
        if (mediaItem?.looping == true) {
            return AlphaVideoMediaPlayer.TIME_UNSET
        }
        val duration = mediaPlayer.getDuration()
        val position = mediaPlayer.getCurrentPosition()
        if (duration == AlphaVideoMediaPlayer.TIME_UNSET ||
            position == AlphaVideoMediaPlayer.TIME_UNSET ||
            duration < 0 ||
            position < 0
        ) {
            return AlphaVideoMediaPlayer.TIME_UNSET
        }
        return (duration - position).coerceAtLeast(minimumValue = 0L)
    }

    private fun shouldCompleteAfterSurfaceLoss(remainingAtLossMs: Long): Boolean {
        if (mediaItem?.looping == true) {
            return false
        }
        return remainingAtLossMs != AlphaVideoMediaPlayer.TIME_UNSET &&
                remainingAtLossMs <= NEAR_EOS_SLACK_MS
    }

    private fun clearSurfaceHold() {
        surfaceHold = SurfaceHold.None
    }

    /** Drops auto-resume / near-EOS intent; may leave [SurfaceHold.Lost] for GL sampling rearm. */
    private fun dropSurfaceResumeIntent() {
        val hold = surfaceHold
        if (hold is SurfaceHold.Lost && hold.pausedDecoder) {
            surfaceHold = SurfaceHold.Lost(
                pausedDecoder = false,
                remainingAtLossMs = AlphaVideoMediaPlayer.TIME_UNSET
            )
        }
    }

    private fun parseVideoSize() {
        val videoSize = mediaPlayer.getVideoSize()
        if (videoSize.width <= 0 || videoSize.height <= 0) {
            throw IllegalStateException(
                "invalid video size: ${videoSize.width}x${videoSize.height}"
            )
        }
        val layout = renderView.getLayout()
        val (width, height) = rgbHalfSize(
            sourceWidth = videoSize.width,
            sourceHeight = videoSize.height,
            layout = layout
        )
        val scaleType = renderView.getScaleType()
        AlphaVideo.Log.i(
            component = COMPONENT,
            message = "videoSize source=${videoSize.width}x${videoSize.height} " +
                    "rgbHalf=${width}x${height} " +
                    "view=${renderView.getMeasuredWidth()}x${renderView.getMeasuredHeight()} " +
                    "scaleType=$scaleType layout=$layout"
        )
        renderView.measureInternal(
            videoWidth = width.toFloat(),
            videoHeight = height.toFloat()
        )
        val callback = listener
        postToMain {
            AlphaVideo.Log.i(
                component = COMPONENT,
                message = "listener onVideoSizeChanged ${width}x${height}"
            )
            callback?.onVideoSizeChanged(
                width = width,
                height = height
            )
        }
    }

    private fun rgbHalfSize(
        sourceWidth: Int,
        sourceHeight: Int,
        layout: AlphaVideo.Layout
    ): Pair<Int, Int> {
        return when (layout) {
            AlphaVideo.Layout.AlphaLeft, AlphaVideo.Layout.AlphaRight -> {
                sourceWidth / 2 to sourceHeight
            }

            AlphaVideo.Layout.AlphaTop, AlphaVideo.Layout.AlphaBottom -> {
                sourceWidth to sourceHeight / 2
            }
        }
    }

    private fun destroyInternal() {
        invalidatePlayback()
        playHandler.removeCallbacksAndMessages(null)
        pendingMediaItem = null
        clearSurfaceHold()
        playWhenReady = false
        when (playerState) {
            PlayerState.STARTED -> {
                runCatching { mediaPlayer.pause() }
                playerState = PlayerState.PAUSED
            }

            else -> Unit
        }
        when (playerState) {
            PlayerState.PAUSED,
            PlayerState.PREPARED,
            PlayerState.ENDED -> {
                runCatching { mediaPlayer.stop() }
                playerState = PlayerState.STOPPED
            }

            else -> Unit
        }
        mediaPlayer.release()
        currentSurface = null
        renderView.release()
        renderView.onPause()
        playerState = PlayerState.RELEASE
        clearCallbacks()
        // Let Media3 (and other) looper-bound release work finish before quitting the thread.
        playHandler.post {
            playThread.quitSafely()
        }
    }

    private fun clearCallbacks() {
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runSetSource(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logError(throwable = e, message = "set source failure")
            failPlayback(message = "set source failure: ${e.message ?: e}")
        }
    }

    private fun emitCompleted() {
        val callback = listener
        postToMain {
            AlphaVideo.Log.i(component = COMPONENT, message = "listener onCompleted")
            callback?.onCompleted()
        }
    }

    private fun emitError(message: String, what: Int = 0, extra: Int = 0) {
        val callback = listener
        postToMain {
            AlphaVideo.Log.e(
                component = COMPONENT,
                message = "listener onError what=$what extra=$extra msg=$message"
            )
            callback?.onError(
                error = AlphaVideo.Error(
                    message = message,
                    what = what,
                    extra = extra
                )
            )
        }
    }

    private fun logError(throwable: Throwable, message: String) {
        AlphaVideo.Log.e(
            component = COMPONENT,
            message = message,
            throwable = throwable
        )
    }

    private fun postToMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    /**
     * Surface-loss bookkeeping until the next successful [startPlay] (or session reset).
     * [Lost.pausedDecoder]: decoder was paused while playing (auto-resume / near-EOS).
     * [Lost.remainingAtLossMs]: remaining duration snapshot at loss for near-EOS complete.
     * Any [Lost]: rearm GL sampling on the next [startPlay].
     */
    private sealed class SurfaceHold {
        data object None : SurfaceHold()

        data class Lost(
            val pausedDecoder: Boolean,
            val remainingAtLossMs: Long
        ) : SurfaceHold()
    }

    private enum class DecoderAction {
        Stop,
        Reset,
        StopThenReset
    }

    private enum class PlayerState {
        NOT_PREPARED,
        PREPARED,
        STARTED,
        PAUSED,
        STOPPED,
        ENDED,
        RELEASE
    }

    private sealed interface PlayCommand {

        data object Init : PlayCommand

        data class SetMediaItem(val mediaItem: AlphaVideo.MediaItem) : PlayCommand

        data object Prepare : PlayCommand

        data object Play : PlayCommand

        data object Pause : PlayCommand

        data object Stop : PlayCommand

        data class SetVolume(val volume: Float) : PlayCommand

        data class Prepared(val generation: Int) : PlayCommand

        data class FirstFrame(val generation: Int) : PlayCommand

        data class Completed(val generation: Int) : PlayCommand

        data class Error(
            val message: String,
            val what: Int,
            val extra: Int,
            val generation: Int
        ) : PlayCommand

        data class SurfacePrepared(val surface: Surface) : PlayCommand

        data object SurfaceDestroyed : PlayCommand

        data object Destroy : PlayCommand
    }

}
