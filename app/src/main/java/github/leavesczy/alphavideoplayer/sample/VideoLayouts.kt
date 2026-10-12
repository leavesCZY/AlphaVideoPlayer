package github.leavesczy.alphavideoplayer.sample

import github.leavesczy.alphavideoplayer.AlphaVideo

internal object VideoLayouts {

    fun from(name: String): AlphaVideo.Layout {
        val key = name.lowercase()
        return when {
            key.startsWith(prefix = "alpha_left") -> AlphaVideo.Layout.AlphaLeft
            key.startsWith(prefix = "alpha_right") -> AlphaVideo.Layout.AlphaRight
            key.startsWith(prefix = "alpha_top") -> AlphaVideo.Layout.AlphaTop
            key.startsWith(prefix = "alpha_bottom") -> AlphaVideo.Layout.AlphaBottom
            else -> error(message = "Unknown video layout prefix: $name")
        }
    }

}
