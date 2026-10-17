package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.model.LumenEffectTuning
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.morph.LumenMorphOptions
import com.lumen.coacervation.engine.morph.LumenMorphTile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 液态形变的像素契约：收起态只占左半格，展开态覆盖整个 2 格宿主。 */
@RunWith(AndroidJUnit4::class)
class LumenMorphTileTest {
    @Test
    fun collapsedShapeLeavesTheSecondCellEmptyAndExpandedFillsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tile = LumenMorphTile(context).apply {
            configure(LumenPalette.neutral(false), LumenEffectTuning.DEFAULT, LumenMorphOptions.DEFAULT)
            setContent(null, "测试")
            measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            layout(0, 0, 600, 300)
        }
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        try {
            fun drawAtSecondCell(): Int {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                tile.draw(canvas)
                return bitmap.getPixel(450, 150)
            }
            assertFalse(tile.isExpanded)
            assertTrue("collapsed must leave the second cell empty", drawAtSecondCell() == Color.WHITE)
            tile.setExpanded(true, animate = false)
            assertTrue(tile.isExpanded)
            assertNotEquals("expanded must fill the second cell", Color.WHITE, drawAtSecondCell())
        } finally {
            bitmap.recycle()
        }
    }
}
