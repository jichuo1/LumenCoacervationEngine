package com.lumen.coacervation.sample

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.liquid.LiquidStretchViewport
import com.lumen.coacervation.engine.motion.modal.LumenPanelScroll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Axis selection uses real RecyclerView layouts, including hosts with no scrollable overflow. */
@RunWith(AndroidJUnit4::class)
class PanelScrollAxisRegressionTest {
    private class Rows(var rows: Int) : RecyclerView.Adapter<Rows.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(80, 40)
        })
        override fun getItemCount() = rows
        override fun onBindViewHolder(holder: Holder, position: Int) { (holder.itemView as TextView).text = "$position" }
    }

    private class Fixture(activity: SurfaceSandboxActivity, orientation: Int, rows: Int) {
        val adapter = Rows(rows)
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity, orientation, false)
            this.adapter = this@Fixture.adapter
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        val root = FrameLayout(activity).apply { addView(list, FrameLayout.LayoutParams(240, 180)) }
        var installations = 0

        init {
            activity.binding.close()
            activity.setContentView(root)
            prepare()
        }

        fun prepare() = LumenPanelScroll.prepare(root) {
            installations++
            LiquidStretchViewport.installAround(it, { true }) { _, _ -> }
        }
    }

    @Test fun horizontalRecyclerViewRetainsItsPlatformEdgesAfterPreparationBeforeLayout() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, RecyclerView.HORIZONTAL, 12); fixture.prepare() }
            frame(scenario, fixture.root)
            scenario.onActivity {
                fixture.prepare()
                assertEquals(0, fixture.installations)
                assertSame(fixture.root, fixture.list.parent)
                assertEquals(View.OVER_SCROLL_ALWAYS, fixture.list.overScrollMode)
                assertTrue(fixture.list.canScrollHorizontally(1))
            }
        }
    }

    @Test fun shortVerticalRecyclerViewInstallsAfterItsFirstLayoutAndRemainsIdempotent() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, RecyclerView.VERTICAL, 1); fixture.prepare() }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertFalse(fixture.list.canScrollVertically(-1))
                assertFalse(fixture.list.canScrollVertically(1))
                assertTrue(fixture.list.parent is LiquidStretchViewport)
                assertEquals(View.OVER_SCROLL_NEVER, fixture.list.overScrollMode)
                fixture.prepare()
                assertEquals(1, fixture.installations)
            }
        }
    }

    @Test fun emptyVerticalRecyclerViewResolvesWhenRowsArriveWithoutChangingItsBounds() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            var width = 0
            var height = 0
            scenario.onActivity { fixture = Fixture(it, RecyclerView.VERTICAL, 0); fixture.prepare() }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertEquals(0, fixture.installations)
                width = fixture.list.width
                height = fixture.list.height
                fixture.adapter.rows = 1
                fixture.adapter.notifyDataSetChanged()
            }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertEquals(width, fixture.list.width)
                assertEquals(height, fixture.list.height)
                assertTrue(fixture.list.parent is LiquidStretchViewport)
                fixture.prepare()
                assertEquals(1, fixture.installations)
            }
        }
    }

    @Test fun detachedUnresolvedHostEndsItsPendingCheckAndCanBeExplicitlyPreparedAgain() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, RecyclerView.VERTICAL, 0) }
            frame(scenario, fixture.root)
            scenario.onActivity {
                fixture.root.removeView(fixture.list)
                fixture.adapter.rows = 1
                fixture.adapter.notifyDataSetChanged()
                fixture.root.addView(fixture.list, FrameLayout.LayoutParams(240, 180))
            }
            frame(scenario, fixture.root)
            scenario.onActivity {
                assertSame(fixture.root, fixture.list.parent)
                assertEquals(0, fixture.installations)
                fixture.prepare()
                assertTrue(fixture.list.parent is LiquidStretchViewport)
                fixture.prepare()
                assertEquals(1, fixture.installations)
            }
        }
    }

    @Test fun deferredOuterRecyclerViewAlsoPreparesItsNewlyLaidOutInlineList() {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var outer: RecyclerView
            lateinit var inner: RecyclerView
            var installations = 0
            fun prepare() = LumenPanelScroll.prepare(root) {
                installations++
                LiquidStretchViewport.installAround(it, { true }) { _, _ -> }
            }
            scenario.onActivity { activity ->
                activity.binding.close()
                inner = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = Rows(1)
                }
                outer = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = object : RecyclerView.Adapter<Rows.Holder>() {
                        override fun getItemCount() = 1
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Rows.Holder {
                            val slot = FrameLayout(activity).apply {
                                layoutParams = RecyclerView.LayoutParams(240, 80)
                                addView(inner, FrameLayout.LayoutParams(220, 60))
                            }
                            return Rows.Holder(slot)
                        }
                        override fun onBindViewHolder(holder: Rows.Holder, position: Int) = Unit
                    }
                }
                root = FrameLayout(activity).apply { addView(outer, FrameLayout.LayoutParams(240, 180)) }
                activity.setContentView(root)
                prepare()
                prepare()
            }
            frame(scenario, root)
            scenario.onActivity {
                assertTrue(outer.parent is LiquidStretchViewport)
                assertTrue(inner.parent is LiquidStretchViewport)
                prepare()
                assertEquals(2, installations)
            }
        }
    }

    private fun frame(scenario: ActivityScenario<SurfaceSandboxActivity>, root: View) {
        val frames = CountDownLatch(1)
        scenario.onActivity {
            root.postOnAnimation { root.postOnAnimation { root.postOnAnimation { frames.countDown() } } }
            root.postInvalidateOnAnimation()
        }
        assertTrue(frames.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
