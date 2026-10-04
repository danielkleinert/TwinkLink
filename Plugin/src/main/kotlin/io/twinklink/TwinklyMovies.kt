package io.twinklink

import heronarts.lx.LX
import heronarts.lx.LXLoopTask
import heronarts.lx.parameter.BooleanParameter
import heronarts.lx.parameter.BoundedParameter
import heronarts.lx.parameter.DiscreteParameter
import heronarts.lx.parameter.LXParameter
import heronarts.lx.parameter.MutableParameter
import heronarts.lx.parameter.StringParameter
import heronarts.lx.parameter.TriggerParameter
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class Movie(val id: Int, val name: String, val uniqueId: String, val frames: Int, val fps: Int)

/** The device's movies with its storage, counted in frames of its LED count. */
class MovieList(val movies: List<Movie>, val availableFrames: Int, val maxCapacity: Int, val maxMovies: Int?) {
    val usedFrames: Int get() = maxCapacity - availableFrames
}

/** Storage the movie to record takes on top of [list]'s, including the device's overhead; [refusal] tells why it doesn't fit. */
class RecordingFit(val list: MovieList, val frames: Int, val refusal: String?)

/** [playingId] is the current movie while the device is in movie mode; [fps] is the device's frame rate, which movies can't exceed. */
class MovieState(val list: MovieList, val playingId: Int?, val fps: Int) {
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

    // Recording a movie from what the fixture sends

    val recordName = StringParameter("Name", "")
        .setDescription("Name of the recorded movie; empty names it after the time")

    // Limited by the device's storage, which the record section shows, rather than by these ranges: at 1 fps it holds
    // about an hour. Exponential, so dragging stays fine for short lengths.

    val recordDuration = BoundedParameter("Duration", 10.0, 1.0, 3600.0)
        .setExponent(3.0)
        .setUnits(LXParameter.Units.SECONDS)
        .setDescription("Length of the recorded movie; the storage bar shows whether it fits")

    val recordBlend = BoundedParameter("Loop Blend", 1.0, 0.0, 600.0)
        .setExponent(3.0)
        .setUnits(LXParameter.Units.SECONDS)
        .setDescription(
            "Records this much longer and fades it into the movie's start, so the movie loops seamlessly. " +
                "At most the movie's duration."
        )

    /** Up to the device's frame rate, which it follows while set to it; lower rates suit slow patterns and save storage. */
    val recordFps = DiscreteParameter("FPS", TwinklyDevice.DEFAULT_MOVIE_FPS, 1, TwinklyDevice.DEFAULT_MOVIE_FPS + 1)
        .setDescription("Frames per second of the recorded movie, up to the device's frame rate")

    val recording = BooleanParameter("Record", false)
        .setDescription(
            "Record what the fixture shows in real time and store it as a movie on the device. Enables the fixture. " +
                "Records without Chromatik's master brightness, as movies play at the device's brightness."
        )

    val recordProgress = BoundedParameter("Recorded", 0.0)

    /** Recording progress or result, shown below the record button. */
    val recordStatus = StringParameter("Record Status", "")

    /** The running recording, which the fixture's output feeds from the network thread. */
    @Volatile var recorder: MovieRecorder? = null
        private set

    private var recordTask: LXLoopTask? = null

    /** Incremented whenever [state] or [message] changes. */
    val changed = MutableParameter("Movies Changed")

    /** Incremented when an action failed, described by [failure]. */
    val failed = MutableParameter("Movies Failed")

    /** Read by the UI thread as well, which only sees whole states. */
    @Volatile var state: MovieState? = null
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

    init {
        // Switched by the UI, and back by the recording when it ends
        recording.addListener { onEngine(::syncRecording) }
    }

