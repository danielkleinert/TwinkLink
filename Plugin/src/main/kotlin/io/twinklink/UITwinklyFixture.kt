package io.twinklink

import heronarts.glx.ui.UI
import heronarts.glx.ui.UI2dComponent
import heronarts.glx.ui.UI2dContainer
import heronarts.glx.ui.component.UIButton
import heronarts.glx.ui.component.UICheckbox
import heronarts.glx.ui.component.UILabel
import heronarts.glx.ui.component.UIMeter
import heronarts.glx.ui.vg.VGraphics
import heronarts.lx.parameter.BoundedParameter
import heronarts.lx.parameter.StringParameter
import heronarts.lx.studio.LXStudio
import heronarts.lx.studio.ui.fixture.UIFixture
import heronarts.lx.studio.ui.fixture.UIFixtureControls
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Thin meters, centered in their row by position, as rows only lay out horizontally. */
private const val METER_HEIGHT = 6f

private fun meterY(rowHeight: Float) = (rowHeight - METER_HEIGHT) / 2

class UITwinklyFixture : UIFixtureControls<TwinklyFixture> {

    override fun buildFixtureControls(ui: LXStudio.UI, uiFixture: UIFixture, fixture: TwinklyFixture) {
        uiFixture.addTagSection()

        val labelWidth = UIFixture.PARAMETER_LABEL_WIDTH.toFloat()
        val fullWidth = uiFixture.contentWidth
        val controlWidth = fullWidth - labelWidth - 2
        val checkboxSize = 10f
        val rowHeight = 16f

        val account = uiFixture.addSection("Account")
        account.addControlRow(
            uiFixture.newParameterLabel(fixture.email.label, labelWidth),
            uiFixture.newControlTextBox(fixture.email, controlWidth)
        )
        account.addControlRow(
            uiFixture.newParameterLabel(fixture.password.label, labelWidth),
            UIPasswordBox(controlWidth, rowHeight, fixture.password)
        )
        account.addControlRow(
            UILabel(0f, 0f, labelWidth, rowHeight),
            // Unlayouted box so the checkbox keeps its offset and is centered like the label text
            UI2dContainer(0f, 0f, rowHeight, rowHeight).also {
                val inset = (rowHeight - checkboxSize) / 2
                UICheckbox(0f, inset, checkboxSize, checkboxSize, fixture.savePassword).addToContainer(it)
            },
            uiFixture.newParameterLabel(fixture.savePassword.label, controlWidth - rowHeight - 2)
                .setDescription(fixture.savePassword.description)
        )
        account.addControlRow(
            UIButton(0f, 0f, fullWidth, rowHeight, fixture.fetch)
        )
        statusLabel(ui, fixture.accountStatus, fullWidth, rowHeight).addToContainer(account)

        val layout = uiFixture.addSection("Layout")
        layout.addControlRow(
            uiFixture.newParameterLabel(fixture.layoutSelect.label, labelWidth),
            uiFixture.newControlDropMenu(fixture.layoutSelect, controlWidth)
        )
        statusLabel(ui, fixture.layoutInfo, fullWidth, rowHeight).addToContainer(layout)

        uiFixture.addGeometrySection()
        uiFixture.addRenderingSection()

        buildMoviesSection(ui, uiFixture, fixture.movies, labelWidth, controlWidth, fullWidth, rowHeight)
        buildRecordSection(ui, uiFixture, fixture.movies, labelWidth, controlWidth, fullWidth, rowHeight)
    }

    private fun buildRecordSection(
        ui: LXStudio.UI, uiFixture: UIFixture, movies: TwinklyMovies,
        labelWidth: Float, controlWidth: Float, fullWidth: Float, rowHeight: Float
    ) {
        val section = uiFixture.addSection("Record Movie")
        section.addControlRow(
            uiFixture.newParameterLabel(movies.recordName.label, labelWidth),
            uiFixture.newControlTextBox(movies.recordName, controlWidth)
        )
        for (parameter in listOf(movies.recordDuration, movies.recordBlend)) {
            section.addControlRow(
                uiFixture.newParameterLabel(parameter.label, labelWidth),
                uiFixture.newControlBox(parameter, controlWidth)
            )
        }
        section.addControlRow(
            uiFixture.newParameterLabel(movies.recordFps.label, labelWidth),
            uiFixture.newControlIntBox(movies.recordFps, controlWidth)
        )
        section.addControlRow(
            uiFixture.newParameterLabel("Storage", labelWidth),
            UIRecordingStorage(ui, movies, 0f, meterY(rowHeight), controlWidth, METER_HEIGHT)
        )
        section.addControlRow(
            UIButton(0f, 0f, labelWidth, rowHeight, movies.recording)
                .setActiveLabel("Cancel")
                .setInactiveLabel("Record")
                .setBorderRounding(ACTION_ROUNDING),
            thinMeter(ui, movies.recordProgress, controlWidth, rowHeight)
        )
        statusLabel(ui, movies.recordStatus, fullWidth, rowHeight).addToContainer(section)
    }

