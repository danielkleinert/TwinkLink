package io.twinklink

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import heronarts.lx.LX
import heronarts.lx.model.LXPoint
import heronarts.lx.output.LXBufferOutput.ByteOrder
import heronarts.lx.parameter.DiscreteParameter
import heronarts.lx.parameter.LXParameter
import heronarts.lx.parameter.StringParameter
import heronarts.lx.structure.LXFixture
import heronarts.lx.transform.LXMatrix

class TwinklyFixture(lx: LX) : LXFixture(lx, "Twinkly") {
    val ipAddress: StringParameter = StringParameter("IP")
        .setDescription("IP address of the Twinkly device")

    val protocolVersion: DiscreteParameter = DiscreteParameter("Protocol", 3, 1, 4)
        .setDescription("Twinkly UDP protocol version (1, 2, or 3)")

    val jsonCoords: StringParameter = StringParameter("Coords")
        .setDescription("JSON array of coordinates as [[x,y,z], ...]")

    val ledProfile: StringParameter = StringParameter("Profile", "RGB")
        .setDescription("LED Profile (RGB or RGBW)")

    private var loadedPoints = mutableListOf<LXPoint>()

    init {
        addOutputParameter("ipAddress", ipAddress)
        addOutputParameter("jsonCoords", jsonCoords)
        addOutputParameter("ledProfile", ledProfile)
        addOutputParameter("protocolVersion", protocolVersion)
    }

    override fun onParameterChanged(p: LXParameter?) {
        super.onParameterChanged(p)
        if (jsonCoords == p) parseJson()
    }

    private fun parseJson() {
        try {
            val coords = Gson().fromJson<List<List<Number>>>(
                jsonCoords.string,
                object : TypeToken<List<List<Number>>>() {}.type)
            this.loadedPoints = coords.map { coord ->
                LXPoint(
                    coord.getOrNull(0)?.toFloat() ?: 0f,
                    coord.getOrNull(1)?.toFloat() ?: 0f,
                    coord.getOrNull(2)?.toFloat() ?: 0f
                )
            }.toMutableList()
            regenerate()
        } catch (e: Exception) {
            LX.error(e, "Error parsing Twinkly coordinates - expected format: [[x,y,z], ...]")
        }
    }

    override fun computePointGeometry(transform: LXMatrix?, points: MutableList<LXPoint>) {
        points.forEachIndexed { i, p ->
            p.set(loadedPoints.getOrNull(i))
            p.multiply(transform)
        }
    }

    override fun size(): Int {
        return this.loadedPoints.size
    }

    override fun buildOutputs() {
        if (ipAddress.isDefault || loadedPoints.isEmpty()) return
        val indices = points.map { it.index }.toIntArray()
        val byteOrder = if (ledProfile.string == "RGBW") ByteOrder.WRGB else ByteOrder.RGB
        val output = TwinklyOutput(lx, this, indices, byteOrder, ipAddress.string, protocolVersion.valuei)
        addOutputDirect(output)
    }

}
