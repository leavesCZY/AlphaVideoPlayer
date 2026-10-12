package github.leavesczy.alphavideoplayer

import android.content.Context
import github.leavesczy.alphavideoplayer.internal.player.SystemMediaPlayer

/** [AlphaVideoPlayer] with [android.media.MediaPlayer] (file / asset via `openFd`). */
class AlphaVideoSystemPlayer(context: Context) : AlphaVideoPlayer(
    context = context,
    mediaPlayer = SystemMediaPlayer(context = context)
)
