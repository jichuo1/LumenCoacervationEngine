package com.lumen.coacervation.sample

import android.os.SystemClock
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumen.coacervation.engine.controls.LumenReorderCallback
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReorderLifecycleRegressionTest {
    private class Holder(view: TextView) : RecyclerView.ViewHolder(view)
    private class Rows : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = 3
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, 120)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) { (holder.itemView as TextView).text = "Row $position" }
    }

    private fun await(scenario: ActivityScenario<SurfaceSandboxActivity>, message: String, ready: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < until) {
            var done = false
            scenario.onActivity { done = ready() }
            if (done) return
            SystemClock.sleep(20)
        }
        fail(message)
    }

    private fun withHelper(test: (ActivityScenario<SurfaceSandboxActivity>, RecyclerView, ItemTouchHelper, LumenReorderCallback, MutableList<Int>) -> Unit) {
        ActivityScenario.launch(SurfaceSandboxActivity::class.java).use { scenario ->
            lateinit var list: RecyclerView
            lateinit var helper: ItemTouchHelper
            lateinit var callback: LumenReorderCallback
            val drops = ArrayList<Int>()
            scenario.onActivity { activity ->
                list = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity); adapter = Rows() }
                callback = LumenReorderCallback({ true }, { _, _ -> true }, {
                    assertFalse("Business callbacks must wait for all drag recovery", callback.isDragging)
                    drops.add(it)
                    helper.attachToRecyclerView(null)
                })
                helper = ItemTouchHelper(callback)
                activity.setContentView(list)
                helper.attachToRecyclerView(list)
            }
            try {
                await(scenario, "Rows never laid out") { list.findViewHolderForAdapterPosition(1) != null }
                test(scenario, list, helper, callback, drops)
            } finally { scenario.onActivity { helper.attachToRecyclerView(null) } }
        }
    }

    private fun endSelection(helper: ItemTouchHelper) {
        ItemTouchHelper::class.java.getDeclaredMethod("select", RecyclerView.ViewHolder::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(helper, null, ItemTouchHelper.ACTION_STATE_IDLE)
    }

    @Test fun onDropMayDetachTheRealHelperWithoutReenteringRecoveryCleanup() {
        withHelper { scenario, list, helper, callback, drops ->
            scenario.onActivity {
                helper.startDrag(requireNotNull(list.findViewHolderForAdapterPosition(0)))
                assertTrue(callback.isDragging)
                endSelection(helper)
                assertTrue("Drop must leave the recovery stack before notifying", drops.isEmpty())
            }
            await(scenario, "Drop did not complete") { drops.size == 1 && !callback.isDragging }
            SystemClock.sleep(100)
            scenario.onActivity { assertEquals(listOf(0), drops) }
        }
    }

    @Test fun twoRecoveriesFinishOnceEvenWhenHelperDetachesBeforeTheirNaturalEnd() {
        withHelper { scenario, list, helper, callback, drops ->
            scenario.onActivity {
                helper.startDrag(requireNotNull(list.findViewHolderForAdapterPosition(0)))
                endSelection(helper)
                helper.startDrag(requireNotNull(list.findViewHolderForAdapterPosition(1)))
                endSelection(helper)
                val recoveries = ItemTouchHelper::class.java.getDeclaredField("mRecoverAnimations").apply { isAccessible = true }
                    .get(helper) as List<*>
                assertEquals("Exercise the pinned helper's multiple-recovery detach path", 2, recoveries.size)
                helper.attachToRecyclerView(null)
                assertTrue("First repeated cleanup must not unlock the second recovery", callback.isDragging)
                assertTrue(drops.isEmpty())
            }
            await(scenario, "Late detached recovery never completed") { drops.size == 2 && !callback.isDragging }
            SystemClock.sleep(100)
            scenario.onActivity { assertEquals(listOf(0, 1), drops) }
        }
    }
}
