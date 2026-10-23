package com.bike.trainer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.BikeNavHost
import com.bike.trainer.ui.theme.BikeTheme
import com.bike.trainer.ui.world.WorldHost
import kotlinx.coroutines.launch
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotHost
import org.godotengine.godot.plugin.GodotPlugin

/**
 * Single activity: the Compose UI on top, and underneath it (hidden until a "3D
 * World" ride) the embedded Godot engine that renders the cel-shaded world.
 */
class MainActivity : FragmentActivity(), GodotHost {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = FrameLayout(this)
        val world = FrameLayout(this).apply {
            id = View.generateViewId()
            visibility = View.GONE
        }
        root.addView(world, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ComposeView(this).apply {
            setContent {
                BikeTheme {
                    BikeNavHost()
                }
            }
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        WorldHost.attach(this, world)
        handleStravaRedirect(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        WorldHost.detach(this)
        // The Godot engine can't be restarted within a process: when the app is
        // closed after a 3D ride, end the process so the next launch starts clean.
        if (isFinishing && WorldHost.started) android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Back always goes to the app's own back handling (ride "Leave?" dialog,
     * navigation). Godot's view would otherwise swallow it when it has focus.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && WorldHost.started) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- GodotHost ----
    override fun getActivity() = this

    override fun getGodot(): Godot = Godot.getInstance(this)

    override fun getHostPlugins(engine: Godot): Set<GodotPlugin> = setOf(WorldHost.plugin(engine))

    /** The scene never asks to quit; ignore so the app keeps running. */
    override fun onGodotForceQuit(instance: Godot) {}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStravaRedirect(intent)
    }

    /** Completes the OAuth flow when Strava redirects to bike://strava-auth?code=... */
    private fun handleStravaRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "bike" || data.host != "strava-auth") return

        val error = data.getQueryParameter("error")
        if (error != null) {
            Toast.makeText(this, "Strava authorization denied", Toast.LENGTH_LONG).show()
            return
        }
        val code = data.getQueryParameter("code") ?: return
        lifecycleScope.launch {
            val ok = ServiceLocator.stravaRepository.exchangeAuthorizationCode(code)
            Toast.makeText(
                this@MainActivity,
                if (ok) "Connected to Strava" else "Could not connect to Strava",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
