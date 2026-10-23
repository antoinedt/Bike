package com.bike.trainer.ui.world

import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import org.godotengine.godot.Godot
import org.godotengine.godot.GodotFragment

/**
 * Owns the embedded Godot engine that renders the "3D World" scenery.
 *
 * The engine can only be started once per process, so it is created on the first
 * world ride and then kept for the rest of the session: between rides its view is
 * simply hidden (rendering stops with its surface). It sits behind the Compose UI,
 * sized to the ride screen's scene area, so the HUD and controls draw on top.
 */
object WorldHost {
    private var activity: FragmentActivity? = null
    private var container: FrameLayout? = null
    private var plugin: WorldPlugin? = null

    /** True once the engine has been started in this process. */
    var started = false
        private set

    fun attach(activity: FragmentActivity, container: FrameLayout) {
        this.activity = activity
        this.container = container
    }

    fun detach(activity: FragmentActivity) {
        if (this.activity === activity) {
            this.activity = null
            this.container = null
        }
    }

    /** The plugin handed to the engine at start-up (one per engine). */
    fun plugin(godot: Godot): WorldPlugin = plugin ?: WorldPlugin(godot).also { plugin = it }

    /** Show the world in [dir] (starting the engine on first use). */
    fun show(dir: String) {
        val box = container ?: return
        val act = activity ?: return
        if (!started) {
            started = true
            act.supportFragmentManager.beginTransaction()
                .replace(box.id, GodotFragment())
                .commitNowAllowingStateLoss()
        }
        box.visibility = View.VISIBLE
        plugin?.openWorld(dir)
    }

    /** Place the view exactly over the ride screen's scene area (window coordinates). */
    fun setBounds(bounds: Rect) {
        val box = container ?: return
        val lp = box.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == bounds.left && lp.topMargin == bounds.top &&
            lp.width == bounds.width() && lp.height == bounds.height()
        ) return
        lp.leftMargin = bounds.left
        lp.topMargin = bounds.top
        lp.width = bounds.width()
        lp.height = bounds.height()
        box.layoutParams = lp
    }

    fun update(distanceMeters: Double, speedMps: Double) = plugin?.update(distanceMeters, speedMps)

    fun setChase(on: Boolean) = plugin?.setChase(on)

    fun hide() {
        container?.visibility = View.GONE
    }

    /** The engine's GL surface, for in-ride photo capture. */
    fun renderView(): View? = if (started) activity?.let { Godot.getInstance(it).renderView?.view } else null
}
