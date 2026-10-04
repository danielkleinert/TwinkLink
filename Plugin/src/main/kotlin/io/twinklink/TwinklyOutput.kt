package io.twinklink

import heronarts.lx.LX
import heronarts.lx.output.IndexBuffer
import heronarts.lx.output.LXBufferOutput

/** [recorder] is read on each frame, as recordings outlive output rebuilds. */
class TwinklyOutput(
    lx: LX, private val device: TwinklyDevice, indices: IntArray, byteOrder: ByteOrder, private val recorder: () -> MovieRecorder?
) : LXBufferOutput(lx, IndexBuffer(indices, byteOrder)) {
    private val buffer = ByteArray(indexBuffer.numChannels)

    override fun getDataBuffer(): ByteArray = buffer

    override fun getDataBufferOffset(): Int = 0

    /**
     * A recording gets the frame without Chromatik's master brightness, as movies play at the device's own brightness,
     * like those of the Twinkly app; fixture and output brightness stay. Encoded separately rather than scaled, as
     * brightness applies before gamma. The recorder copies the frame, so the buffer is then reused for the stream.
     */
    override fun onSend(colors: IntArray?, glut: GammaTable?, brightness: Double) {
        recorder()?.let {
            val master = lx.engine.output.brightness.value
            updateDataBuffer(colors, glut, if (master > 0) (brightness / master).coerceAtMost(1.0) else 1.0)
            it.offer(buffer)
        }
        updateDataBuffer(colors, glut, brightness)
        device.sendFrame(buffer)
    }
}
