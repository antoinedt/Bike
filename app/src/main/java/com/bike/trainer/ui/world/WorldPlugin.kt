package com.bike.trainer.ui.world

import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

/**
 * Bridge between the app and the Godot world scene (res://main.gd, which looks
 * this up as the "VibeBike" singleton): opens a built world and streams the ride's
 * distance / speed and the camera mode to it.
 */
class WorldPlugin(godot: Godot) : GodotPlugin(godot) {

    @Volatile private var ready = false
    @Volatile private var pendingWorld: String? = null
    @Volatile private var chase = false

    override fun getPluginName() = "VibeBike"

    override fun getPluginSignals() = setOf(OPEN_WORLD, RIDE_UPDATE, SET_CHASE)

    /** Load the world in [dir] (now, or as soon as the scene is ready). */
    fun openWorld(dir: String) {
        pendingWorld = dir
        if (ready) emitSignal(OPEN_WORLD.name, dir)
    }

    fun update(distanceMeters: Double, speedMps: Double, cadenceRpm: Double) {
        if (ready) emitSignal(RIDE_UPDATE.name, distanceMeters, speedMps, cadenceRpm)
    }

    fun setChase(on: Boolean) {
        chase = on
        if (ready) emitSignal(SET_CHASE.name, on)
    }

    /** Called by the scene once it has connected to the signals. */
    @UsedByGodot
    fun worldReady() {
        ready = true
        pendingWorld?.let { emitSignal(OPEN_WORLD.name, it) }
        emitSignal(SET_CHASE.name, chase)
    }

    companion object {
        private val OPEN_WORLD = SignalInfo("open_world", String::class.java)
        private val RIDE_UPDATE = SignalInfo(
            "ride_update", java.lang.Double::class.java, java.lang.Double::class.java, java.lang.Double::class.java,
        )
        private val SET_CHASE = SignalInfo("set_chase", java.lang.Boolean::class.java)
    }
}
