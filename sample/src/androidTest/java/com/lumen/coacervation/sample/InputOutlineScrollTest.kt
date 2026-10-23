package com.lumen.coacervation.sample

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.controls.LumenControls
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.model.LumenPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputOutlineScrollTest {
    @get:org.junit.Rule val hardwareOutput = HardwareOutputRule()

    @Test fun longSingleLineInputKeepsItsOutlineAtBothSignedScrollOffsets() = runCase(multiline = false)
    @Test fun longMultilineInputKeepsItsOutlineWhileTheCursorScrollsVertically() = runCase(multiline = true)

    private fun runCase(multiline: Boolean) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var activity: SurfaceSandboxActivity
            lateinit var input: EditText
            lateinit var lumen: LumenActivityDelegate
            scenario.onActivity {
                activity = it
                activity.binding.close(); activity.session.close()
                lumen = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
                lumen.prepare()
                input = EditText(activity).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f)
                    setPadding(24, 24, 24, 24)
                    setTextColor(Color.WHITE)
                    isCursorVisible = false
                    showSoftInputOnFocus = false
                    if (multiline) { setSingleLine(false); maxLines = 3 } else setSingleLine()
                    setText("short input")
                    keepScreenOn = true
                }
                val availableWidth = activity.resources.displayMetrics.widthPixels
                val margin = (availableWidth / 8).coerceAtMost(80)
                activity.setContentView(FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(input, FrameLayout.LayoutParams((availableWidth - margin * 2).coerceAtMost(360), 160).apply {
                        leftMargin = margin
                        topMargin = (activity.resources.displayMetrics.heightPixels / 6).coerceAtMost(160)
                    })
                })
                LumenControls.style(input, lumen)
                // Isolate the outline pixels from the material's own border and optical background.
                input.background = ColorDrawable(Color.rgb(24, 24, 24))
                input.requestFocus()
            }
            try {
                var expected = IntArray(0)
                fun borderPixels(): IntArray {
                    var colors = IntArray(0)
                    WindowRegressionCapture.copy(activity, input) { image ->
                        val inset = input.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                        colors = intArrayOf(image.getPixel(inset, 80), image.getPixel(input.width - 1 - inset, 80),
                            image.getPixel(input.width / 2, inset), image.getPixel(input.width / 2, 159 - inset))
                    }
                    return colors
                }
                expected = borderPixels()
                assertTrue("The fixture must contain a visible foreground outline", expected.any { it != Color.rgb(24, 24, 24) })
                WindowRegressionCapture.main {
                    input.setText(if (multiline) (1..100).joinToString("\n") { "line $it has long input" }
                        else "Long editable input 0123456789 ".repeat(100))
                    input.setSelection(input.text.length)
                }
                val deadline = SystemClock.uptimeMillis() + 5000
                var scrolled = false
                while (!scrolled && SystemClock.uptimeMillis() < deadline) {
                    WindowRegressionCapture.frame(input)
                    WindowRegressionCapture.main { scrolled = if (multiline) input.scrollY > 0 else input.scrollX != 0 }
                }
                assertTrue("Real cursor movement must scroll the overflowing input", scrolled)
                assertArrayEquals("Native text scrolling moved the outline", expected, borderPixels())
                if (!multiline) {
                    WindowRegressionCapture.main {
                        val outline = input.foreground
                        // Detach first: View.setForeground otherwise clears the child's new callback.
                        input.foreground = null
                        val layer = LayerDrawable(arrayOf(outline))
                        input.foreground = layer
                        assertSame(layer, outline.callback)
                        assertSame(input, layer.callback)
                    }
                    for (offset in listOf(-72, 96)) {
                        WindowRegressionCapture.main {
                            input.layoutDirection = if (offset < 0) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        }
                        WindowRegressionCapture.frame(input)
                        WindowRegressionCapture.main { input.scrollTo(offset, 0) }
                        assertArrayEquals("The outline must stay fixed for signed/RTL scroll=$offset", expected, borderPixels())
                        WindowRegressionCapture.main { assertEquals(offset, input.scrollX) }
                    }
                }
                WindowRegressionCapture.main { input.setText("short again"); input.setSelection(0); input.scrollTo(0, 0) }
                assertArrayEquals("Removing overflowing input must restore identical border pixels", expected, borderPixels())
                WindowRegressionCapture.main {
                    assertEquals("short again", input.text.toString()); assertEquals(0, input.selectionStart)
                    assertEquals(24, input.paddingLeft); assertEquals(24, input.paddingRight)
                }
            } finally { WindowRegressionCapture.main { lumen.onDestroy() } }
        }
    }
}
