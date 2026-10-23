package com.bike.trainer.ui.ride

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.route.OsmTiles
import com.bike.trainer.route.Route
import com.bike.trainer.route.StreetViewManifest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * The "Stylized" scenery: a low-poly 3D model of the real place — terrain relief
 * from DEM tiles, the ground painted with OpenStreetMap land use / water / streets,
 * extruded OSM buildings and trees where the map has woods and parks (see
 * TerrainTiles / OsmTiles / "Stylize"), with the route as the road you ride. When
 * the route has prefetched Street View frames, those real photos are projected onto
 * that geometry from where they were taken (blending the frame behind and ahead),
 * so the scene looks like the real street while moving in true 3D. Rendered
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
    /** Prefetched Street View frames to project onto the 3D scenery (null = none). */
    photos: StreetViewManifest? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val renderer = remember(route, photos) {
        val frames = photos?.let { m ->
            PhotoFrames(context.applicationContext, route, m).takeIf { it.frames.isNotEmpty() }
        }
        StylizedRenderer(context.applicationContext, route, frames)
    }
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

    Box(modifier) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
        // Required attribution for the OpenStreetMap / OpenMapTiles data.
        if (route.id != null) {
            Text(
                OsmTiles.ATTRIBUTION,
                fontSize = 8.sp,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

private class StylizedRenderer(
    context: Context,
    private val route: Route,
    private val photos: PhotoFrames?,
) : GLSurfaceView.Renderer {
    private val field = HeightField(context, route, allowNetwork = true)
    private val osm = OsmSource(context, route, allowNetwork = true)
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
    private val buffers = IntArray(MESHES)
    private val counts = IntArray(MESHES)
    private var groundTex = 0
    private var hasGround = false
    private var shownBitmap: android.graphics.Bitmap? = null
    private var uploaded = false
    private var program = 0
    private var skyProgram = 0
    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val mvp = FloatArray(16)

    // Photo projection state (GL thread).
    private val photoTex = HashMap<Int, Int>()   // frame index -> texture
    private val freeTex = ArrayList<Int>()
    private val matA = FloatArray(16)
    private val matB = FloatArray(16)
    private val dome = FloatList(64 * 6 * FloatList.STRIDE)

    fun update(dist: Double, speedMps: Double, chase: Boolean) {
        sampleDist = dist
        sampleSpeed = speedMps
        sampleAt = SystemClock.elapsedRealtime()
        this.chase = chase
    }

    fun release() {
        released = true
        builder.shutdownNow()
        photos?.release()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        skyProgram = link(SKY_VERTEX, SKY_FRAGMENT)
        GLES20.glGenBuffers(MESHES, buffers, 0)
        val t = IntArray(1 + PHOTO_TEXTURES)
        GLES20.glGenTextures(t.size, t, 0)
        groundTex = t[0]
        photoTex.clear()
        freeTex.clear()
        for (i in 1 until t.size) freeTex.add(t[i])
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
        // With photos, sit at the Street View camera height so the images line up.
        val eyeHeight = if (photos != null) PhotoFrames.CAMERA_HEIGHT else 1.9
        val wantEye = (ground ?: eyeY.takeIf { !it.isNaN() } ?: 0.0) + eyeHeight + 4.5 * camChase
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
        GLES20.glUniform3f(
            GLES20.glGetUniformLocation(program, "uTexRect"),
            w.originX.toFloat(), w.originY.toFloat(), w.size.toFloat(),
        )
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundTex)

        val photo = bindPhotos(w)
        // Uploading a photo rebinds unit 0: put the ground texture back.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundTex)
        setPhoto(photo, dome = false)

        if (photo) {
            // Far ring around the camera: catches the photo's sky and distant skyline.
            setPhoto(true, dome = true)
            drawDome(p.x, eyeY, p.y)
            setPhoto(true, dome = false)
        }
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uUseTex"), if (hasGround) 1f else 0f)
        drawMesh(0) // terrain (painted with the real land use)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uUseTex"), 0f)
        // The photos already show the real trees and lane markings.
        if (!photo) drawMesh(1) // trees
        drawMesh(4) // buildings
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-1f, -2f)
        drawMesh(2) // road
        if (!photo) {
            GLES20.glPolygonOffset(-2f, -4f)
            drawMesh(3) // markings
        }
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    /**
     * Pick the Street View frames behind / ahead of the rider, make sure their
     * images are on the GPU, and set the projector uniforms. False = no photo now.
     */
    private fun bindPhotos(w: WorldMesh): Boolean {
        val ph = photos ?: return false
        val (ia, ib) = ph.around(renderDist)
        // Decode ahead so frames are ready before we reach them.
        val first = ia ?: ib ?: return false
        for (k in first..first + 4) ph.request(k)
        ph.trim(first - 2, first + 8)

        fun ready(i: Int?, mat: FloatArray): Int? {
            if (i == null) return null
            val f = ph.frames[i]
            val g = w.heightAt(f.x, f.y) ?: return null
            val tex = photoTex[i] ?: run {
                val bmp = ph.bitmap(i) ?: return null
                if (bmp.isRecycled) return null
                val t = freeTex.removeLastOrNull() ?: evictPhoto(keep = setOfNotNull(ia, ib)) ?: return null
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                photoTex[i] = t
                t
            }
            ph.matrix(i, g, mat)
            return tex
        }

        val ta = ready(ia, matA)
        val tb = ready(ib, matB)
        if (ta == null && tb == null) return false
        val blend = if (ta != null && tb != null) {
            val da = ph.frames[ia!!].distance
            val db = ph.frames[ib!!].distance
            val t = ((renderDist - da) / (db - da).coerceAtLeast(0.01)).coerceIn(0.0, 1.0)
            (t * t * (3 - 2 * t)).toFloat()
        } else if (tb != null) 1f else 0f

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ta ?: tb!!)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tb ?: ta!!)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPhotoA"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPhotoB"), 2)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatA"), 1, false, if (ta != null) matA else matB, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMatB"), 1, false, if (tb != null) matB else matA, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uBlend"), blend)
        return true
    }

    /** Free the texture of a frame we've ridden past. */
    private fun evictPhoto(keep: Set<Int>): Int? {
        val victim = photoTex.keys.filter { it !in keep }.minOrNull() ?: return null
        return photoTex.remove(victim)
    }

    private fun setPhoto(on: Boolean, dome: Boolean) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPhotoOn"), if (on) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDome"), if (dome) 1f else 0f)
    }

    /** A tall ring 1.8 km around the camera, rebuilt each frame (a few hundred vertices). */
    private fun drawDome(cx: Double, cy: Double, cz: Double) {
        dome.clear()
        val r = 1800.0
        val lo = cy - 400.0
        val hi = cy + 1500.0
        val segs = 48
        for (s in 0 until segs) {
            val a0 = 2 * Math.PI * s / segs
            val a1 = 2 * Math.PI * (s + 1) / segs
            val x0 = cx + kotlin.math.cos(a0) * r; val y0 = cz + kotlin.math.sin(a0) * r
            val x1 = cx + kotlin.math.cos(a1) * r; val y1 = cz + kotlin.math.sin(a1) * r
            dome.vertex(x0, lo, -y0, HORIZON[0], HORIZON[1], HORIZON[2])
            dome.vertex(x1, lo, -y1, HORIZON[0], HORIZON[1], HORIZON[2])
            dome.vertex(x1, hi, -y1, HORIZON[0], HORIZON[1], HORIZON[2])
            dome.vertex(x0, lo, -y0, HORIZON[0], HORIZON[1], HORIZON[2])
            dome.vertex(x1, hi, -y1, HORIZON[0], HORIZON[1], HORIZON[2])
            dome.vertex(x0, hi, -y0, HORIZON[0], HORIZON[1], HORIZON[2])
        }
        val a = dome.toArray()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[DOME])
        val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fb, GLES20.GL_STREAM_DRAW)
        counts[DOME] = a.size / FloatList.STRIDE
        // Behind everything: never occludes real geometry.
        GLES20.glDepthMask(false)
        drawMesh(DOME)
        GLES20.glDepthMask(true)
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
                    val mesh = WorldBuilder.build(field, osm, route, (rider + 1100.0).coerceAtMost(route.totalDistance), rider)
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
        val arrays = arrayOf(w.terrain, w.trees, w.road, w.markings, w.buildings)
        for (i in arrays.indices) {
            val a = arrays[i]
            counts[i] = a.size / FloatList.STRIDE
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[i])
            if (a.isEmpty()) continue
            val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            fb.put(a).position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, a.size * 4, fb, GLES20.GL_STATIC_DRAW)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Ground texture (land use / water / streets), mipmapped for distance.
        val bmp = w.ground
        hasGround = bmp != null && !bmp.isRecycled
        if (hasGround) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, groundTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp!!, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        // The previous window's bitmap is no longer needed; keep the current one in
        // case the GL context is lost and it has to be uploaded again.
        if (shownBitmap !== bmp) shownBitmap?.recycle()
        shownBitmap = bmp
        uploaded = true
    }

    private fun drawMesh(i: Int) {
        if (counts[i] == 0) return
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aCol = GLES20.glGetAttribLocation(program, "aCol")
        val aShade = GLES20.glGetAttribLocation(program, "aShade")
        val stride = FloatList.STRIDE * 4
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[i])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, stride, 12)
        if (aShade >= 0) {
            GLES20.glEnableVertexAttribArray(aShade)
            GLES20.glVertexAttribPointer(aShade, 1, GLES20.GL_FLOAT, false, stride, 24)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, counts[i])
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
        if (aShade >= 0) GLES20.glDisableVertexAttribArray(aShade)
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
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aCol)
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
        private const val MESHES = 6
        private const val DOME = 5
        private const val PHOTO_TEXTURES = 4
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
            uniform vec3 uTexRect;
            attribute vec3 aPos;
            attribute vec3 aCol;
            uniform mat4 uMatA;
            uniform mat4 uMatB;
            attribute float aShade;
            varying vec3 vCol;
            varying float vDist;
            varying float vShade;
            varying vec2 vUv;
            varying vec4 vPA;
            varying vec4 vPB;
            void main() {
                vCol = aCol;
                vShade = aShade;
                vPA = uMatA * vec4(aPos, 1.0);
                vPB = uMatB * vec4(aPos, 1.0);
                vDist = distance(aPos, uEye);
                vUv = vec2((aPos.x - uTexRect.x) / uTexRect.z, (-aPos.z - uTexRect.y) / uTexRect.z);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """
        private const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec3 uFog;
            uniform vec2 uFogRange;
            uniform sampler2D uTex;
            uniform float uUseTex;
            uniform sampler2D uPhotoA;
            uniform sampler2D uPhotoB;
            uniform float uPhotoOn;
            uniform float uDome;
            uniform float uBlend;
            varying vec3 vCol;
            varying float vDist;
            varying float vShade;
            varying vec2 vUv;
            varying vec4 vPA;
            varying vec4 vPB;

            // Colour of a Street View frame projected onto this point; alpha = coverage
            // (0 outside the photo, soft towards its edges).
            vec4 projected(sampler2D s, vec4 c) {
                if (c.w <= 0.05) return vec4(0.0);
                vec2 uv = c.xy / c.w * 0.5 + 0.5;
                vec2 e = min(uv, 1.0 - uv);
                float w = clamp(min(e.x, e.y) / 0.04, 0.0, 1.0);
                if (w <= 0.0) return vec4(0.0);
                return vec4(texture2D(s, vec2(uv.x, 1.0 - uv.y)).rgb, w);
            }

            void main() {
                vec3 c = vCol;
                if (uUseTex > 0.5) {
                    // Premultiplied texel: real land use where mapped, terrain colour elsewhere.
                    vec4 t = texture2D(uTex, vUv);
                    c = vCol * (1.0 - t.a) + t.rgb * vShade;
                }
                float f = clamp((vDist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
                vec3 base = mix(c, uFog, f);
                if (uPhotoOn > 0.5) {
                    vec4 a = projected(uPhotoA, vPA);
                    vec4 b = projected(uPhotoB, vPB);
                    float wa = a.a * (1.0 - uBlend);
                    float wb = b.a * uBlend;
                    // Where only one frame sees this point, it takes over.
                    if (b.a <= 0.0) wa = a.a;
                    if (a.a <= 0.0) wb = b.a;
                    float sum = wa + wb;
                    if (sum > 0.001) {
                        vec3 photo = (a.rgb * wa + b.rgb * wb) / sum;
                        gl_FragColor = vec4(mix(base, photo, max(a.a, b.a)), 1.0);
                        return;
                    }
                    if (uDome > 0.5) discard;
                }
                gl_FragColor = vec4(base, 1.0);
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
