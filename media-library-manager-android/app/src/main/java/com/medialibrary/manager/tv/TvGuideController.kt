package com.medialibrary.manager.tv

import android.content.Context
import com.medialibrary.manager.cast.CastLiveStream
import com.medialibrary.manager.cast.CastTarget
import com.medialibrary.manager.di.ServiceLocator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class TvSignInState(
    val busy: Boolean = false,
    val error: String? = null,
    /** Shown while waiting for you to approve it on your phone. */
    val deviceCode: DeviceCode? = null
)

data class TvGuideState(
    val channels: List<TvChannel> = emptyList(),
    val programs: Map<Int, List<TvProgram>> = emptyMap(),
    /** 0 = today, -1 = yesterday, 1 = tomorrow… */
    val dayOffset: Int = 0,
    val dayStart: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val email: String? = null
)

/**
 * The TV tab: Fizz sign-in (asked for only when you open the tab; the login is saved like in the
 * Fizz app) and the "My plan" guide. Lives as long as the app so switching tabs doesn't reload it.
 */
class TvGuideController(context: Context) {
    // An error that slips through must never crash the app: show it instead.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        android.util.Log.e("TvGuide", "TV tab error", e)
        _guide.update { it.copy(loading = false, error = friendly(e as? Exception ?: Exception(e))) }
    })
    private val store = PrefsCredentialStore(context.applicationContext)
    private val recentPrefs = context.applicationContext.getSharedPreferences("tv_recent", Context.MODE_PRIVATE)
    val client = FizzTvClient(store, store::deviceId)

    /** null until checked. */
    private val _signedIn = MutableStateFlow<Boolean?>(null)
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    private val _signIn = MutableStateFlow(TvSignInState())
    val signIn: StateFlow<TvSignInState> = _signIn.asStateFlow()

    private val _guide = MutableStateFlow(TvGuideState())
    val guide: StateFlow<TvGuideState> = _guide.asStateFlow()

    private var loadJob: Job? = null
    private var pollJob: Job? = null

    data class Playing(val stream: TvStream, val channelName: String)
    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing.asStateFlow()
    private val _tuning = MutableStateFlow<Int?>(null)
    /** The channel id currently being tuned (stream request in flight), for a spinner. */
    val tuning: StateFlow<Int?> = _tuning.asStateFlow()

    // ---- Recently watched channels (Home's "On now" row) ----

    /** A recently watched channel and what's on it right now (null while unknown or off air). */
    data class NowOn(val channel: TvChannel, val program: TvProgram?)
    private val _nowOn = MutableStateFlow<List<NowOn>>(emptyList())
    val nowOn: StateFlow<List<NowOn>> = _nowOn.asStateFlow()

    private fun recentIds(): List<Int> =
        recentPrefs.getString("ids", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    /** Remembers [channelId] as the most recent (watched here or cast); keeps the last 3. */
    private fun recordRecent(channelId: Int) {
        val ids = (listOf(channelId) + recentIds().filter { it != channelId }).take(3)
        recentPrefs.edit().putString("ids", ids.joinToString(",")).apply()
        refreshNowOn()
    }

    /** Looks up what's on now on the last 3 watched channels. Quietly does nothing when signed out. */
    fun refreshNowOn() {
        val ids = recentIds()
        if (ids.isEmpty() || !client.isSignedIn()) {
            _nowOn.value = emptyList()
            return
        }
        scope.launch {
            runCatching {
                val channels = client.myPlanChannels().associateBy { it.id }
                val now = System.currentTimeMillis()
                val programs = client.guide(ids, now - 4 * 3_600_000L, now + 3_600_000L)
                _nowOn.value = ids.mapNotNull { id ->
                    channels[id]?.let { ch -> NowOn(ch, programs.firstOrNull { it.channelId == id && now >= it.start && now < it.end }) }
                }
            }
        }
    }

    /** Called when the TV tab opens. */
    fun open() {
        val signed = client.isSignedIn()
        _signedIn.value = signed
        if (signed && _guide.value.channels.isEmpty() && loadJob?.isActive != true) load(_guide.value.dayOffset)
    }

    fun selectDay(offset: Int) = load(offset)

    fun reload() = load(_guide.value.dayOffset, refreshChannels = true)

    private fun dayStart(offset: Int): Long =
        LocalDate.now().plusDays(offset.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun load(offset: Int, refreshChannels: Boolean = false) {
        loadJob?.cancel()
        val start = dayStart(offset)
        _guide.update {
            it.copy(dayOffset = offset, dayStart = start, loading = true, error = null, programs = if (it.dayStart == start) it.programs else emptyMap())
        }
        loadJob = scope.launch {
            try {
                val channels = client.myPlanChannels(forceRefresh = refreshChannels)
                _guide.update { it.copy(channels = channels, email = client.credentials()?.email) }
                // A few channels per request, a few requests at a time; rows fill in as they arrive.
                // A failed request leaves its rows empty and is reported; it never cancels the others.
                val limit = Semaphore(4)
                val failures = supervisorScope {
                    channels.chunked(10).map { chunk ->
                        async {
                            limit.withPermit {
                                try {
                                    val programs = client.guide(chunk.map { it.id }, start, start + DAY_MS)
                                    val byChannel = programs.groupBy { it.channelId }
                                    _guide.update { g ->
                                        if (g.dayStart != start) g else g.copy(programs = g.programs + chunk.associate { c -> c.id to (byChannel[c.id] ?: emptyList()) })
                                    }
                                    null
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    android.util.Log.w("TvGuide", "Guide request failed", e)
                                    e
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                failures.firstOrNull { it is SignInRequiredException }?.let { throw it }
                _guide.update { it.copy(loading = false, error = failures.firstOrNull()?.let { e -> friendly(e) }) }
            } catch (e: SignInRequiredException) {
                _guide.value = TvGuideState()
                _signIn.value = TvSignInState(error = e.message)
                _signedIn.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _guide.update { it.copy(loading = false, error = friendly(e)) }
            }
        }
    }

    /** Opens a channel live (guide-only tabs never call this). */
    fun watch(channelId: Int, channelName: String) {
        if (_tuning.value != null) return
        _tuning.value = channelId
        scope.launch {
            try {
                val stream = client.streamForChannel(channelId)
                _playing.value = Playing(stream, channelName)
                recordRecent(channelId)
            } catch (e: SignInRequiredException) {
                _signedIn.value = false
                _signIn.value = TvSignInState(error = e.message)
            } catch (e: Exception) {
                _guide.update { it.copy(error = friendly(e)) }
            } finally {
                _tuning.value = null
            }
        }
    }

    fun closePlayer() {
        _playing.value = null
    }

    // ---- Casting a channel to a Google TV ----

    private val castManager = ServiceLocator.castManager
    val castTarget: StateFlow<CastTarget?> = castManager.target
    val castState = castManager.state

    suspend fun discoverCastTargets(): List<CastTarget> = castManager.discover()
    fun selectCastTarget(target: CastTarget?) = castManager.selectTarget(target)
    fun stopCasting() = castManager.stopCasting()

    /** Tunes [channelId] and casts it to the selected Google TV, via Fizz's own Cast receiver. */
    fun castChannel(channelId: Int, channelName: String) {
        if (_tuning.value != null) return
        _tuning.value = channelId
        scope.launch {
            try {
                val stream = client.streamForChannel(channelId)
                val live = CastLiveStream(
                    url = stream.url,
                    // Fizz sends video/mp4 and lets its receiver pick the parser from media_format_id.
                    contentType = "video/mp4",
                    mediaFormatId = if (stream.isDash) 2 else 4,
                    drmToken = stream.drmToken,
                    drmLicenseUrl = stream.licenseUrl,
                    drmType = stream.drmType,
                    drmContentId = stream.drmContentId
                )
                castManager.castLive(live, channelName)
                recordRecent(channelId)
            } catch (e: SignInRequiredException) {
                _signedIn.value = false
                _signIn.value = TvSignInState(error = e.message)
            } catch (e: Exception) {
                _guide.update { it.copy(error = friendly(e)) }
            } finally {
                _tuning.value = null
            }
        }
    }

    // ---- Sign-in ----

    fun startBrowserSignIn(): BrowserSignIn = client.startBrowserSignIn()

    fun finishBrowserSignIn(redirectUrl: String, pending: BrowserSignIn) {
        _signIn.value = TvSignInState(busy = true)
        scope.launch {
            try {
                client.finishBrowserSignIn(redirectUrl, pending)
                signedInNow()
            } catch (e: Exception) {
                _signIn.value = TvSignInState(error = friendly(e))
            }
        }
    }

    fun startDeviceSignIn() {
        pollJob?.cancel()
        _signIn.value = TvSignInState(busy = true)
        pollJob = scope.launch {
            try {
                val code = client.startDeviceSignIn()
                _signIn.value = TvSignInState(deviceCode = code)
                while (isActive) {
                    delay(code.intervalSeconds * 1000L)
                    if (client.pollDeviceSignIn(code)) {
                        signedInNow()
                        break
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _signIn.value = TvSignInState(error = friendly(e))
            }
        }
    }

    fun cancelDeviceSignIn() {
        pollJob?.cancel()
        _signIn.value = TvSignInState()
    }

    fun showSignInError(message: String) {
        _signIn.value = TvSignInState(error = message)
    }

    private fun signedInNow() {
        _signIn.value = TvSignInState()
        _guide.value = TvGuideState()
        _signedIn.value = true
        load(0)
    }

    fun signOut() {
        loadJob?.cancel()
        pollJob?.cancel()
        client.signOut()
        _guide.value = TvGuideState()
        _signIn.value = TvSignInState()
        _signedIn.value = false
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Can't reach the TV service. Check your connection."
        else -> e.message ?: "Something went wrong."
    }

    companion object {
        const val DAY_MS = 24 * 3600_000L
    }
}

/** Saved in the app's private storage, like the Fizz app's own preferences. */
private class PrefsCredentialStore(context: Context) : TvCredentialStore {
    private val prefs = context.getSharedPreferences("fizz_tv", Context.MODE_PRIVATE)

    override fun load(): TvCredentials? {
        val refresh = prefs.getString("refresh_token", null) ?: return null
        return TvCredentials(
            refreshToken = refresh,
            sessionId = prefs.getString("session_id", null),
            email = prefs.getString("email", null),
            deviceId = deviceId()
        )
    }

    /** Made once and kept (also across sign-outs), so the service always sees the same device. */
    fun deviceId(): String = prefs.getString("device_id", null)
        ?: FizzTvClient.randomDeviceId().also { prefs.edit().putString("device_id", it).commit() }

    override fun save(credentials: TvCredentials?) {
        val edit = prefs.edit()
        if (credentials == null) {
            // The device id stays, so signing in again looks like the same device to the service.
            edit.remove("refresh_token").remove("session_id").remove("email")
        } else {
            edit.putString("refresh_token", credentials.refreshToken)
                .putString("session_id", credentials.sessionId)
                .putString("email", credentials.email)
                .putString("device_id", credentials.deviceId)
        }
        edit.commit()
    }
}
