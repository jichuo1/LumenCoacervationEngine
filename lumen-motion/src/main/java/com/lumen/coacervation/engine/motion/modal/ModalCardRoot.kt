package com.lumen.coacervation.engine.motion.modal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import com.lumen.coacervation.engine.liquid.LiquidMotionSurfaceFrameProvider
import kotlin.math.roundToInt

/**
 * 弹窗卡片层的根。平时就是普通 FrameLayout；被覆盖式子面板盖住时，把子面板**当前**的
 * 圆角矩形从自己的绘制里挖掉（`clipOutPath`）。
 *
 * 为什么不用整体透明度（2026-09-24 两轮真机）：
 * - 父面板淡出放在 0.90..1.0：形变是 ease-out，尾段形状已停住，子面板里"两层半透明玻璃
 *   叠加"的亮度这时才掉下去，读作背景跳变加载（241 → 229）。
 * - 挪到 0.50..0.85：父面板先淡没、子面板还没长满，外轮廓从父面板退回子面板大小再跟着
 *   往外长，读作"下面 GitHub 面板的边缘先往回收再展开"（988ms 实测外轮廓 142..1361 ×
 *   620..2287，父面板是 112..1391 × 448..2774）。
 * 两者是同一条透明度曲线的两头，无解。挖空后：子面板外父面板完整可见，外轮廓不动。
 *
 * 子面板区域内**不是**一刀切挖掉，而是与子面板交叉淡变：父面板按 `1 − 子面板不透明度`
 * 画在离屏层里。子面板刚长出来时几乎透明（承载层 alpha 从 0 起），一刀切会直接透出压暗层
 * 和主界面，形成一块深灰胶囊（2026-09-24 真机：面板内暗像素 2.4% → 12%）；交叉淡变后
 * 开头父面板完整、结尾只剩子面板一层，全程不露底、不叠亮。
 *
 * 只重录本层 display list（子 View 的 display list 复用）；离屏层只有子面板矩形大小，
 * 且只在形变期间存在。
 */
public class ModalCardRoot(context: Context) : FrameLayout(context) {
    private val exclusion = Path()
    private val exclusionRect = RectF()
    private val sourceLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private var hasExclusion = false
    private var coverOpacity = 0f

    /**
     * 按 [source] 当前的形变矩形与圆角让位；两者可以在不同窗口里，按屏幕坐标换算。
     * [opacity] 是子面板此刻的不透明度（承载层 alpha），决定区域内父面板保留多少。
     */
    fun <T> excludeMotionSurface(source: T, opacity: Float) where T : View, T : LiquidMotionSurfaceFrameProvider {
        coverOpacity = opacity.coerceIn(0f, 1f)
        source.copyLiquidMotionBounds(exclusionRect)
        if (exclusionRect.isEmpty || !source.isAttachedToWindow || !isAttachedToWindow) {
            clearExclusion()
            return
        }
        source.getLocationOnScreen(sourceLocation)
        getLocationOnScreen(ownLocation)
        exclusionRect.offset(
            (sourceLocation[0] - ownLocation[0]).toFloat(),
            (sourceLocation[1] - ownLocation[1]).toFloat()
        )
        val radius = source.liquidMotionCornerRadiusPx()
        exclusion.rewind()
        exclusion.addRoundRect(exclusionRect, radius, radius, Path.Direction.CW)
        hasExclusion = true
        invalidate()
    }

    fun clearExclusion() {
        if (!hasExclusion) return
        hasExclusion = false
        exclusion.rewind()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!hasExclusion || coverOpacity <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val outside = canvas.save()
        canvas.clipOutPath(exclusion)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(outside)
        val insideAlpha = ((1f - coverOpacity) * 255f).roundToInt()
        if (insideAlpha <= 0) return
        val inside = canvas.save()
        canvas.clipPath(exclusion)
        canvas.saveLayerAlpha(exclusionRect, insideAlpha)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(inside)
    }
}