    private fun buildMoviesSection(
        ui: LXStudio.UI, uiFixture: UIFixture, movies: TwinklyMovies,
        labelWidth: Float, controlWidth: Float, fullWidth: Float, rowHeight: Float
    ) {
        val section = uiFixture.addSection("Movies")
        val storageLabel = uiFixture.newParameterLabel(movies.storage.label, labelWidth)
        section.addControlRow(storageLabel, thinMeter(ui, movies.storage, controlWidth, rowHeight))
        // Styled like Chromatik's item lists
        val listPadding = 4f
        val list = UI2dContainer(0f, 0f, fullWidth, 0f)
        list.setLayout(UI2dContainer.Layout.VERTICAL)
        list.setChildSpacing(2f)
        list.setPadding(listPadding)
        list.setBackgroundColor(ui.theme.listBackgroundColor)
        list.setBorderColor(ui.theme.listBorderColor)
        list.setBorderRounding(4)
        list.addToContainer(section)
        val buttonWidth = (fullWidth - 2) / 2
        val deleteLast = UIButton(0f, 0f, buttonWidth, rowHeight, movies.deleteLast)
        uiFixture.newControlRow(
            UIButton(0f, 0f, buttonWidth, rowHeight, movies.refresh).setBorderRounding(ACTION_ROUNDING),
            deleteLast.setBorderRounding(ACTION_ROUNDING)
        ).setTopMargin(2f).addToContainer(section)

        fun rebuild() {
            val state = movies.state
            storageLabel.setDescription(movies.storage.description)

            deleteLast.setEnabled(movies.lastMovie != null)

            list.removeAllChildren()
            // Layouts only position children along their axis, so the cross-axis padding is set as position
            val rowWidth = fullWidth - 2 * listPadding
            if (state != null) buildMovieRows(ui, movies, state, list, listPadding, rowWidth, rowHeight)
            val message = movies.message.ifEmpty { if (state?.list?.movies?.isEmpty() == true) "No movies on the device" else "" }
            if (message.isNotEmpty()) {
                helpLabel(ui, listPadding, 0f, rowWidth, rowHeight, message, VGraphics.Align.LEFT)
                    .setPadding(0, 4)
                    .addToContainer(list)
            }
        }
        rebuild()
        // The movies change on the engine thread, so the UI is updated from its own loop
        val changed = AtomicBoolean()
        val failed = AtomicBoolean()
        section.addListener(movies.changed) { changed.set(true) }
        section.addListener(movies.failed) { failed.set(true) }
        section.addLoopTask {
            if (changed.getAndSet(false)) rebuild()
            if (failed.getAndSet(false)) ui.showContextDialogMessage(movies.failure)
        }

        // Read the device whenever the inspector shows the fixture
        movies.refresh()
    }

    private fun buildMovieRows(
        ui: LXStudio.UI, movies: TwinklyMovies, state: MovieState, list: UI2dContainer, x: Float, width: Float, rowHeight: Float
    ) {
        val paddingX = 4f
        val paddingY = 2f
        val spacing = 2f
        val controlHeight = rowHeight - 2
        val buttonWidth = controlHeight
        val framesWidth = 40f
        val durationWidth = 36f
        val nameWidth = width - 2 * paddingX - framesWidth - durationWidth - buttonWidth - 3 * spacing
        for (movie in state.list.movies) {
            val playing = state.isPlaying(movie)
            val details = "${movie.frames} frames at ${movie.fps} fps"
            val name = UILabel.Control(ui, 0f, paddingY, nameWidth, controlHeight, movie.name)
                .setDescription(if (playing) "$details, playing" else details)
            val seconds = if (movie.fps > 0) String.format(Locale.ROOT, "%.1f s", movie.frames.toDouble() / movie.fps) else ""
            val frames = helpLabel(ui, 0f, paddingY, framesWidth, controlHeight, "${movie.frames} f", VGraphics.Align.RIGHT, details)
            val duration = helpLabel(ui, 0f, paddingY, durationWidth, controlHeight, seconds, VGraphics.Align.RIGHT, details)
            val play = UIButton.Action(0f, paddingY, buttonWidth, controlHeight, ui.theme.iconPlay) {
                if (playing) movies.stop() else movies.play(movie)
            }.apply {
                setActive(playing)
                setBorderRounding(BUTTON_ROUNDING)
                setDescription(
                    if (playing) "Stop ${movie.name} and switch the device off"
                    else "Play ${movie.name} on the device. Disables the fixture, which would otherwise keep streaming."
                )
            }
            // List item styled like the rows of Chromatik's fixture list
            UI2dContainer(x, 0f, width, controlHeight + 2 * paddingY).apply {
                setLayout(UI2dContainer.Layout.HORIZONTAL)
                setChildSpacing(spacing)
                setPadding(paddingY, paddingX, paddingY, paddingX)
                setBorderRounding(4)
                setBackgroundColor(ui.theme.listItemBackgroundColor)
                addChildren(name, frames, duration, play)
            }.addToContainer(list)
        }
    }

