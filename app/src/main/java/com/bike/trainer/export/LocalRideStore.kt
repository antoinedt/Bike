package com.bike.trainer.export

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves finished rides as TCX files in the app's external files directory
 * (`Android/data/<pkg>/files/rides/`). No permissions are needed there, and the
 * files persist so they can be re-uploaded or shared manually later.
 */
object LocalRideStore {

    fun ridesDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "rides")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Write [tcx] to a uniquely named file derived from the route name + time. */
    fun save(context: Context, routeName: String, tcx: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = routeName.replace(Regex("[^A-Za-z0-9-_]+"), "_").trim('_').take(40)
        val file = File(ridesDir(context), "${safeName.ifBlank { "ride" }}_$stamp.tcx")
        file.writeText(tcx)
        return file
    }
}
