package io.twinklink

import heronarts.glx.ui.UI2dContainer
import heronarts.glx.ui.component.UIButton
import heronarts.glx.ui.component.UICheckbox
import heronarts.glx.ui.component.UILabel
import heronarts.glx.ui.component.UIMeter
import heronarts.glx.ui.vg.VGraphics
import heronarts.lx.parameter.StringParameter
import heronarts.lx.studio.LXStudio
import heronarts.lx.studio.ui.fixture.UIFixture
import heronarts.lx.studio.ui.fixture.UIFixtureControls
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    }

    private fun buildMoviesSection(
        ui: LXStudio.UI, uiFixture: UIFixture, movies: TwinklyMovies,
        labelWidth: Float, controlWidth: Float, fullWidth: Float, rowHeight: Float
    ) {
        val section = uiFixture.addSection("Movies")
        val storageLabel = uiFixture.newParameterLabel(movies.storage.label, labelWidth)
        // A thin meter, centered as rows only lay out horizontally
        val meterHeight = 6f
        section.addControlRow(
            storageLabel,
            UIMeter(ui, movies.storage, UIMeter.Axis.HORIZONTAL, 0f, (rowHeight - meterHeight) / 2, controlWidth, meterHeight)
        )
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
