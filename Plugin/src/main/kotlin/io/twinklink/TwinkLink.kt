package io.twinklink

import heronarts.lx.LX
import heronarts.lx.LXPlugin

class TwinkLink : LXPlugin {
    override fun initialize(lx: LX?) {
    }

    override fun dispose() {
        disposeDevices()
    }

    companion object {
        private val devices = mutableListOf<TwinklyDevice>()

        /**
         * Chromatik disposes fixtures when they are removed or a project is closed, but not on quit, and drops plugin instances without disposing them when it
         * reloads packages, so devices are restored from a JVM shutdown hook as well.
         */
        private val shutdownHook by lazy {
            Runtime.getRuntime().addShutdownHook(Thread(::disposeDevices, "TwinkLink shutdown"))
        }

        private fun disposeDevices() {
            val devicesCopy = synchronized(devices) {
                devices.toList().also { devices.clear() }
            }

            for (device in devicesCopy) {
                try {
                    device.dispose()
                } catch (e: Exception) {
                    LX.error(e, "TwinkLink: Error disposing device")
                }
            }
        }

        fun registerDevice(device: TwinklyDevice) {
            shutdownHook
            synchronized(devices) { devices.add(device) }
        }

        fun unregisterDevice(device: TwinklyDevice) {
            synchronized(devices) { devices.remove(device) }
        }
    }
}
