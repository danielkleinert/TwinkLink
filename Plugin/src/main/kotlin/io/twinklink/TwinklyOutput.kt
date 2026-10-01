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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

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
    /** Serializes start and restore, which each span several device requests. */
    private val deviceLock = Mutex()

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
            deviceLock.withLock {
                try {
                    api.authenticate()
                    if (originalMode == null) {
                        // A device still in "rt" was left there by an earlier session that didn't restore it
                        originalMode = api.getMode().takeUnless { it == "rt" } ?: "movie"
                        originalBrightness = api.getBrightness()
                    }
                    api.setMode("rt")
                    api.setBrightness(100)
                } catch (e: Exception) {
                    LX.error(e, "Error starting Twinkly output")
                }
            }
        }
    }

    private fun stop() {
        scope.launch { restore() }
    }

    /** Waits for a start in flight, so the original state has been read before it is restored. */
    private suspend fun restore() {
        deviceLock.withLock {
            val mode = originalMode ?: return
            try {
                api.setBrightness(originalBrightness)
                api.setMode(mode)
            } catch (e: Exception) {
                LX.error(e, "Error restoring Twinkly mode")
            }
        }
    }

    override fun dispose() {
        unregisterOutput(this)
        parentFixture.enabled.removeListener(enabledListener)
        // Bounded, as it may run on quit, where an unreachable device must not hang the shutdown
        runBlocking { withTimeoutOrNull(RESTORE_TIMEOUT) { restore() } }
        scope.cancel()
        api.dispose()
        super.dispose()
    }

    companion object {
        private val RESTORE_TIMEOUT = 5.seconds
    }
}
