package github.leavesczy.alphavideoplayer

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Host for the player's GL render view.
 * [setPlayer] only attaches or detaches rendering (does not play, pause, or [AlphaVideoPlayer.release]).
 */
class AlphaVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val container: ViewGroup

    private var player: AlphaVideoPlayer? = null

    init {
        LayoutInflater.from(context).inflate(
            R.layout.layout_alphavideoplayer_view,
            this
        )
        container = findViewById(R.id.fl_alphavideoplayer_container)
    }

    fun setPlayer(player: AlphaVideoPlayer?) {
        if (this.player === player) {
            return
        }
        this.player?.detachRenderView(parentView = container)
        this.player = player
        player?.attachRenderView(parentView = container)
    }

    fun getPlayer(): AlphaVideoPlayer? {
        return player
    }

}
