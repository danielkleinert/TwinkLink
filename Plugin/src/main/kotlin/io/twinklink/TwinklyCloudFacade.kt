package io.twinklink

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import heronarts.lx.LX
import heronarts.lx.output.LXBufferOutput.ByteOrder
import heronarts.lx.transform.LXVector
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.GZIPInputStream

internal val gson = GsonBuilder().disableHtmlEscaping().create()

private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

/**
 * A single layout object from the Twinkly Cloud API. The constructor throws if the object
 * has no layout coordinates; devices without an IP are left out.
 */
class LayoutFacade(private val layoutObj: JsonObject) {
    constructor(json: String) : this(gson.fromJson(json, JsonObject::class.java))

    val json: String by lazy { gson.toJson(layoutObj) }

    val id: Long = layoutObj.get("id").asLong
    val name: String = layoutObj.get("name").asString

    private val layout: JsonObject = layoutObj.getAsJsonObject("layout")
    private val coords: String = layout.get("coords").asString

    val devices: List<DeviceFacade> = (layoutObj.opt("devices")?.asJsonArray ?: JsonArray()).mapNotNull {
        it.asJsonObject.opt("device")?.asJsonObject?.takeIf { d -> d.opt("ip") != null }?.let(::DeviceFacade)
    }

    /** The device that gets driven; further devices in the layout are ignored. */
    val device: DeviceFacade? get() = devices.firstOrNull()

    /**
     * Plain coordinates; LXPoints would carry their own index, which must not leak into the model.
     * Decoded lazily, as fetched layouts are only listed by name until one is chosen.
     */
    val points: List<LXVector> by lazy { parsePoints() }

    private class Coord(val x: Double, val y: Double, val z: Double)

    private fun parsePoints(): List<LXVector> {
        val aspectXY = layout.opt("aspectXY")?.asDouble ?: 1.0
        val aspectXZ = layout.opt("aspectXZ")?.asDouble ?: 1.0

        val decoded = Base64.getDecoder().decode(coords)
        val decompressed = GZIPInputStream(ByteArrayInputStream(decoded)).use {
            it.readBytes().toString(StandardCharsets.UTF_8)
        }

        // Normalized 0..1 coordinates scaled to 100 units, stretched by the aspect ratios, Z flipped
        val scale = 100.0
        return gson.fromJson(decompressed, Array<Coord>::class.java).map { pt ->
            LXVector(
                (pt.x * scale).toFloat(),
                (pt.y * scale / aspectXY * 2).toFloat(),
                (pt.z * scale / aspectXZ * -1).toFloat()
            )
        }
    }
}

class DeviceFacade(deviceObj: JsonObject) {
    val ip: String = deviceObj.get("ip").asString
    val ledProfile: String = deviceObj.opt("ledProfile")?.asString ?: "RGB"
    val byteOrder: ByteOrder = if (ledProfile == "RGBW") ByteOrder.WRGB else ByteOrder.RGB

    /** Firmware version as major, minor, patch; all 0 when the cloud doesn't report it. */
    private val firmware: List<Int> = (deviceObj.opt("firmware")?.asString ?: "")
        .split(".").map { it.toIntOrNull() ?: 0 }
        .let { (it + listOf(0, 0, 0)).take(3) }

    private val firmwareKnown = firmware[0] != 0

    private fun firmwareAtLeast(major: Int, minor: Int, patch: Int): Boolean =
        compareValuesBy(firmware, listOf(major, minor, patch), { it[0] }, { it[1] }, { it[2] }) >= 0

    /**
     * Realtime UDP protocol matching the firmware, following xled: Generation I devices
     * (firmware 1.x) use v1, Generation II before 2.4.14 uses v2, newer firmware uses v3.
     */
    val protocolVersion: Int = when {
        !firmwareKnown -> 3 // Unknown firmware, v3 is supported by all current devices
        !firmwareAtLeast(2, 0, 0) -> 1
        !firmwareAtLeast(2, 4, 14) -> 2
        else -> 3
    }

    /** Storing several movies needs firmware 2.5.6; unknown firmware is assumed to be current. */
    val supportsMovies: Boolean = !firmwareKnown || firmwareAtLeast(2, 5, 6)
}

/** Parses the /v3/objects response, skipping objects that are not usable layouts. */
fun parseLayouts(response: JsonObject): List<LayoutFacade> =
    (response.opt("objects")?.asJsonArray ?: JsonArray()).mapNotNull {
        try {
            LayoutFacade(it.asJsonObject)
        } catch (e: Exception) {
            LX.error(e, "Skipping Twinkly Cloud object without a usable layout")
            null
        }
    }
