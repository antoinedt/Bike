package com.medialibrary.manager.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.medialibrary.manager.MainActivity
import com.medialibrary.manager.R
import com.medialibrary.manager.di.ServiceLocator
import com.medialibrary.manager.torrent.TorrentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the app alive (with an ongoing notification) while downloads or copies are running. It
 * doesn't do the work itself — TransferManager does — it just shows progress and stops once
 * nothing is left.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ONGOING_ID, Notifications.ongoing(this, progressText()), type)
        if (updater?.isActive != true) {
            updater = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!busy()) {
                        stopSelf()
                        break
                    }
                    NotificationManagerCompat.from(this@TransferService).runCatching {
                        notify(Notifications.ONGOING_ID, Notifications.ongoing(this@TransferService, progressText()))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun busy() = ServiceLocator.transferManager.isBusy() || ServiceLocator.castManager.isActive()

    private fun progressText(): String {
        val manager = ServiceLocator.transferManager
        val active = manager.torrents.value.filter {
            it.state == TorrentState.DOWNLOADING || it.state == TorrentState.METADATA || it.state == TorrentState.CHECKING
        }
        val parts = mutableListOf<String>()
        if (active.isNotEmpty()) {
            val total = active.sumOf { it.totalBytes }
            val done = active.sumOf { it.doneBytes }
            val pct = if (total > 0) " · ${done * 100 / total}%" else ""
            val speed = active.sumOf { it.downloadRate }
            parts += "Downloading ${active.size}$pct · ${formatBytes(speed)}/s"
        }
        manager.deliveries.value.values.forEach { d ->
            val name = manager.torrents.value.find { it.id == d.torrentId }?.name ?: "download"
            parts += "Saving $name${if (d.totalBytes > 0) " · ${d.copiedBytes * 100 / d.totalBytes}%" else ""}"
        }
        manager.copies.value.find { it.state == CopyState.RUNNING }?.let { c ->
            parts += "Copying ${c.item.name}${if (c.totalBytes > 0) " · ${c.copiedBytes * 100 / c.totalBytes}%" else ""}"
        }
        ServiceLocator.castManager.state.value?.let { c ->
            if (ServiceLocator.castManager.isActive()) parts += "Casting ${c.itemName} to ${c.deviceName}"
        }
        return parts.joinToString("\n").ifEmpty { "Working…" }
    }

    companion object {
        /** Starts the service while downloads, copies or casting are running; stops it otherwise. */
        fun refresh(context: Context) {
            val busy = ServiceLocator.transferManager.isBusy() || ServiceLocator.castManager.isActive()
            if (busy) {
                runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
            } else {
                context.stopService(Intent(context, TransferService::class.java))
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}

object Notifications {
    const val ONGOING_ID = 41
    private const val CHANNEL_ONGOING = "transfers"
    private const val CHANNEL_DONE = "transfers_done"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Downloads and copies in progress", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("Media Library Manager")
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

    fun remote(context: Context, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(text.hashCode(), notification) }
    }

    fun downloadSaved(context: Context, name: String, where: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("Download finished")
            .setContentText("$name — saved to $where")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name — saved to $where"))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(name.hashCode(), notification) }
    }
}
