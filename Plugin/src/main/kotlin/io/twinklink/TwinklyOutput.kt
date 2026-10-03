package io.twinklink

import heronarts.lx.LX
import heronarts.lx.output.IndexBuffer
import heronarts.lx.output.LXBufferOutput

class TwinklyOutput(lx: LX, private val device: TwinklyDevice, indices: IntArray, byteOrder: ByteOrder) :
    LXBufferOutput(lx, IndexBuffer(indices, byteOrder)) {
    private val buffer = ByteArray(indexBuffer.numChannels)

    override fun getDataBuffer(): ByteArray = buffer

    override fun getDataBufferOffset(): Int = 0

    override fun onSend(colors: IntArray?, glut: GammaTable?, brightness: Double) {
        updateDataBuffer(colors, glut, brightness)
        device.sendFrame(buffer)
    }
}
