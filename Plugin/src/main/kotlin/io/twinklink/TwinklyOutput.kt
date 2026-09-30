package io.twinklink

import heronarts.lx.LX
import heronarts.lx.output.IndexBuffer
import heronarts.lx.output.LXBufferOutput
import heronarts.lx.parameter.LXParameter
import heronarts.lx.parameter.LXParameterListener
import heronarts.lx.structure.LXFixture
import io.twinklink.TwinkLink.Companion.registerOutput
import io.twinklink.TwinkLink.Companion.unregisterOutput
import kotlinx.coroutines.*

class TwinklyOutput(
    lx: LX?,
    private val parentFixture: LXFixture,
    indices: IntArray,
    byteOrder: ByteOrder,
    ipAddress: String,
    protocolVersion: Int = 3
) : LXBufferOutput(lx, IndexBuffer(indices, byteOrder)) {
    private val api: TwinklyAPI = TwinklyAPI(ipAddress, protocolVersion)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var originalMode: String? = null
    private var originalBrightness: Int = 50

    private val enabledListener: LXParameterListener
    private val buffer: ByteArray = ByteArray(indexBuffer.numChannels)

    init {
        if (parentFixture.enabled.isOn) start()
        enabledListener = LXParameterListener { p: LXParameter ->
            if (parentFixture.enabled.isOn) start() else stop()
        }
        parentFixture.enabled.addListener(enabledListener)
        registerOutput(this)
    }


    override fun getDataBuffer(): ByteArray = buffer

    override fun getDataBufferOffset(): Int = 0

    override fun onSend(colors: IntArray?, glut: GammaTable?, brightness: Double) {
        updateDataBuffer(colors, glut, brightness)
        api.sendRealtimeFrame(buffer)
    }

    private fun start() {
        scope.launch {
            try {
                api.authenticate()
                if (originalMode == null) {
                    originalMode = api.getMode()
                    originalBrightness = api.getBrightness()
                }
                api.setMode("rt")
                api.setBrightness(100)
            } catch (e: Exception) {
                LX.error(e, "Error starting Twinkly output")
            }
        }
    }

    private fun stop() {
        scope.launch {
            try {
                api.setBrightness(originalBrightness)
                api.setMode(originalMode ?: "off")
            } catch (e: Exception) {
                LX.error(e, "Error stopping Twinkly output")
            }
        }
    }

    override fun dispose() {
        unregisterOutput(this)
        parentFixture.enabled.removeListener(enabledListener)
        runBlocking {
            api.setBrightness(originalBrightness)
            api.setMode(originalMode ?: "off")
        }
        scope.cancel()
        api.dispose()
        super.dispose()
    }
}