    /** Called on the engine thread when the layout switched devices. */
    fun onDeviceChanged() {
        if (recorder != null) endRecording("Recording cancelled, the device changed")
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

    /** Why the fixture's device has no movies to work with, or null. */
    private fun unavailableReason(): String? = when {
        fixture.device == null -> "No device in this layout"
        fixture.deviceInfo?.supportsMovies == false -> "Movies need firmware 2.5.6 or newer"
        else -> null
    }

    private fun syncRecording() {
        if (recording.isOn && recorder == null) startRecording()
        else if (!recording.isOn && recorder != null) endRecording("Recording cancelled")
    }

    private fun startRecording() {
        val failure = recordingRefusal()
        if (failure != null) {
            recording.setValue(false)
            recordStatus.setValue(failure)
            return
        }
        val fps = recordFps.valuei
        val frames = recordFrames()
        val blendFrames = (recordBlend.value * fps).roundToInt().coerceIn(0, frames)
        val name = recordName.string.trim().ifEmpty { "Chromatik " + LocalTime.now().format(TIME_FORMAT) }
        // Frames are only sent while the fixture is enabled, which playing a movie switched off
        fixture.enabled.setValue(true)
        val r = MovieRecorder(fps, frames, blendFrames)
        recorder = r
        recordStatus.setValue("Waiting for output")
        var lastCount = 0
        var stalledMs = 0.0
        val task = LXLoopTask { deltaMs ->
            val count = r.count
            when {
                r.isCancelled -> endRecording("Recording cancelled, the layout changed")
                // Uploads once a running request finished, which would otherwise ignore it
                r.isComplete -> if (!busy) upload(r, name)
                count != lastCount -> {
                    lastCount = count
                    stalledMs = 0.0
                    recordProgress.setValue(count.toDouble() / r.total)
                    recordStatus.setValue(
                        String.format(Locale.ROOT, "Recording %.1f of %.1f s at %d fps", count.toDouble() / fps, r.total.toDouble() / fps, fps)
                    )
                }
                else -> {
                    stalledMs += deltaMs
                    if (stalledMs > STALL_MS) recordStatus.setValue("Waiting for output, is Chromatik's output live?")
                }
            }
        }
        recordTask = task
        lx.engine.addLoopTask(task)
    }

    /** Why a movie can't be recorded now, checked against the listed storage, as the device can only tell once it is full. */
    private fun recordingRefusal(): String? {
        unavailableReason()?.let { return it }
        val fit = recordingFit() ?: return "Refresh the movies first"
        return fit.refusal
    }

    private fun recordFrames() = (recordDuration.value * recordFps.valuei).roundToInt().coerceAtLeast(1)

    /** How the movie to record fits the listed storage; null until the movies were read. Safe on any thread. */
    fun recordingFit(): RecordingFit? {
        val list = state?.list ?: return null
        val stored = recordFrames() + MOVIE_OVERHEAD
        val refusal = when {
            list.maxMovies?.let { list.movies.size >= it } == true -> "The device holds no more than ${list.maxMovies} movies"
            stored > list.availableFrames ->
                "Needs about $stored frames, the device has ${list.availableFrames.coerceAtLeast(0)} left"
            else -> null
        }
        return RecordingFit(list, stored, refusal)
    }

    private fun upload(r: MovieRecorder, name: String) {
        val info = fixture.deviceInfo ?: return endRecording("Recording cancelled, the layout has no device")
        endRecording("Uploading $name...")
        // Frames carry each LED in the order of the fixture's points, which is the device's LED order
        val leds = fixture.points.size
        val descriptor = info.ledProfile.lowercase(Locale.ROOT) + "_raw"
        request(onDone = { ok -> recordStatus.setValue(if (ok) "Stored $name" else "") }) {
            // Blended off the engine thread
            it.uploadMovie(name, descriptor, leds, r.fps, r.frames, r.takeMovie())
        }
    }

    /** Stops feeding the recorder; called on the engine thread. */
    private fun endRecording(status: String) {
        detachRecorder()
        recordProgress.setValue(0.0)
        recordStatus.setValue(status)
        recording.setValue(false)
    }

    /**
     * Runs [action] on the device, then reads the movies again, as the action changed them or failed midway.
     * [onDone] tells whether the action succeeded.
     */
    private fun request(onDone: (Boolean) -> Unit = {}, action: suspend (TwinklyDevice) -> Unit) {
        if (busy) return
        unavailableReason()?.let { return show(null, it) }
        val device = fixture.device ?: return
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
                onDone(result.isSuccess)
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
        state?.let { limitFps(it.fps) }
        this.message = message
        val list = state?.list
        if (list == null || list.maxCapacity <= 0) {
            storage.setValue(0.0)
            storage.setDescription(STORAGE_DESCRIPTION)
        } else {
            storage.setValue(list.usedFrames.toDouble() / list.maxCapacity)
            storage.setDescription(
                "Movie storage: ${list.usedFrames} of ${list.maxCapacity} frames used (including some overhead per movie), " +
                    "${list.movies.size} of ${list.maxMovies ?: "?"} movies"
            )
        }
        deleteLast.setDescription(
            lastMovie?.let { "Delete ${it.name} from the device" }
                ?: "Delete the last movie from the device"
        )
        changed.increment()
    }

    private fun limitFps(max: Int) {
        if (recordFps.maxValue == max) return
        val atMax = recordFps.valuei == recordFps.maxValue
        recordFps.setRange(1, max + 1)
        if (atMax || recordFps.valuei > max) recordFps.setValue(max.toDouble())
    }

    /** Runs [block] on the engine thread, unless disposed meanwhile. */
    private fun onEngine(block: () -> Unit) {
        lx.engine.addTask { if (scope.isActive) block() }
    }

    private fun detachRecorder() {
        recordTask?.let(lx.engine::removeLoopTask)
        recordTask = null
        recorder = null
    }

    fun dispose() {
        detachRecorder()
        scope.cancel()
    }

    companion object {
        private const val STORAGE_DESCRIPTION = "Movie storage on the device"
        /** Storage a movie takes beyond its frames: 30 frames took 34, 60 took 65, 156 took 160. */
        private const val MOVIE_OVERHEAD = 8
        /** How long no frame arrived before the recording tells it is waiting. */
        private const val STALL_MS = 1000.0
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
