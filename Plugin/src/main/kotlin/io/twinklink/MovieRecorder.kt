package io.twinklink

import heronarts.lx.utils.LXUtils

/**
 * Records the frames a TwinklyOutput sends, sampled by time at [fps], as the engine runs faster than movies play.
 * Records [blendFrames] beyond the movie's [frames], which are blended into its start so the movie loops seamlessly.
 * [offer] may be called on the network thread while other threads read the progress or take the movie.
 */
class MovieRecorder(val fps: Int, val frames: Int, private val blendFrames: Int) {
    init {
        require(blendFrames in 0..frames) { "Blend longer than the movie" }
    }

    val total = frames + blendFrames

    private val frameNanos = 1_000_000_000L / fps

    // Guarded by this
    private val recorded = ArrayList<ByteArray>(total)
    private var frameSize = 0
    private var nextNanos = 0L
    private var cancelled = false

    val count: Int @Synchronized get() = recorded.size

    val isComplete: Boolean get() = count == total

    /** Set when the frame size changed, as the layout did. */
    val isCancelled: Boolean @Synchronized get() = cancelled

    /** Records [frame] as many times as sample times passed since the last, which keeps a stalling engine in real time. */
    @Synchronized
    fun offer(frame: ByteArray) {
        if (cancelled || recorded.size == total) return
        val now = System.nanoTime()
        if (recorded.isEmpty()) {
            frameSize = frame.size
            nextNanos = now
        } else if (frame.size != frameSize) {
            cancelled = true
            return
        }
        while (now >= nextNanos && recorded.size < total) {
            recorded.add(frame.copyOf())
            nextNanos += frameNanos
        }
    }

    /**
     * The movie's frames, one after another, releasing the recorded ones. The first [blendFrames] fade from the frames
     * recorded after the end into those at the start, so the last frame flows into the first:
     * movie[t] = lerp(recorded[frames + t], recorded[t], t / blendFrames).
     */
    @Synchronized
    fun takeMovie(): ByteArray {
        check(recorded.size == total) { "Recording is incomplete" }
        val movie = ByteArray(frames * frameSize)
        for (t in 0 until frames) {
            val frame = recorded[t]
            val offset = t * frameSize
            if (t < blendFrames) {
                val tail = recorded[frames + t]
                val amount = t.toFloat() / blendFrames
                for (i in 0 until frameSize) {
                    movie[offset + i] = LXUtils.lerpi(tail[i].toInt() and 0xFF, frame[i].toInt() and 0xFF, amount).toByte()
                }
            } else {
                frame.copyInto(movie, offset)
            }
        }
        recorded.clear()
        return movie
    }
}
