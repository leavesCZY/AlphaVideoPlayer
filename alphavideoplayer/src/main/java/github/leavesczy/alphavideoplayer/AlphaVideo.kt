package github.leavesczy.alphavideoplayer

import java.io.File

object AlphaVideo {

    /** How the RGB half maps into [AlphaVideoView]. */
    enum class ScaleType {
        CenterCrop,
        FitWidth,
        FitCenter,
        FitTop,
        FitBottom;
    }

    /** Half-frame pack layout after [android.graphics.SurfaceTexture] transform. */
    enum class Layout {
        AlphaLeft,
        AlphaRight,
        AlphaTop,
        AlphaBottom
    }

    /** Packed decoder frame size; [Listener.onVideoSizeChanged] reports the RGB half. */
    data class VideoSize(
        val width: Int,
        val height: Int
    )

    /**
     * [message] is always set. [what] / [extra] are backend decoder codes when available;
     * library-level failures often use `0`.
     */
    data class Error(
        val message: String,
        val what: Int,
        val extra: Int
    )

    /** Main-thread playback callbacks. */
    interface Listener {

        fun onVideoSizeChanged(width: Int, height: Int) {
        }

        /** Fired only on prepared → started (not when resuming after [AlphaVideoPlayer.pause]). */
        fun onStart() {
        }

        /**
         * Natural end (non-looping), [AlphaVideoPlayer.stop] after playback had started
         * (playing or paused), or surface restore when remaining duration at loss was already
         * near zero. Not after prepare-only stop, [onError], [AlphaVideoPlayer.release], or
         * [AlphaVideoPlayer.setMediaItem].
         */
        fun onCompleted() {
        }

        /** Ends the current item without [onCompleted]. */
        fun onError(error: Error) {
        }
    }

    sealed interface Source {

        data class File(val file: java.io.File) : Source

        /** Relative path under `assets/`, e.g. `videos/foo.mp4`. */
        data class Asset(val path: String) : Source

    }

    @ConsistentCopyVisibility
    data class MediaItem private constructor(
        val source: Source,
        val scaleType: ScaleType,
        val layout: Layout,
        val looping: Boolean
    ) {

        companion object {

            fun fromFile(
                file: File,
                scaleType: ScaleType,
                layout: Layout,
                looping: Boolean
            ): MediaItem {
                return MediaItem(
                    source = Source.File(file = file),
                    scaleType = scaleType,
                    layout = layout,
                    looping = looping
                )
            }

            fun fromAsset(
                assetPath: String,
                scaleType: ScaleType,
                layout: Layout,
                looping: Boolean
            ): MediaItem {
                val path = assetPath.trimStart(chars = charArrayOf('/'))
                require(value = path.isNotBlank()) {
                    "asset path must not be blank"
                }
                return MediaItem(
                    source = Source.Asset(path = path),
                    scaleType = scaleType,
                    layout = layout,
                    looping = looping
                )
            }
        }
    }

    /** Optional logcat diagnostics (tag `AlphaVideoPlayer`). */
    object Log {

        private const val TAG = "AlphaVideoPlayer"

        @Volatile
        var enabled: Boolean = false

        fun d(component: String, message: String) {
            if (enabled) {
                android.util.Log.d(TAG, format(component = component, message = message))
            }
        }

        fun i(component: String, message: String) {
            if (enabled) {
                android.util.Log.i(TAG, format(component = component, message = message))
            }
        }

        fun w(component: String, message: String, throwable: Throwable? = null) {
            if (!enabled) {
                return
            }
            if (throwable != null) {
                android.util.Log.w(TAG, format(component = component, message = message), throwable)
            } else {
                android.util.Log.w(TAG, format(component = component, message = message))
            }
        }

        fun e(component: String, message: String, throwable: Throwable? = null) {
            if (!enabled) {
                return
            }
            if (throwable != null) {
                android.util.Log.e(TAG, format(component = component, message = message), throwable)
            } else {
                android.util.Log.e(TAG, format(component = component, message = message))
            }
        }

        private fun format(component: String, message: String): String {
            return if (component.isEmpty()) message else "[$component] $message"
        }

    }

}
