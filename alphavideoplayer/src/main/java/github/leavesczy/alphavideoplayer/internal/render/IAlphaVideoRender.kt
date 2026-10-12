package github.leavesczy.alphavideoplayer.internal.render

import android.graphics.SurfaceTexture
import android.view.Surface
import github.leavesczy.alphavideoplayer.AlphaVideo
import github.leavesczy.alphavideoplayer.internal.widget.AlphaVideoBaseGLTextureView

internal interface IAlphaVideoRender :
    AlphaVideoBaseGLTextureView.Renderer,
    SurfaceTexture.OnFrameAvailableListener {

    interface SurfaceListener {

        fun onSurfacePrepared(surface: Surface)

        fun onSurfaceDestroyed()

        fun onRenderSetupFailed(errorInfo: String)

    }

    fun setSurfaceListener(surfaceListener: SurfaceListener)

    fun setScaleType(scaleType: AlphaVideo.ScaleType)

    fun setLayout(layout: AlphaVideo.Layout)

    fun measureInternal(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Float,
        videoHeight: Float
    )

    fun onFirstFrame()

    /** Stops sampling immediately (safe off the GL thread). */
    fun clearCanDraw()

    fun onCompletion()

}
