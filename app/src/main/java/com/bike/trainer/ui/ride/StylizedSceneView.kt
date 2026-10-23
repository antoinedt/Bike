package com.bike.trainer.ui.ride

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.route.Route
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * The "Stylized" scenery: a low-poly 3D world generated from the route itself —
 * real terrain relief from cached DEM tiles (see TerrainTiles / "Stylize"), the
 * road draped over it, roadside forest and a soft sky with distance fog. Rendered
 * continuously and extrapolated between engine ticks so motion stays smooth.
 *
 * @param chase true = camera behind and above the rider, false = rider's-eye view.
 */
@Composable
fun StylizedSceneView(
    route: Route,
    distanceMeters: Double,
    speedKmh: Double,
    chase: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val renderer = remember(route) { StylizedRenderer(context.applicationContext, route) }
    val view = remember(renderer) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    SideEffect { renderer.update(distanceMeters, speedKmh / 3.6, chase) }

    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        // Expose this GL view so captures can PixelCopy its surface.
        ServiceLocator.sceneView = view
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
            renderer.release()
            if (ServiceLocator.sceneView === view) ServiceLocator.sceneView = null
        }
    }

    AndroidView(modifier = modifier, factory = { view })
}

private class StylizedRenderer(context: Context, private val route: Route) : GLSurfaceView.Renderer {
    private val field = HeightField(context, route, allowNetwork = true)
    private val builder = Executors.newSingleThreadExecutor { r -> Thread(r, "stylized-world").apply { isDaemon = true } }
    private val building = AtomicBoolean(false)
    private val pending = AtomicReference<WorldMesh?>(null)
    @Volatile private var released = false

    // Latest engine sample (written from the UI thread).
    @Volatile private var sampleDist = 0.0
    @Volatile private var sampleSpeed = 0.0
    @Volatile private var sampleAt = 0L
    @Volatile private var chase = false

    // GL-thread state.
    private var world: WorldMesh? = null
    @Volatile private var requestedFor = Double.NaN
    private var renderDist = Double.NaN
    private var lastFrame = 0L
    private var eyeY = Double.NaN
    private var camChase = 0.0
    private val buffers = IntArray(4)
    private val counts = IntArray(4)
    private var uploaded = false
    private var program = 0
    private var skyProgram = 0
    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val mvp = FloatArray(16)

    fun update(dist: Double, speedMps: Double, chase: Boolean) {
        sampleDist = dist
        sampleSpeed = speedMps
        sampleAt = SystemClock.elapsedRealtime()
        this.chase = chase
    }

