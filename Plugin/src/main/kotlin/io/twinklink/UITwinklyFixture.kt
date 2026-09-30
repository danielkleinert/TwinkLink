package io.twinklink

import heronarts.glx.ui.UI2dContainer
import heronarts.glx.ui.component.UIButton
import heronarts.glx.ui.component.UICheckbox
import heronarts.glx.ui.component.UILabel
import heronarts.glx.ui.vg.VGraphics
import heronarts.lx.parameter.StringParameter
import heronarts.lx.studio.LXStudio
import heronarts.lx.studio.ui.fixture.UIFixture
import heronarts.lx.studio.ui.fixture.UIFixtureControls

class UITwinklyFixture : UIFixtureControls<TwinklyFixture> {

    override fun buildFixtureControls(ui: LXStudio.UI, uiFixture: UIFixture, fixture: TwinklyFixture) {
        uiFixture.addTagSection()

        val labelWidth = UIFixture.PARAMETER_LABEL_WIDTH.toFloat()
        val fullWidth = uiFixture.contentWidth
        val controlWidth = fullWidth - labelWidth - 2
        val checkboxSize = 10f
        val rowHeight = 16f

        val account = uiFixture.addSection("Twinkly Account")
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
            UIButton(0f, 0f, fullWidth, rowHeight)
                .setParameter(fixture.fetch)
                .setLabel(fixture.fetch.label)
                .setDescription(fixture.fetch.description)
        )
        statusLabel(ui, fixture.accountStatus, fullWidth, rowHeight).addToContainer(account)

        val layout = uiFixture.addSection("Twinkly Layout")
        layout.addControlRow(
            uiFixture.newParameterLabel(fixture.layoutSelect.label, labelWidth),
            uiFixture.newControlDropMenu(fixture.layoutSelect, controlWidth)
        )
        statusLabel(ui, fixture.layoutInfo, fullWidth, rowHeight).addToContainer(layout)

        uiFixture.addGeometrySection()
        uiFixture.addRenderingSection()
    }

    /**
     * Secondary text line bound to a status parameter. Added directly to the section,
     * not wrapped in a control row, so hiding it while empty leaves no gap.
     */
    private fun statusLabel(ui: LXStudio.UI, status: StringParameter, width: Float, height: Float): UILabel {
        val label = UILabel(0f, 0f, width, height)
        label.setPadding(0, 4)
        label.setFont(ui.theme.controlFont)
        label.setFontColor(ui.theme.helpTextColor)
        label.setTextAlignment(VGraphics.Align.LEFT, VGraphics.Align.MIDDLE)
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
}
