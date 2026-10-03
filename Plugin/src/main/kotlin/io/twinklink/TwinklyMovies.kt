package io.twinklink

import heronarts.lx.LX
import heronarts.lx.parameter.BoundedParameter
import heronarts.lx.parameter.MutableParameter
import heronarts.lx.parameter.TriggerParameter
import kotlinx.coroutines.*

class Movie(val id: Int, val name: String, val uniqueId: String, val frames: Int, val fps: Int)

/** The device's movies with its storage, counted in frames of its LED count. */
class MovieList(val movies: List<Movie>, val availableFrames: Int, val maxCapacity: Int, val maxMovies: Int?)

/** [playingId] is the current movie while the device is in movie mode. */
class MovieState(val list: MovieList, val playingId: Int?) {
    fun isPlaying(movie: Movie) = movie.id == playingId
}

/** The movies stored on the fixture's device. State is changed on the engine thread, where [changed] notifies the UI. */
class TwinklyMovies(private val lx: LX, private val fixture: TwinklyFixture) {
    val refresh = TriggerParameter("Refresh") { refresh() }
        .setDescription("Read the movies stored on the device")

    /** The device stores movies one after another and, like the Twinkly app, only deletes the last. */
    val deleteLast = TriggerParameter("Delete Last") { lastMovie?.let(::delete) }

    /** Share of the device's movie storage in use. */
    val storage = BoundedParameter("Storage", 0.0)
        .setDescription(STORAGE_DESCRIPTION)

    /** Incremented whenever [state] or [message] changes. */
    val changed = MutableParameter("Movies Changed")

    /** Incremented when an action failed, described by [failure]. */
    val failed = MutableParameter("Movies Failed")

    var state: MovieState? = null
        private set

    val lastMovie: Movie? get() = state?.list?.movies?.lastOrNull()

    /** Progress or why the movies can't be listed, shown in place of the list. */
    var message = ""
        private set

    var failure = ""
        private set

    /** Whether a request is running; further actions are ignored meanwhile, without disabling the buttons, which would flash. */
    private var busy = false

    /** A refresh asked for while busy, done once the request finished. */
    private var refreshPending = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Called on the engine thread when the layout switched devices. */
    fun onDeviceChanged() {
        val wasLoaded = state != null
        show(null, "")
        // The list was looked at, so show the new device's movies right away
        if (wasLoaded) refresh()
    }

    /** Called by the device after the stream changed its mode; reads it again if the list is loaded. */
    fun onModeChanged() = onEngine { if (state != null) refresh() }

    // Actions run on the engine thread, which owns the state

    fun refresh() = onEngine {
        if (busy) refreshPending = true else request { }
    }

    fun play(movie: Movie) = onEngine {
        request { device ->
            device.playMovie(movie.id)
            // Stop sending frames, which the device ignores while playing the movie
            onEngine { fixture.enabled.setValue(false) }
        }
    }

    fun stop() = onEngine { request { it.stopMovie() } }

    fun delete(movie: Movie) = onEngine { request { it.deleteMovie(movie.uniqueId) } }

    /** Runs [action] on the device, then reads the movies again, as the action changed them or failed midway. */
    private fun request(action: suspend (TwinklyDevice) -> Unit) {
        if (busy) return
        val device = fixture.device ?: return show(null, "No device in this layout")
        if (fixture.deviceInfo?.supportsMovies == false) return show(null, "Movies need firmware 2.5.6 or newer")
        busy = true
        if (state == null) show(null, "Reading movies...")
        scope.launch {
            val result = runCatching { action(device) }
            // Read the state even after a failed action, which may have changed the device midway
            val read = runCatching { device.readMovieState() }
            onEngine {
                busy = false
                result.exceptionOrNull()?.let {
                    LX.error(it, "Twinkly movie request failed")
                    failure = failureText(it)
                    failed.increment()
                }
                read.onSuccess { show(it, "") }.onFailure {
                    LX.error(it, "Reading Twinkly movies failed")
                    show(null, failureText(it))
                }
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }

    private fun show(state: MovieState?, message: String) {
        this.state = state
        this.message = message
        val list = state?.list
        if (list == null || list.maxCapacity <= 0) {
            storage.setValue(0.0)
            storage.setDescription(STORAGE_DESCRIPTION)
        } else {
            val used = list.maxCapacity - list.availableFrames
            storage.setValue(used.toDouble() / list.maxCapacity)
            storage.setDescription(
                "Movie storage: $used of ${list.maxCapacity} frames used (including some overhead per movie), " +
                    "${list.movies.size} of ${list.maxMovies ?: "?"} movies"
            )
        }
        deleteLast.setDescription(
            lastMovie?.let { "Delete ${it.name} from the device" }
                ?: "Delete the last movie from the device"
        )
        changed.increment()
    }

    /** Runs [block] on the engine thread, unless disposed meanwhile. */
    private fun onEngine(block: () -> Unit) {
        lx.engine.addTask { if (scope.isActive) block() }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        private const val STORAGE_DESCRIPTION = "Movie storage on the device"
    }
}
