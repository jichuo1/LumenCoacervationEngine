package com.lumen.coacervation.engine

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.geometry.ViewSamplingMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采样矩阵的单位矩阵快路径（2026-10-25）：用设备真实的 RenderNode 矩阵逐位核对，
 * `viewStep` 与直接 `getValues` 在任何变换组合下给出相同的 9 个值。
 */
@RunWith(AndroidJUnit4::class)
class ViewStepInstrumentedTest {
    private data class Pose(val tx: Float = 0f, val ty: Float = 0f, val sx: Float = 1f, val sy: Float = 1f,
        val rotation: Float = 0f, val px: Float? = null, val py: Float? = null)

    @Test fun identityFastPathMatchesGetValuesForEveryPose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val matrices = ViewSamplingMatrix()
        val poses = listOf(Pose(), Pose(tx = 12f), Pose(ty = -3.25f), Pose(tx = 0.5f, ty = 0.125f),
            Pose(sx = 1.07f), Pose(sx = 0.92f, sy = 1.15f, px = 40f, py = 20f), Pose(rotation = 3.5f),
            Pose(rotation = 360f), Pose(tx = -0f, ty = -0f), Pose(sx = 1f, sy = 1f, px = 99f, py = 7f),
            Pose(tx = 1e-6f), Pose(sx = 1f + 1e-6f))
        var identities = 0
        for (pose in poses) {
            val view = View(context)
            view.layout(0, 0, 120, 48)
            view.translationX = pose.tx; view.translationY = pose.ty
            view.scaleX = pose.sx; view.scaleY = pose.sy
            view.rotation = pose.rotation
            pose.px?.let { view.pivotX = it }; pose.py?.let { view.pivotY = it }
            val expected = FloatArray(9).also(view.matrix::getValues)
            val actual = FloatArray(9).also { matrices.viewStep(view, it) }
            if (view.matrix.isIdentity) identities++
            for (index in 0 until 9) {
                // ±0 是同一个数值（-0f 平移时 Skia 仍判为单位矩阵）；其余情况要求位模式也相同。
                if (expected[index] == 0f && actual[index] == 0f) continue
                assertEquals("$pose index $index", expected[index].toRawBits(), actual[index].toRawBits())
            }
        }
        assertTrue("the fast path must actually be exercised", identities >= 3)
    }
}
