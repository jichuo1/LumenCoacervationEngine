package com.lumen.coacervation.sample

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.LumenEngine
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.liquid.LiquidStretchEdge
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SkinId
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Measures actual display-list recordings in two real windows owned by the same renderer. */
@RunWith(AndroidJUnit4::class)
class PanelStretchWindowIsolationTest {
    private class CountingText(context: android.content.Context) : TextView(context) {
        var draws = 0
        override fun onDraw(canvas: android.graphics.Canvas) { draws++; super.onDraw(canvas) }
    }

    @Test fun dialogReboundDoesNotRerecordTheStationaryActivityBehindIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previous = LumenEngine.requestedMaterial(context)
        instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, SkinId.LIQUID, realtimeCapture = false) }
        try {
            ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
                lateinit var material: LumenActivityDelegate
                lateinit var dialog: Dialog
                lateinit var scroll: NestedScrollView
                lateinit var dialogRoot: View
                val mainRows = ArrayList<CountingText>()
                val popupRows = ArrayList<CountingText>()
                scenario.onActivity { activity ->
                    activity.session.close()
                    material = LumenActivityDelegate(activity, { LumenPalette.neutral(false) })
                    material.prepare()
                    val mainBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    repeat(6) { mainBody.addView(CountingText(activity).apply {
                        text = "background $it"
                        background = material.cardBackground()
                        mainRows += this
                    }, LinearLayout.LayoutParams(500, 90)) }
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(mainBody, FrameLayout.LayoutParams(500, 540).apply { leftMargin = 80; topMargin = 80 })
                    })
                    assertTrue(material.bindRoot(activity.window.decorView))
                    material.onStart()
                    assertTrue(material.isLiquidEffective)
                    val popupBody = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    repeat(12) { popupBody.addView(CountingText(activity).apply {
                        text = "popup $it"
                        background = material.cardBackground()
                        popupRows += this
                    }, LinearLayout.LayoutParams(500, 90)) }
                    scroll = NestedScrollView(activity).apply { addView(popupBody) }
                    dialog = Dialog(activity).apply {
                        setContentView(scroll)
                        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        show()
                        window?.setLayout(500, 600)
                    }
                    dialogRoot = checkNotNull(dialog.window).decorView
                }
                frame(scenario, dialogRoot)
                var mainBefore = 0
                var popupBefore = 0
                scenario.onActivity {
                    mainBefore = mainRows.sumOf { it.draws }
                    popupBefore = popupRows.sumOf { it.draws }
                    assertTrue(mainBefore > 0 && popupBefore > 0)
                    material.engine!!.onStretchDistance(.1f, LiquidStretchEdge.TOP, scroll)
                }
                frame(scenario, dialogRoot)
                scenario.onActivity {
                    assertEquals("A dialog optical step must not rerecord the background Activity", mainBefore, mainRows.sumOf { it.draws })
                    assertTrue("The owner window must rerecord before its next draw", popupRows.sumOf { it.draws } > popupBefore)
                    popupBefore = popupRows.sumOf { it.draws }
                    material.engine!!.onStretchDistance(.15f, LiquidStretchEdge.BOTTOM, scroll)
                }
                frame(scenario, dialogRoot)
                scenario.onActivity { activity ->
                    val mainAfter = mainRows.sumOf { it.draws }
                    val popupAfter = popupRows.sumOf { it.draws }
                    assertEquals(mainBefore, mainAfter)
                    assertTrue(popupAfter > popupBefore)
                    java.io.File(activity.getExternalFilesDir(null), "window-isolation.txt").writeText(
                        "mainBefore=$mainBefore;mainAfter=$mainAfter;popupAfter=$popupAfter;twoDialogSteps=true")
                    dialog.dismiss()
                    material.onDestroy()
                }
            }
        } finally {
            instrumentation.runOnMainSync { LumenEngine.selectMaterial(context, previous) }
        }
    }

    private fun frame(scenario: ActivityScenario<SurfaceSandboxActivity>, root: View) {
        val drawn = CountDownLatch(1)
        scenario.onActivity {
            root.postOnAnimation { root.postOnAnimation { drawn.countDown() } }
            root.invalidate()
        }
        assertTrue("The hardware window must advance", drawn.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
