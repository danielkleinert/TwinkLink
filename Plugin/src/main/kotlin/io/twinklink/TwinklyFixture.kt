package io.twinklink

import com.google.gson.JsonObject
import heronarts.lx.LX
import heronarts.lx.model.LXPoint
import heronarts.lx.parameter.BooleanParameter
import heronarts.lx.parameter.DiscreteParameter
import heronarts.lx.parameter.LXParameter
import heronarts.lx.parameter.StringParameter
import heronarts.lx.parameter.TriggerParameter
import heronarts.lx.structure.LXFixture
import heronarts.lx.transform.LXMatrix
import kotlinx.coroutines.*

class TwinklyFixture(lx: LX) : LXFixture(lx, "TwinklyFixture") {
    /** The selected layout object from the Twinkly Cloud API, saved with the project. */
    val layoutJson = StringParameter("Layout JSON")
        .setDescription("Layout object from the Twinkly Cloud API")

    val email = StringParameter("Email")
        .setDescription("Twinkly Cloud account email")

    val savePassword = BooleanParameter("Save password", false)
        .setDescription("Store the password in plain text in the project file")

    /**
     * Deliberately not registered as a parameter, which would expose it via OSC query.
     * Written to the project file by save() only when savePassword is on.
     */
    val password = StringParameter("Password")
        .setDescription("Twinkly Cloud account password")

    // Transient UI state, not saved with the project

    val fetch = TriggerParameter("Load Layouts") { fetchLayouts() }
        .setDescription("Sign in to the Twinkly Cloud and load the layouts of your devices")

    val layoutSelect = DiscreteParameter("Layout", arrayOf(NO_LAYOUT))
        .setDescription("Layout that defines the LED positions and the device to drive")

    /** Shown below the load button: the last sign-in result, or a hint. */
    val accountStatus = StringParameter("Account Status", HINT_NO_LAYOUT)

    /** Summary of the selected layout's device, shown below the layout menu. */
    val layoutInfo = StringParameter("Layout Info", "")

    val movies = TwinklyMovies(lx, this)

    /** Session with the layout's device, kept across output rebuilds. */
    var device: TwinklyDevice? = null
        private set

    val deviceInfo: DeviceFacade? get() = layout?.device

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fetchedLayouts: List<LayoutFacade> = emptyList()
    private var layout: LayoutFacade? = null
    private var signInMessage: String? = null
    private var fetchJob: Job? = null

    /** Entries behind layoutSelect's options, null standing for NO_LAYOUT. */
    private var layoutOptions: List<LayoutFacade?> = listOf(null)

    /** setOptions may clamp the value and fire the listener with an index that is about to change. */
    private var updatingLayoutOptions = false

    init {
        addMetricsParameter("layoutJson", layoutJson)
        addParameter("email", email)
        addParameter("savePassword", savePassword)
        layoutSelect.addListener { if (!updatingLayoutOptions) selectLayout(layoutSelect.valuei) }
    }

    override fun onParameterChanged(p: LXParameter?) {
        // Parse before super, which regenerates the fixture from the parsed layout
        if (p == layoutJson) parseLayout()
        if (p == enabled) device?.let { if (enabled.isOn) it.startStream() else it.stopStream() }
        super.onParameterChanged(p)
    }

    private fun parseLayout() {
        layout = try {
            // selectLayout() already set the matching layout
            val parsed = layout?.takeIf { it.json == layoutJson.string }
                ?: layoutJson.string.takeIf { it.isNotEmpty() }?.let { LayoutFacade(it) }
            // Decode the points now, so a broken layout fails here and not while regenerating
            parsed?.also { it.points }
        } catch (e: Exception) {
            LX.error(e, "Error parsing Twinkly layout JSON")
            null
        }
        val l = layout
        val device = l?.device
        layoutInfo.setValue(when {
            l == null -> ""
            device == null -> "Layout has no device"
            else -> {
                val more = if (l.devices.size > 1) " (+${l.devices.size - 1} not driven)" else ""
                "${l.points.size} LEDs · ${device.ledProfile} · ${device.ip}$more"
            }
        })
        updateLayoutOptions()
        updateAccountStatus()
    }

    private fun updateAccountStatus() {
        accountStatus.setValue(signInMessage ?: if (layout != null) HINT_HAS_LAYOUT else HINT_NO_LAYOUT)
    }