    fun release() {
        released = true
        builder.shutdownNow()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        skyProgram = link(SKY_VERTEX, SKY_FRAGMENT)
        GLES20.glGenBuffers(4, buffers, 0)
        uploaded = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 0.0 else (now - lastFrame) / 1000.0
        lastFrame = now

        // Extrapolate between the engine's ~4 Hz ticks, then ease toward it.
        val ahead = ((now - sampleAt) / 1000.0).coerceIn(0.0, 0.6)
        val target = sampleDist + sampleSpeed * ahead
        renderDist = if (renderDist.isNaN() || abs(target - renderDist) > 150.0) target
        else renderDist + (target - renderDist) * (dt * 6.0).coerceAtMost(1.0)
        camChase += ((if (chase) 1.0 else 0.0) - camChase) * (dt * 3.0).coerceAtMost(1.0)

        maybeRebuild()
        pending.getAndSet(null)?.let { world = it; uploaded = false }
        val w = world
        if (w != null && !uploaded) upload(w)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glClearColor(HORIZON[0], HORIZON[1], HORIZON[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        drawSky()
        if (w == null) return

        // ---- Camera: on the rendered road, rider's-eye or chase ----
        val back = 12.0 * camChase
        val p = route.pointAt(renderDist - back)
        val ground = w.heightAt(p.x, p.y)
        val wantEye = (ground ?: eyeY.takeIf { !it.isNaN() } ?: 0.0) + 0.3 + 1.6 + 4.5 * camChase
        eyeY = if (eyeY.isNaN()) wantEye else eyeY + (wantEye - eyeY) * (dt * 5.0).coerceAtMost(1.0)
        // Look at the average of a few points ahead: steadies the heading on noisy GPX.
        var lx = 0.0; var ly = 0.0; var lh = 0.0; var nLook = 0
        for (k in 1..4) {
            val q = route.pointAt(renderDist + 10.0 + k * 8.0)
            lx += q.x; ly += q.y
            lh += (w.heightAt(q.x, q.y) ?: eyeY) + 1.2
            nLook++
        }
        lx /= nLook; ly /= nLook; lh /= nLook
        // At the very end of the route the look-ahead collapses onto the eye.
        if (abs(lx - p.x) + abs(ly - p.y) < 1.0) {
            lx = p.x + kotlin.math.sin(p.heading) * 30.0
            ly = p.y + kotlin.math.cos(p.heading) * 30.0
        }
        Matrix.perspectiveM(proj, 0, 58f, width.toFloat() / height, 0.8f, 6000f)
        Matrix.setLookAtM(
            viewM, 0,
            p.x.toFloat(), eyeY.toFloat(), (-p.y).toFloat(),
            lx.toFloat(), lh.toFloat(), (-ly).toFloat(),
            0f, 1f, 0f,
        )
        Matrix.multiplyMM(mvp, 0, proj, 0, viewM, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uEye"), p.x.toFloat(), eyeY.toFloat(), (-p.y).toFloat())
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uFog"), HORIZON[0], HORIZON[1], HORIZON[2])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uFogRange"), 900f, 2900f)

        drawMesh(0) // terrain
        drawMesh(1) // trees
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-1f, -2f)
        drawMesh(2) // road
        GLES20.glPolygonOffset(-2f, -4f)
        drawMesh(3) // markings
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    private fun maybeRebuild() {
        if (released || building.get() || renderDist.isNaN()) return
        val stale = requestedFor.isNaN() ||
            renderDist - requestedFor > 700.0 ||
            renderDist < requestedFor - 300.0
        if (!stale) return
        val rider = renderDist
        requestedFor = rider
        building.set(true)
        runCatching {
            builder.execute {
                try {
                    val mesh = WorldBuilder.build(field, route, (rider + 1100.0).coerceAtMost(route.totalDistance), rider)
                    if (!released) pending.set(mesh)
                } catch (_: Throwable) {
                    // Leave the previous window up; next frame will retry.
                    requestedFor = Double.NaN
                } finally {
                    building.set(false)
                }
            }
        }.onFailure { building.set(false) }
    }

    private fun upload(w: WorldMesh) {
        val arrays = arrayOf(w.terrain, w.trees, w.road, w.markings)
        for (i in 0..3) {
            val a = arrays[i]
            counts[i] = a.size / 6
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[i])
            if (a.isEmpty()) continue
            val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a).position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fb, GLES20.GL_STATIC_DRAW)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        uploaded = true
    }

    private fun drawMesh(i: Int) {
        if (counts[i] == 0) return
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aCol = GLES20.glGetAttribLocation(program, "aCol")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[i])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, counts[i])
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private val skyQuad = ByteBuffer.allocateDirect(SKY.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(SKY).position(0) }

    private fun drawSky() {
        GLES20.glUseProgram(skyProgram)
        val aPos = GLES20.glGetAttribLocation(skyProgram, "aPos")
        val aCol = GLES20.glGetAttribLocation(skyProgram, "aCol")
        skyQuad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 20, skyQuad)
        skyQuad.position(2)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 20, skyQuad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        return prog
    }

    companion object {
        private val HORIZON = floatArrayOf(0.80f, 0.88f, 0.94f)
        private val ZENITH = floatArrayOf(0.30f, 0.55f, 0.85f)

        // Full-screen gradient: zenith at the top fading to the horizon colour.
        private val SKY = floatArrayOf(
            -1f, -1f, HORIZON[0], HORIZON[1], HORIZON[2],
            1f, -1f, HORIZON[0], HORIZON[1], HORIZON[2],
            -1f, 1f, ZENITH[0], ZENITH[1], ZENITH[2],
            1f, 1f, ZENITH[0], ZENITH[1], ZENITH[2],
        )

        private const val VERTEX = """
            uniform mat4 uMvp;
            uniform vec3 uEye;
            attribute vec3 aPos;
            attribute vec3 aCol;
            varying vec3 vCol;
            varying float vDist;
            void main() {
                vCol = aCol;
                vDist = distance(aPos, uEye);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """
        private const val FRAGMENT = """
            precision mediump float;
            uniform vec3 uFog;
            uniform vec2 uFogRange;
            varying vec3 vCol;
            varying float vDist;
            void main() {
                float f = clamp((vDist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
                gl_FragColor = vec4(mix(vCol, uFog, f), 1.0);
            }
        """
        private const val SKY_VERTEX = """
            attribute vec2 aPos;
            attribute vec3 aCol;
            varying vec3 vCol;
            void main() {
                vCol = aCol;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """
        private const val SKY_FRAGMENT = """
            precision mediump float;
            varying vec3 vCol;
            void main() { gl_FragColor = vec4(vCol, 1.0); }
        """
    }
}
