package io.twinklink

import heronarts.lx.LX
import heronarts.lx.LXPlugin

class TwinkLink : LXPlugin {
    override fun initialize(lx: LX?) {
    }

    override fun dispose() {
        val outputsCopy = synchronized(outputs) {
            outputs.toList().also { outputs.clear() }
        }

        for (output in outputsCopy) {
            try {
                output.dispose()
            } catch (e: Exception) {
                LX.error(e, "TwinklyPlugin: Error disposing output")
            }
        }
    }

    companion object {
        private val outputs = mutableListOf<TwinklyOutput>()

        @JvmStatic
        fun registerOutput(output: TwinklyOutput) {
            synchronized(outputs) { outputs.add(output) }
        }

        @JvmStatic
        fun unregisterOutput(output: TwinklyOutput) {
            synchronized(outputs) { outputs.remove(output) }
        }
    }
}
