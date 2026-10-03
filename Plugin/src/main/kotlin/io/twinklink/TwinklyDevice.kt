package io.twinklink

import heronarts.lx.LX
import io.twinklink.TwinkLink.Companion.registerDevice
import io.twinklink.TwinkLink.Companion.unregisterDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/**
 * Session with a Twinkly device: the realtime stream and the movies. The device accepts only one token at a time,
 * so all requests share this session, which the fixture keeps across output rebuilds.
 */
class TwinklyDevice(
    val ip: String,
    val protocolVersion: Int,
    /** Called after starting or stopping the stream changed the device's mode. */
    private val onModeChanged: () -> Unit
) {
    private val api = TwinklyAPI(ip, protocolVersion)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Serializes stream and movie changes, which each span several requests. */
    private val lock = Mutex()

    // Guarded by lock
    private var streaming = false
    private var originalMode = "movie"
    private var originalBrightness = 50

    init {
        registerDevice(this)
    }

    fun sendFrame(frame: ByteArray) = api.sendRealtimeFrame(frame)

    fun startStream() {
        scope.launch {
            val started = lock.withLock {
                if (streaming) return@withLock false
                try {
                    val mode = api.getMode()
                    // A device still in "rt" was left there by an earlier session that didn't restore it
                    if (mode != "rt") {
                        originalMode = mode
                        originalBrightness = api.getBrightness()
                    }
                    // Set first, so a start that fails midway is still restored
                    streaming = true
                    api.setMode("rt")
                    api.setBrightness(100)
                } catch (e: Exception) {
                    LX.error(e, "Error starting Twinkly output")
                }
                streaming
            }
            if (started) onModeChanged()
        }
    }

    fun stopStream() {
        scope.launch { if (restore()) onModeChanged() }
    }

    /** Ends the stream, if one is running, and returns whether it did. */
    private suspend fun restore(): Boolean = lock.withLock {
        if (!streaming) return@withLock false
        try {
            endStream(originalMode)
        } catch (e: Exception) {
            LX.error(e, "Error restoring Twinkly mode")
        }
        true
    }

    /** Called with the lock held. Still streaming if it fails, so a later restore tries again. */
    private suspend fun endStream(mode: String) {
        api.setBrightness(originalBrightness)
        setModeOrOff(mode)
        streaming = false
    }

    private suspend fun setModeOrOff(mode: String) {
        try {
            api.setMode(mode)
        } catch (e: TwinklyRefusedException) {
            if (e.code != CODE_NO_MOVIES) throw e
            api.setMode("off")
        }
    }

    /** Locked, so it never sees the temporary modes of other requests. */
    suspend fun readMovieState(): MovieState = lock.withLock {
        val playingId = if (api.getMode() == "movie") api.getCurrentMovieId() else null
        MovieState(api.getMovies(), playingId)
    }

    /** Ends a running stream itself, as the fixture's enabled state can only be switched on the engine thread. */
    suspend fun playMovie(id: Int) {
        lock.withLock {
            api.setCurrentMovie(id)
            if (streaming) endStream("movie") else api.setMode("movie")
        }
    }

    /** Switches the device off, unless streaming already replaced the movie. */
    suspend fun stopMovie() {
        lock.withLock {
            if (!streaming) api.setMode("off")
        }
    }

    /** Like the Twinkly app, pauses movie and playlist mode around the delete, which the device refuses while showing movies. */
    suspend fun deleteMovie(uniqueId: String) {
        lock.withLock {
            val mode = api.getMode()
            val pause = mode in MOVIE_MODES
            if (pause) api.setMode("off")
            try {
                api.deleteMovie(uniqueId)
            } finally {
                if (pause) setModeOrOff(mode)
            }
        }
    }

    /**
     * Restores the device before returning, so a new session for it can't overlap the restore.
     * Bounded, as an unreachable device must not hang the engine or the shutdown.
     */
    fun dispose() {
        unregisterDevice(this)
        runBlocking { withTimeoutOrNull(RESTORE_TIMEOUT) { restore() } }
        scope.cancel()
        api.dispose()
    }

    companion object {
        private val RESTORE_TIMEOUT = 5.seconds
        /** The device refuses movie mode once no movies are left. */
        private const val CODE_NO_MOVIES = 1104
        private val MOVIE_MODES = setOf("movie", "playlist")
    }
}