    private fun thinMeter(ui: LXStudio.UI, parameter: BoundedParameter, width: Float, rowHeight: Float) =
        UIMeter(ui, parameter, UIMeter.Axis.HORIZONTAL, 0f, meterY(rowHeight), width, METER_HEIGHT)

    private fun helpLabel(
        ui: LXStudio.UI, x: Float, y: Float, width: Float, height: Float, text: String, align: VGraphics.Align,
        description: String = text
    ): UILabel =
        UILabel.Control(ui, x, y, width, height, text).apply {
            setFontColor(ui.theme.helpTextColor)
            setTextAlignment(align, VGraphics.Align.MIDDLE)
            setDescription(description)
        }

    /**
     * Secondary text line bound to a status parameter. Added directly to the section,
     * not wrapped in a control row, so hiding it while empty leaves no gap.
     */
    private fun statusLabel(ui: LXStudio.UI, status: StringParameter, width: Float, height: Float): UILabel {
        val label = helpLabel(ui, 0f, 0f, width, height, "", VGraphics.Align.LEFT)
        label.setPadding(0, 4)
        label.setBottomMargin(6f)
        label.addListener(status, {
            label.label = status.string
            // Long messages such as errors are clipped, the tooltip shows them in full
            label.setDescription(status.string)
            label.setVisible(status.string.isNotEmpty())
            // The label has no background, so the section must repaint to clear the old text
            label.container?.redraw()
        }, true)
        return label
    }

    companion object {
        /** Square play buttons, slightly rounded like Chromatik's control boxes. */
        private const val BUTTON_ROUNDING = 2
        /** Like the New, Import and Export buttons of Chromatik's model pane. */
        private const val ACTION_ROUNDING = 4
    }
}

/** Previews the storage after recording, like the Movies meter: stored movies gray, the new one green, or red if it doesn't fit. */
private class UIRecordingStorage(ui: LXStudio.UI, private val movies: TwinklyMovies, x: Float, y: Float, w: Float, h: Float) :
    UI2dComponent(x, y, w, h) {
    private var used = 0f
    private var added = 0f
    private var fits = true

    init {
        setBorderColor(ui.theme.controlBorderColor)
        setBackgroundColor(ui.theme.meterBackgroundColor)
        setDescription(NOT_LOADED)
        // Polled like UIMeter, as the fit depends on the movies and on the duration and fps
        addLoopTask {
            val fit = movies.recordingFit()?.takeIf { it.list.maxCapacity > 0 }
            val u = fit?.let { it.list.usedFrames.toFloat() / it.list.maxCapacity } ?: 0f
            val a = fit?.let { it.frames.toFloat() / it.list.maxCapacity } ?: 0f
            val f = fit?.refusal == null
            if (u != used || a != added || f != fits) {
                used = u
                added = a
                fits = f
                setDescription(describe(fit))
                redraw()
            }
        }
    }

    private fun describe(fit: RecordingFit?): String {
        if (fit == null) return NOT_LOADED
        val list = fit.list
        val after = list.usedFrames + fit.frames
        return fit.refusal ?: "After recording: $after of ${list.maxCapacity} frames used, about ${fit.frames} for the new movie"
    }

    override fun onDraw(ui: UI, vg: VGraphics) {
        val inner = width - 2
        val usedPixels = inner * used.coerceIn(0f, 1f)
        // A movie that doesn't fit stays visible even with the storage full, drawn over its end
        val addedPixels = (inner * added).coerceAtMost(inner - usedPixels).let { if (fits) it else it.coerceAtLeast(MIN_REFUSED_PIXELS) }
        val addedStart = (1f + usedPixels).coerceAtMost(1f + inner - addedPixels)
        if (usedPixels > 0.5f) {
            vg.fillColor(USED_COLOR)
            vg.beginPath()
            vg.rect(1f, 1f, usedPixels, height - 2)
            vg.fill()
        }
        if (addedPixels > 0.5f) {
            if (fits) vg.fillColor(FITS_COLOR) else vg.fillColor(ui.theme.errorColor)
            vg.beginPath()
            vg.rect(addedStart, 1f, addedPixels, height - 2)
            vg.fill()
        }
    }

    companion object {
        private const val NOT_LOADED = "Refresh the movies to see the storage the recording takes"
        private const val USED_COLOR = 0xff6e6e6e.toInt()
        private const val FITS_COLOR = 0xff3fae4a.toInt()
        private const val MIN_REFUSED_PIXELS = 3f
    }
}
