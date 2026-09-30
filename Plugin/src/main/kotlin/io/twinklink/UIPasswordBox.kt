package io.twinklink

import heronarts.glx.ui.component.UITextBox
import heronarts.lx.clipboard.LXClipboardItem
import heronarts.lx.parameter.StringParameter

/** Text box that masks its value when not editing and starts edits from an empty buffer. */
class UIPasswordBox(w: Float, h: Float, parameter: StringParameter) : UITextBox(0f, 0f, w, h, parameter) {
    override fun getValueString(): String = "•".repeat(value?.length ?: 0)
    override fun getInitialEditBufferValue(): String = ""
    override fun onCopy(): LXClipboardItem? = null

    /** Any printable character, not just UITextBox's ASCII whitelist. */
    override fun isValidCharacter(keyChar: Char): Boolean = !keyChar.isISOControl()

    /**
     * Keeps surrounding spaces, which UITextBox trims. Empty input is still ignored,
     * as leaving the field saves the edit buffer, which starts out empty.
     */
    override fun saveEditBuffer(editBuffer: String) {
        if (editBuffer.isNotEmpty()) setValue(editBuffer)
    }
}
