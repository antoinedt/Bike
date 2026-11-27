package com.medialibrary.manager.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** A newer build is published; waiting for you to say Update or Later. */
    data class Available(val version: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    /** Android needs "Install unknown apps" allowed for OmniPlay first. */
    data class NeedsPermission(val version: String) : UpdateState
    /** The system's install screen is up (or about to be). */
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from this repo's GitHub releases: every Android CI build is published as
 * `mlm-build-<N>` with the APK attached, and the app's versionCode is N, so "newer" is just a
 * higher N. Android never lets an app replace itself silently, so the last step is the system's
 * own "Update this app?" screen (one tap). The APK is signed with the same committed key every
 * build, so it installs over the current app and keeps everything.
 */
object AppUpdater {
    private const val RELEASES_URL = "https://api.github.com/repos/antoinedt/Bike/releases?per_page=100"
    private val TAG = Regex("^mlm-build-(\\d+)$")
    private const val ASSET = "media-library-manager-debug.apk"
    private const val ACTION_INSTALL_STATUS = "com.medialibrary.manager.UPDATE_INSTALL_STATUS"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var checkedAtLaunch = false
    private var latest: Release? = null
    private var downloaded: File? = null

    private data class Release(val build: Int, val url: String, val size: Long) {
        val version get() = "0.2.$build"
    }

    fun currentBuild(context: Context): Long = runCatching {
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    }.getOrDefault(0L)

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** Once per app start. */
    fun checkAtLaunch(context: Context) {
        if (checkedAtLaunch) return
        checkedAtLaunch = true
        check(context)
    }

    fun check(context: Context) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        val app = context.applicationContext
        scope.launch {
            _state.value = UpdateState.Checking
            _state.value = runCatching {
                val newest = fetchLatest()
                latest = newest
                if (newest == null || newest.build <= currentBuild(app)) UpdateState.UpToDate
                else UpdateState.Available(newest.version)
            }.getOrElse { UpdateState.Failed("Couldn't check for updates: ${it.message}") }
        }
    }

    /** "Later": hide the prompt until the next check. */
    fun dismiss() {
        val s = _state.value
        if (s is UpdateState.Available || s is UpdateState.NeedsPermission) _state.value = UpdateState.Idle
    }

    /** Downloads the newest APK (if not done already) and opens the system install screen. */
    fun update(context: Context) {
        val release = latest ?: return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val file = downloaded?.takeIf { it.exists() && it.length() == release.size }
                    ?: download(app, release).also { downloaded = it }
                install(app, release, file)
            }.onFailure { _state.value = UpdateState.Failed("Update failed: ${it.message}") }
        }
    }

    /** Opens Android's "Install unknown apps" switch for OmniPlay. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun fetchLatest(): Release? {
        val request = Request.Builder().url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OmniPlay")
            .build()
        val body = http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) error("GitHub answered ${res.code}")
            res.body?.string() ?: error("empty answer")
        }
        var best: Release? = null
        for (element in json.parseToJsonElement(body).jsonArray) {
            val r = element.jsonObject
            val build = TAG.find(r["tag_name"]?.jsonPrimitive?.content ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (r["draft"]?.jsonPrimitive?.boolean == true) continue
            val asset = r["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content == ASSET } ?: continue
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: continue
            val size = asset["size"]?.jsonPrimitive?.long ?: 0L
            if (best == null || build > best.build) best = Release(build, url, size)
        }
        return best
    }

    private fun download(context: Context, release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "omniplay-${release.version}.apk")
        _state.value = UpdateState.Downloading(release.version, 0f)
        val request = Request.Builder().url(release.url).header("User-Agent", "OmniPlay").build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) error("download answered ${res.code}")
            val body = res.body ?: error("empty download")
            val total = if (release.size > 0) release.size else body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        val progress = if (total > 0) received.toFloat() / total else 0f
                        if (progress - lastShown >= 0.02f) {
                            lastShown = progress
                            _state.value = UpdateState.Downloading(release.version, progress)
                        }
                    }
                }
            }
        }
        if (release.size > 0 && file.length() != release.size) error("the download was incomplete")
        return file
    }

    private suspend fun install(context: Context, release: Release, apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release.version)
            return
        }
        _state.value = UpdateState.Installing(release.version)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("omniplay.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            withContext(Dispatchers.Main) { listenForResult(context) }
            val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private var receiverRegistered = false

    /** The installer reports back here: show its confirmation screen, or why it failed. */
    private fun listenForResult(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { ctx.startActivity(it) } }
                    }
                    PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        // Aborted = you cancelled the install screen: ask again next launch.
                        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                            UpdateState.Idle
                        } else {
                            UpdateState.Failed("Install failed${message?.let { ": $it" } ?: ""}")
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
}
