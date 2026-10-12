package github.leavesczy.alphavideoplayer.internal.widget

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import github.leavesczy.alphavideoplayer.AlphaVideo
import java.lang.ref.WeakReference
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

/**
 * TextureView with a dedicated GL thread (GLSurfaceView-style loop).
 * Owns EGL; [Renderer.onSurfaceDestroyed] runs with the context current when possible so OES
 * textures / programs can be deleted safely.
 */
internal open class AlphaVideoBaseGLTextureView(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    companion object {

        const val RENDER_MODE_WHEN_DIRTY = 0

        const val RENDER_MODE_CONTINUOUSLY = 1

        private const val COMPONENT = "AlphaVideoBaseGLTextureView"

        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098

        private const val EGL_OPENGL_ES2_BIT = 4

        private val threadManager = GLThreadManager()

    }

    interface Renderer {

        fun onSurfaceCreated(gl: GL10, config: EGLConfig)

        fun onSurfaceChanged(gl: GL10, width: Int, height: Int)

        fun onDrawFrame(gl: GL10)

        fun onSurfaceDestroyed(gl: GL10?)
    }

    private val viewRef = WeakReference(this)

    private var glThread: GLThread? = null

    private var renderer: Renderer? = null

    private var detached = false

    private var savedRenderMode = RENDER_MODE_CONTINUOUSLY

    var preserveEGLContextOnPause: Boolean = false

    private var eglContextClientVersion = 0

    private var redSize = 8

    private var greenSize = 8

    private var blueSize = 8

    private var alphaSize = 8

    private var depthSize = 0

    private var stencilSize = 0

    init {
        surfaceTextureListener = this
    }

    fun setEGLContextClientVersion(version: Int) {
        checkNotInitialized()
        eglContextClientVersion = version
    }

    fun setEGLConfigChooser(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        depth: Int,
        stencil: Int
    ) {
        checkNotInitialized()
        redSize = red
        greenSize = green
        blueSize = blue
        alphaSize = alpha
        depthSize = depth
        stencilSize = stencil
    }

    fun setRenderer(renderer: Renderer) {
        checkNotInitialized()
        this.renderer = renderer
        val thread = GLThread(viewRef = viewRef)
        glThread = thread
        thread.renderMode = savedRenderMode
        thread.start()
    }

    var renderMode: Int
        get() = glThread?.renderMode ?: savedRenderMode
        set(value) {
            require(value = value in RENDER_MODE_WHEN_DIRTY..RENDER_MODE_CONTINUOUSLY) {
                "renderMode"
            }
            savedRenderMode = value
            glThread?.renderMode = value
        }

    fun requestRender() {
        glThread?.requestRender()
    }

    fun queueEvent(runnable: Runnable): Boolean {
        val thread = glThread ?: return false
        thread.queueEvent(runnable = runnable)
        return true
    }

    fun onPause() {
        glThread?.onPause()
    }

    fun onResume() {
        glThread?.onResume()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (detached && renderer != null) {
            val thread = GLThread(viewRef = viewRef)
            glThread = thread
            thread.renderMode = savedRenderMode
            thread.start()
        }
        detached = false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glThread?.requestExitAndWait()
        glThread = null
        detached = true
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val thread = glThread ?: return
        thread.surfaceCreated()
        thread.onWindowResize(w = width, h = height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glThread?.onWindowResize(w = width, h = height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        glThread?.surfaceDestroyed()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun checkNotInitialized() {
        check(value = glThread == null) { "setRenderer has already been called for this instance." }
    }

    private class EglHelper(private val viewRef: WeakReference<AlphaVideoBaseGLTextureView>) {

        private var egl: EGL10? = null

        private var eglDisplay: EGLDisplay? = null

        private var eglSurface: EGLSurface? = null

        var eglConfig: EGLConfig? = null
            private set

        private var eglContext: EGLContext? = null

        fun start() {
            val egl10 = EGLContext.getEGL() as EGL10
            egl = egl10
            val display = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            eglDisplay = display
            if (display === EGL10.EGL_NO_DISPLAY) {
                throw RuntimeException("eglGetDisplay failed")
            }
            val version = IntArray(size = 2)
            if (!egl10.eglInitialize(display, version)) {
                throw RuntimeException("eglInitialize failed")
            }
            val view = viewRef.get() ?: run {
                eglConfig = null
                eglContext = null
                return
            }
            val config = chooseConfig(egl10 = egl10, display = display, view = view)
            eglConfig = config
            val context = egl10.eglCreateContext(
                display,
                config,
                EGL10.EGL_NO_CONTEXT,
                if (view.eglContextClientVersion != 0) {
                    intArrayOf(
                        EGL_CONTEXT_CLIENT_VERSION,
                        view.eglContextClientVersion,
                        EGL10.EGL_NONE
                    )
                } else {
                    null
                }
            )
            if (context == null || context === EGL10.EGL_NO_CONTEXT) {
                eglContext = null
                throwEglException(function = "createContext", error = egl10.eglGetError())
            }
            eglContext = context
            eglSurface = null
        }

        fun createSurface(): Boolean {
            val egl10 = egl ?: error(message = "egl not initialized")
            val display = eglDisplay ?: error(message = "eglDisplay not initialized")
            val config = eglConfig ?: error(message = "eglConfig not initialized")
            destroySurface()
            val view = viewRef.get()
            val surface = if (view != null) {
                try {
                    egl10.eglCreateWindowSurface(display, config, view.surfaceTexture, null)
                } catch (e: IllegalArgumentException) {
                    AlphaVideo.Log.e(
                        component = COMPONENT,
                        message = "eglCreateWindowSurface",
                        throwable = e
                    )
                    null
                }
            } else {
                null
            }
            eglSurface = surface
            if (surface == null || surface === EGL10.EGL_NO_SURFACE) {
                if (egl10.eglGetError() == EGL10.EGL_BAD_NATIVE_WINDOW) {
                    AlphaVideo.Log.e(
                        component = COMPONENT,
                        message = "createWindowSurface returned EGL_BAD_NATIVE_WINDOW"
                    )
                }
                return false
            }
            if (!egl10.eglMakeCurrent(display, surface, surface, eglContext)) {
                logEglError(function = "eglMakeCurrent", error = egl10.eglGetError())
                return false
            }
            return true
        }

        fun createGL(): GL10 {
            return eglContext?.gl as? GL10
                ?: error(message = "EGL context is not initialized")
        }

        fun swap(): Int {
            val egl10 = egl ?: return EGL10.EGL_NOT_INITIALIZED
            return if (egl10.eglSwapBuffers(eglDisplay, eglSurface)) {
                EGL10.EGL_SUCCESS
            } else {
                egl10.eglGetError()
            }
        }

        fun destroySurface() {
            val surface = eglSurface
            if (surface != null && surface !== EGL10.EGL_NO_SURFACE) {
                egl?.eglMakeCurrent(
                    eglDisplay,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT
                )
                egl?.eglDestroySurface(eglDisplay, surface)
                eglSurface = null
            }
        }

        fun finish() {
            val context = eglContext
            if (context != null) {
                egl?.eglDestroyContext(eglDisplay, context)
                eglContext = null
            }
            val display = eglDisplay
            if (display != null) {
                egl?.eglTerminate(display)
                eglDisplay = null
            }
            egl = null
        }

        private fun chooseConfig(
            egl10: EGL10,
            display: EGLDisplay,
            view: AlphaVideoBaseGLTextureView
        ): EGLConfig {
            val spec = buildConfigSpec(view = view)
            val numConfig = IntArray(size = 1)
            if (!egl10.eglChooseConfig(display, spec, null, 0, numConfig)) {
                throw IllegalArgumentException("eglChooseConfig failed")
            }
            val count = numConfig[0]
            if (count <= 0) {
                throw IllegalArgumentException("No configs match configSpec")
            }
            val configs = arrayOfNulls<EGLConfig>(size = count)
            if (!egl10.eglChooseConfig(display, spec, configs, count, numConfig)) {
                throw IllegalArgumentException("eglChooseConfig#2 failed")
            }
            val value = IntArray(size = 1)
            fun attrib(config: EGLConfig, attribute: Int): Int {
                return if (egl10.eglGetConfigAttrib(display, config, attribute, value)) {
                    value[0]
                } else {
                    0
                }
            }
            for (config in configs) {
                if (config == null) continue
                if (attrib(config = config, attribute = EGL10.EGL_DEPTH_SIZE) < view.depthSize) {
                    continue
                }
                if (attrib(
                        config = config,
                        attribute = EGL10.EGL_STENCIL_SIZE
                    ) < view.stencilSize
                ) {
                    continue
                }
                if (attrib(config = config, attribute = EGL10.EGL_RED_SIZE) == view.redSize &&
                    attrib(config = config, attribute = EGL10.EGL_GREEN_SIZE) == view.greenSize &&
                    attrib(config = config, attribute = EGL10.EGL_BLUE_SIZE) == view.blueSize &&
                    attrib(config = config, attribute = EGL10.EGL_ALPHA_SIZE) == view.alphaSize
                ) {
                    return config
                }
            }
            throw IllegalArgumentException("No config chosen")
        }

        private fun buildConfigSpec(view: AlphaVideoBaseGLTextureView): IntArray {
            val base = intArrayOf(
                EGL10.EGL_RED_SIZE, view.redSize,
                EGL10.EGL_GREEN_SIZE, view.greenSize,
                EGL10.EGL_BLUE_SIZE, view.blueSize,
                EGL10.EGL_ALPHA_SIZE, view.alphaSize,
                EGL10.EGL_DEPTH_SIZE, view.depthSize,
                EGL10.EGL_STENCIL_SIZE, view.stencilSize,
                EGL10.EGL_NONE
            )
            if (view.eglContextClientVersion != 2) {
                return base
            }
            val result = IntArray(size = base.size + 2)
            System.arraycopy(base, 0, result, 0, base.size - 1)
            result[base.size - 1] = EGL10.EGL_RENDERABLE_TYPE
            result[base.size] = EGL_OPENGL_ES2_BIT
            result[base.size + 1] = EGL10.EGL_NONE
            return result
        }

        companion object {

            fun throwEglException(function: String, error: Int): Nothing {
                throw RuntimeException("$function failed: $error")
            }

            fun logEglError(function: String, error: Int) {
                AlphaVideo.Log.w(
                    component = COMPONENT,
                    message = "$function failed: $error"
                )
            }
        }
    }

    private class GLThread(private val viewRef: WeakReference<AlphaVideoBaseGLTextureView>) :
        Thread() {

        private var eglHelper: EglHelper? = null

        private val eventQueue = arrayListOf<Runnable>()

        private var shouldExit = false

        @Volatile
        var exited = false
            private set

        private var requestPaused = false

        private var paused = false

        private var hasSurface = false

        private var surfaceIsBad = false

        private var waitingForSurface = false

        private var haveEglContext = false

        private var haveEglSurface = false

        private var shouldReleaseEglContext = false

        private var width = 0

        private var height = 0

        private var sizeChanged = true

        private var renderModeValue = RENDER_MODE_CONTINUOUSLY

        private var requestRender = true

        private var renderComplete = false

        override fun run() {
            name = "GLThread-${System.identityHashCode(this)}"
            try {
                guardedRun()
            } catch (_: InterruptedException) {
            } finally {
                threadManager.threadExiting(thread = this)
            }
        }

        @Throws(InterruptedException::class)
        private fun guardedRun() {
            val helper = EglHelper(viewRef = viewRef)
            eglHelper = helper
            haveEglContext = false
            haveEglSurface = false
            var gl: GL10? = null
            var createEglContext = false
            var createEglSurface = false
            var createGlInterface = false
            var lostEglContext = false
            var sizeChangedFlag = false
            var wantRenderNotification = false
            var doRenderNotification = false
            var askedToReleaseEglContext = false
            var w = 0
            var h = 0
            var event: Runnable? = null
            var notifySurfaceDestroyed = false
            var notifyContextLost = false
            var exiting = false
            try {
                while (true) {
                    synchronized(lock = threadManager) {
                        while (true) {
                            if (shouldExit) {
                                exiting = true
                                break
                            }
                            if (eventQueue.isNotEmpty()) {
                                event = eventQueue.removeAt(index = 0)
                                break
                            }
                            var pausing = false
                            if (paused != requestPaused) {
                                pausing = requestPaused
                                paused = requestPaused
                                threadManager.notifyAllLocked()
                            }
                            if (shouldReleaseEglContext) {
                                stopEglSurfaceLocked(helper = helper)
                                stopEglContextLocked(helper = helper)
                                shouldReleaseEglContext = false
                                askedToReleaseEglContext = true
                            }
                            if (lostEglContext) {
                                // Keep TextureView surface; tear down OES / EGL and recreate next draw.
                                notifyContextLost = true
                                lostEglContext = false
                                break
                            }
                            if (pausing && haveEglSurface) {
                                stopEglSurfaceLocked(helper = helper)
                            }
                            if (pausing && haveEglContext) {
                                val view = viewRef.get()
                                val preserve = view?.preserveEGLContextOnPause == true
                                if (!preserve || threadManager.shouldReleaseEglContextWhenPausing()) {
                                    stopEglContextLocked(helper = helper)
                                }
                            }
                            if (pausing && threadManager.shouldTerminateEglWhenPausing()) {
                                helper.finish()
                            }
                            if (!hasSurface && !waitingForSurface) {
                                // Notify with EGL still current, then drop the EGL surface.
                                notifySurfaceDestroyed = true
                                surfaceIsBad = false
                                threadManager.notifyAllLocked()
                                break
                            }
                            if (hasSurface && waitingForSurface) {
                                waitingForSurface = false
                                threadManager.notifyAllLocked()
                            }
                            if (doRenderNotification) {
                                wantRenderNotification = false
                                doRenderNotification = false
                                renderComplete = true
                                threadManager.notifyAllLocked()
                            }
                            if (readyToDraw()) {
                                if (!haveEglContext) {
                                    if (askedToReleaseEglContext) {
                                        askedToReleaseEglContext = false
                                    } else if (threadManager.tryAcquireEglContextLocked(thread = this)) {
                                        try {
                                            helper.start()
                                        } catch (t: RuntimeException) {
                                            threadManager.releaseEglContextLocked(thread = this)
                                            throw t
                                        }
                                        haveEglContext = true
                                        createEglContext = true
                                        threadManager.notifyAllLocked()
                                    }
                                }
                                if (haveEglContext && !haveEglSurface) {
                                    haveEglSurface = true
                                    createEglSurface = true
                                    createGlInterface = true
                                    sizeChangedFlag = true
                                }
                                if (haveEglSurface) {
                                    if (sizeChanged) {
                                        sizeChangedFlag = true
                                        w = width
                                        h = height
                                        wantRenderNotification = true
                                        createEglSurface = true
                                        sizeChanged = false
                                    }
                                    requestRender = false
                                    threadManager.notifyAllLocked()
                                    break
                                }
                            }
                            threadManager.waitLocked()
                        }
                    }
                    if (exiting) {
                        viewRef.get()?.renderer?.onSurfaceDestroyed(gl = gl)
                        return
                    }
                    if (notifyContextLost) {
                        notifyContextLost = false
                        viewRef.get()?.renderer?.onSurfaceDestroyed(gl = gl)
                        synchronized(lock = threadManager) {
                            stopEglSurfaceLocked(helper = helper)
                            stopEglContextLocked(helper = helper)
                            threadManager.notifyAllLocked()
                        }
                        continue
                    }
                    if (notifySurfaceDestroyed) {
                        notifySurfaceDestroyed = false
                        viewRef.get()?.renderer?.onSurfaceDestroyed(gl)
                        synchronized(lock = threadManager) {
                            if (haveEglSurface) {
                                stopEglSurfaceLocked(helper = helper)
                            }
                            waitingForSurface = true
                            threadManager.notifyAllLocked()
                        }
                        continue
                    }
                    val pendingEvent = event
                    if (pendingEvent != null) {
                        pendingEvent.run()
                        event = null
                        continue
                    }
                    if (createEglSurface) {
                        if (!helper.createSurface()) {
                            synchronized(lock = threadManager) {
                                surfaceIsBad = true
                                threadManager.notifyAllLocked()
                            }
                            continue
                        }
                        createEglSurface = false
                    }
                    if (createGlInterface) {
                        gl = helper.createGL()
                        threadManager.logGlRendererOnce(gl = gl)
                        createGlInterface = false
                    }
                    val currentGl = gl
                    val config = helper.eglConfig
                    if (createEglContext && currentGl != null && config != null) {
                        viewRef.get()?.renderer?.onSurfaceCreated(
                            gl = currentGl,
                            config = config
                        )
                        createEglContext = false
                    }
                    if (sizeChangedFlag && currentGl != null) {
                        viewRef.get()?.renderer?.onSurfaceChanged(
                            gl = currentGl,
                            width = w,
                            height = h
                        )
                        sizeChangedFlag = false
                    }
                    if (currentGl != null) {
                        viewRef.get()?.renderer?.onDrawFrame(currentGl)
                    }
                    when (val swapError = helper.swap()) {
                        EGL10.EGL_SUCCESS -> Unit
                        EGL11.EGL_CONTEXT_LOST -> lostEglContext = true
                        else -> {
                            EglHelper.logEglError(function = "eglSwapBuffers", error = swapError)
                            synchronized(lock = threadManager) {
                                surfaceIsBad = true
                                threadManager.notifyAllLocked()
                            }
                        }
                    }
                    if (wantRenderNotification) {
                        doRenderNotification = true
                    }
                }
            } finally {
                synchronized(lock = threadManager) {
                    stopEglSurfaceLocked(helper = helper)
                    stopEglContextLocked(helper = helper)
                }
            }
        }

        var renderMode: Int
            get() = synchronized(lock = threadManager) { renderModeValue }
            set(value) {
                require(value = value in RENDER_MODE_WHEN_DIRTY..RENDER_MODE_CONTINUOUSLY) {
                    "renderMode"
                }
                synchronized(lock = threadManager) {
                    renderModeValue = value
                    threadManager.notifyAllLocked()
                }
            }

        fun requestRender() {
            synchronized(lock = threadManager) {
                requestRender = true
                threadManager.notifyAllLocked()
            }
        }

        fun queueEvent(runnable: Runnable) {
            synchronized(lock = threadManager) {
                eventQueue.add(element = runnable)
                threadManager.notifyAllLocked()
            }
        }

        fun surfaceCreated() {
            synchronized(lock = threadManager) {
                hasSurface = true
                threadManager.notifyAllLocked()
                while (waitingForSurface && !exited) {
                    waitInterruptible()
                }
            }
        }

        fun surfaceDestroyed() {
            synchronized(lock = threadManager) {
                hasSurface = false
                threadManager.notifyAllLocked()
                // Wait for renderer teardown (or exit) before TextureView releases SurfaceTexture.
                while (!waitingForSurface && !exited) {
                    waitInterruptible()
                }
            }
        }

        fun onWindowResize(w: Int, h: Int) {
            synchronized(lock = threadManager) {
                width = w
                height = h
                sizeChanged = true
                requestRender = true
                renderComplete = false
                threadManager.notifyAllLocked()
                while (!exited && !paused && !renderComplete && ableToDraw()) {
                    waitInterruptible()
                }
            }
        }

        fun onPause() {
            synchronized(lock = threadManager) {
                requestPaused = true
                threadManager.notifyAllLocked()
                while (!exited && !paused) {
                    waitInterruptible()
                }
            }
        }

        fun onResume() {
            synchronized(lock = threadManager) {
                requestPaused = false
                requestRender = true
                renderComplete = false
                threadManager.notifyAllLocked()
                while (!exited && paused && !renderComplete) {
                    waitInterruptible()
                }
            }
        }

        fun requestExitAndWait() {
            synchronized(lock = threadManager) {
                shouldExit = true
                threadManager.notifyAllLocked()
                while (!exited) {
                    waitInterruptible()
                }
            }
        }

        fun requestReleaseEglContextLocked() {
            shouldReleaseEglContext = true
            threadManager.notifyAllLocked()
        }

        fun markExited() {
            exited = true
        }

        private fun stopEglSurfaceLocked(helper: EglHelper) {
            if (haveEglSurface) {
                haveEglSurface = false
                helper.destroySurface()
            }
        }

        private fun stopEglContextLocked(helper: EglHelper) {
            if (haveEglContext) {
                helper.finish()
                haveEglContext = false
                threadManager.releaseEglContextLocked(thread = this)
            }
        }

        private fun ableToDraw(): Boolean {
            return haveEglContext && haveEglSurface && readyToDraw()
        }

        private fun readyToDraw(): Boolean {
            return !paused && hasSurface && !surfaceIsBad &&
                    width > 0 && height > 0 &&
                    (requestRender || renderModeValue == RENDER_MODE_CONTINUOUSLY)
        }

        private fun waitInterruptible() {
            try {
                threadManager.waitLocked()
            } catch (_: InterruptedException) {
                currentThread().interrupt()
            }
        }
    }

    private class GLThreadManager {

        private var eglOwner: GLThread? = null

        private var loggedRenderer = false

        @Synchronized
        fun tryAcquireEglContextLocked(thread: GLThread): Boolean {
            if (eglOwner === thread || eglOwner == null) {
                eglOwner = thread
                notifyAllLocked()
            }
            return true
        }

        @Synchronized
        fun releaseEglContextLocked(thread: GLThread) {
            if (eglOwner === thread) {
                eglOwner = null
            }
            notifyAllLocked()
        }

        @Synchronized
        fun threadExiting(thread: GLThread) {
            thread.markExited()
            if (eglOwner === thread) {
                eglOwner = null
            }
            notifyAllLocked()
        }

        fun shouldReleaseEglContextWhenPausing(): Boolean = false

        fun shouldTerminateEglWhenPausing(): Boolean = false

        @Synchronized
        fun logGlRendererOnce(gl: GL10) {
            if (loggedRenderer) {
                return
            }
            loggedRenderer = true
            AlphaVideo.Log.d(
                component = COMPONENT,
                message = "GL renderer=${gl.glGetString(GL10.GL_RENDERER)}"
            )
        }

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun waitLocked() {
            (this as Object).wait()
        }

        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        fun notifyAllLocked() {
            (this as Object).notifyAll()
        }
    }

}
