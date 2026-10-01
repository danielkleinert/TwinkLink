package io.twinklink

import heronarts.lx.LX
import heronarts.lx.LXPlugin

class TwinkLink : LXPlugin {
    override fun initialize(lx: LX?) {
    }

    override fun dispose() {
        disposeOutputs()
    }

    companion object {
        private val outputs = mutableListOf<TwinklyOutput>()

        /**
         * Chromatik disposes fixtures when they are removed or a project is closed, but not on quit, and drops plugin instances without disposing them when it
         * reloads packages, so outputs restore their devices from a JVM shutdown hook as well.
         */
        private val shutdownHook by lazy {
            Runtime.getRuntime().addShutdownHook(Thread(::disposeOutputs, "TwinkLink shutdown"))
        }

        private fun disposeOutputs() {
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

        @JvmStatic
        fun registerOutput(output: TwinklyOutput) {
            shutdownHook
            synchronized(outputs) { outputs.add(output) }
        }

        @JvmStatic
        fun unregisterOutput(output: TwinklyOutput) {
            synchronized(outputs) { outputs.remove(output) }
        }
    }
}
