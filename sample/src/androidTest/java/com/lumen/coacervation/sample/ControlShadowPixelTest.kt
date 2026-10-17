package com.lumen.coacervation.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.host.LumenControlShadow
import com.lumen.coacervation.engine.host.LumenControlShadowOptions
import com.lumen.coacervation.engine.widget.LumenNavigationBar
import com.lumen.coacervation.engine.widget.NavigationBarColors
import com.lumen.coacervation.engine.widget.NavigationBarSelectionMask
import com.lumen.coacervation.engine.widget.NavigationBarSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 控件软阴影/选中遮罩的像素契约：直接画 Drawable 或受控 View 树，不依赖截屏时序。
 * 覆盖：外侧连续衰减、内侧不被压暗、遮罩强度单调、重叠减弱、底栏背景阴影不被自身裁剪。
 */
@RunWith(AndroidJUnit4::class)
class ControlShadowPixelTest {

    private fun shadowOptions(soften: Boolean = false, attenuation: Float = 0.55f) =
        LumenControlShadowOptions(
            enabled = true,
            blurRadiusDp = 10f,
            offsetYDp = 0f,
            spreadDp = 0f,
            opacity = 0.2f,
            contactOpacity = 0.1f,
            adaptToTheme = false,
            softenOverlap = soften,
            overlapAttenuation = attenuation
        )

    private fun cardBackground(density: Float, soften: Boolean = false) =
        LumenControlShadow.wrap(
            GradientDrawable().apply { setColor(0xFFF2F2F2.toInt()); cornerRadius = 15f * density },
            15f,
            density,
            shadowOptions(soften),
            dark = false
        )

    private fun luminance(color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r + g + b) / 3.0
    }

    @Test
    fun shadowFadesOutsideTheContourAndKeepsTheInsideClean() = ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val options = shadowOptions()
            val outset = LumenControlShadow.outsetPx(options, density)
            val pad = outset + 8
            val cardW = 240
            val cardH = 160
            val bitmap = Bitmap.createBitmap(cardW + pad * 2, cardH + pad * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val shadow = cardBackground(density)
            shadow.setBounds(pad, pad, pad + cardW, pad + cardH)
            shadow.draw(canvas)
            try {
                val middleY = pad + cardH / 2
                val inside = luminance(bitmap.getPixel(pad + cardW / 2, middleY))
                assertTrue("inside must keep the card colour, was $inside", abs(inside - 242.0) < 6.0)

                var maxStep = 0.0
                var previous = luminance(bitmap.getPixel(8, middleY))
                val far = previous
                for (x in 9 until pad) {
                    val current = luminance(bitmap.getPixel(x, middleY))
                    maxStep = maxOf(maxStep, abs(current - previous))
                    previous = current
                }
                val near = previous
                assertTrue("shadow must be visible near the contour ($far -> $near)", near < far - 10.0)
                assertTrue("adjacent pixels must stay continuous, step=$maxStep", maxStep <= 8.0)
            } finally {
                bitmap.recycle()
            }
        }
    }.let { }

    @Test
    fun selectionMaskStrengthMovesTheOverlayMonotonically() = ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val palette = activity.lumen.palette
            assertEquals(0, Color.alpha(NavigationBarSelectionMask.color(palette, 0f)))
            assertEquals(255, Color.alpha(NavigationBarSelectionMask.color(palette, 1f)))

            fun distanceFromWhite(strength: Float): Double {
                val color = NavigationBarSelectionMask.color(palette, strength)
                val drawable = activity.lumen.chromeOverlayBackground(color, 22f, selected = true)
                val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    drawable.setBounds(0, 0, 120, 120)
                    drawable.draw(canvas)
                    return abs(255.0 - luminance(bitmap.getPixel(60, 60)))
                } finally {
                    bitmap.recycle()
                }
            }

            val none = distanceFromWhite(0f)
            val half = distanceFromWhite(0.5f)
            val full = distanceFromWhite(1f)
            assertEquals(0.0, none, 0.5)
            assertTrue("half mask must change the pixel ($half)", half > 1.0)
            assertTrue("full mask must be stronger than half ($half -> $full)", full > half + 1.0)
        }
    }.let { }

    @Test
    fun softenOverlapLiftsTheCrossing() = ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            fun crossingLuminance(soften: Boolean): Double {
                val parent = FrameLayout(activity).apply {
                    setBackgroundColor(Color.WHITE)
                    clipChildren = false
                    clipToPadding = false
                }
                parent.addView(View(activity).apply { background = cardBackground(density, soften) },
                    FrameLayout.LayoutParams(210, 200).apply { leftMargin = 10; topMargin = 10 })
                parent.addView(View(activity).apply { background = cardBackground(density, soften) },
                    FrameLayout.LayoutParams(210, 200).apply { leftMargin = 230; topMargin = 10 })
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(460, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)
                )
                parent.layout(0, 0, 460, 240)
                val bitmap = Bitmap.createBitmap(460, 240, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    parent.draw(canvas)
                    // 两张卡片相距 10px，交叉处同时吃到两侧阴影。
                    return luminance(bitmap.getPixel(225, 110))
                } finally {
                    bitmap.recycle()
                }
            }

            val additive = crossingLuminance(false)
            val softened = crossingLuminance(true)
            assertTrue("softenOverlap must lift the crossing ($additive -> $softened)", softened > additive + 4.0)
        }
    }.let { }

    @Test
    fun navigationBarShadowIsNotClippedByItsOwnCapsule() = ActivityScenario.launch(SampleActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val density = activity.resources.displayMetrics.density
            val options = shadowOptions()
            val parent = FrameLayout(activity).apply {
                setBackgroundColor(Color.WHITE)
                clipChildren = false
                clipToPadding = false
            }
            val bar = LumenNavigationBar(
                context = activity,
                titles = listOf("A"),
                icons = intArrayOf(android.R.drawable.ic_menu_add),
                colors = NavigationBarColors(Color.BLACK, Color.BLACK, Color.BLACK),
                backgroundFactory = { surface, radius ->
                    val base = GradientDrawable().apply {
                        setColor(0xFFF2F2F2.toInt())
                        cornerRadius = radius * density
                    }
                    if (surface == NavigationBarSurface.BAR) {
                        LumenControlShadow.wrap(base, radius, density, options, dark = false)
                    } else base
                },
                onSelect = {},
                onUserInteraction = {},
                onVisualMovement = {}
            )
            parent.addView(bar, FrameLayout.LayoutParams(800, 200).apply { leftMargin = 30; topMargin = 30 })
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY)
            )
            parent.layout(0, 0, 900, 320)
            val bitmap = Bitmap.createBitmap(900, 320, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                parent.draw(canvas)
                // 栏左缘外侧 2px：旧实现 clipToOutline 会把背景阴影裁成白底。
                val outside = luminance(bitmap.getPixel(28, 130))
                // 避开居中的图标/文字，取胶囊内靠左的表面。
                val inside = luminance(bitmap.getPixel(60, 130))
                assertTrue("bar shadow must survive the capsule clip, outside=$outside", outside < 250.0)
                assertTrue("bar fill must stay clean, inside=$inside", inside > 220.0)
            } finally {
                bitmap.recycle()
            }
        }
    }.let { }
}