    /** Lists the fetched layouts, preceded by the current one (or NO_LAYOUT) unless it was fetched. */
    private fun updateLayoutOptions() {
        val current = layout
        val currentFetched = fetchedLayouts.any { it.id == current?.id }
        layoutOptions = (if (currentFetched) emptyList() else listOf(current)) + fetchedLayouts
        val index = layoutOptions.indexOfFirst { it?.id == current?.id }.coerceAtLeast(0)
        updatingLayoutOptions = true
        try {
            layoutSelect.setOptions(layoutOptions.map { it?.name ?: NO_LAYOUT }.toTypedArray())
            layoutSelect.setValue(index.toDouble())
        } finally {
            updatingLayoutOptions = false
        }
    }

    private fun fetchLayouts() {
        val user = email.string
        val pass = password.string
        if (user.isEmpty() || pass.isEmpty()) {
            signInMessage = "Enter email and password first"
            updateAccountStatus()
            return
        }
        signInMessage = "Signing in..."
        updateAccountStatus()
        // A newer fetch supersedes a running one. Started only once assigned, as the result check reads fetchJob.
        fetchJob?.cancel()
        fetchJob = scope.launch(start = CoroutineStart.LAZY) {
            val result = runCatching { TwinklyCloudAPI.getLayouts(user, pass) }
            ensureActive()
            val self = coroutineContext[Job]
            lx.engine.addTask {
                // Skip results of a superseded fetch or a disposed fixture
                if (fetchJob !== self || self?.isCancelled != false) return@addTask
                result.onSuccess { layouts ->
                    fetchedLayouts = layouts
                    signInMessage = when (layouts.size) {
                        0 -> "No layouts in this account"
                        1 -> "Loaded 1 layout"
                        else -> "Loaded ${layouts.size} layouts"
                    }
                    // Pick up changes to the current layout, e.g. a new IP or re-mapped LEDs
                    val refreshed = layouts.find { it.id == layout?.id && it.json != layout?.json }
                    if (refreshed != null) applyLayout(refreshed) else updateLayoutOptions()
                }.onFailure { e ->
                    LX.error(e, "Error fetching Twinkly layouts")
                    signInMessage = failureText(e)
                }
                updateAccountStatus()
            }
        }
        fetchJob?.start()
    }

    private fun selectLayout(index: Int) {
        val selected = layoutOptions.getOrNull(index) ?: return
        if (selected.id != layout?.id) applyLayout(selected)
    }

    private fun applyLayout(selected: LayoutFacade) {
        // Name the fixture after the layout, unless the user renamed it
        if (label.isDefault || label.string == layout?.name) label.setValue(selected.name)
        layout = selected
        layoutJson.setValue(selected.json)
    }

    override fun size(): Int = layout?.points?.size ?: 0

    override fun computePointGeometry(transform: LXMatrix?, points: MutableList<LXPoint>) {
        val l = layout ?: return
        points.forEachIndexed { i, p -> p.set(transform, l.points[i]) }
    }

    override fun buildOutputs() {
        val info = deviceInfo
        val current = device
        if (info?.ip != current?.ip || info?.protocolVersion != current?.protocolVersion) {
            current?.dispose()
            device = info?.let { TwinklyDevice(it.ip, it.protocolVersion, movies::onModeChanged) }
            if (enabled.isOn) device?.startStream()
            movies.onDeviceChanged()
        }
        val d = device
        if (d != null && info != null) addOutputDirect(
            TwinklyOutput(lx, d, points.map { it.index }.toIntArray(), info.byteOrder, movies::recorder)
        )
    }

    override fun save(lx: LX, obj: JsonObject) {
        super.save(lx, obj)
        if (savePassword.isOn) obj.addProperty(KEY_PASSWORD, password.string)
    }

    override fun load(lx: LX, obj: JsonObject) {
        super.load(lx, obj)
        password.setValue(obj.get(KEY_PASSWORD)?.asString ?: "")
    }

    override fun dispose() {
        scope.cancel()
        movies.dispose()
        device?.dispose()
        super.dispose()
    }

    companion object {
        private const val NO_LAYOUT = "No layout"
        private const val HINT_NO_LAYOUT = "Use your Twinkly app login"
        /** The chosen layout is saved with the project, so the login is only needed to switch. */
        private const val HINT_HAS_LAYOUT = "Only needed to change the layout"
        private const val KEY_PASSWORD = "twinklyPassword"
    }
}

/** A failure for a status line, from its root cause, which carries the most specific message. */
internal fun failureText(e: Throwable): String {
    val cause = generateSequence(e) { it.cause }.last()
    return "Failed: " + (cause.message ?: cause.javaClass.simpleName)
}
