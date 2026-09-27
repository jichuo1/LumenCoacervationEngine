@file:Suppress("ReplaceWithViewOutlineProviderExtension")

package com.lumen.coacervation.engine.motion.modal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.lumen.coacervation.engine.liquid.LiquidMotionSurfaceFrameProvider
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 图标锚点形变承载层：覆盖路径分开绘制表面与卡片内容。
 *
 * 所有锚点弹窗复用气泡的有界皮肤表面并持续持有背景，取代卡片背景与全屏动画
 * 背景；只裁正文，形变首尾与落定态画的是**同一个 Drawable**，没有 drawable 交接，
 * 也就没有两套描边与抗锯齿边缘在终点互跳。卡片 elevation 移交到表面 View，
 * 投影随帧矩形生长，落定不再整圈弹出。承载层自身不接管 elevation。
 *
 * 与 `ContainerMorphHost` 的关系：两者共用"可变 outline 裁剪"这一个原语，但那个 host
 * 还要承担 backdrop、标题副本、跨窗口坐标和页面替换；图标锚点一条都不需要，所以单独实现，
 * 不去继承或改造它。
 */
@SuppressLint("ViewConstructor")
internal class IconAnchoredMotionLayer(
    context: Context,
    surfaceBackground: Drawable? = null,
    private val fallbackColor: Int = 0,
    private val surfaceRadiusPx: Float = 0f,
    surfaceElevation: Float = 0f
) : FrameLayout(context), LiquidMotionSurfaceFrameProvider {

    // 覆盖式子面板始终由同一个表面画填充和描边；只裁正文，不裁表面自身的抗锯齿边缘。
    // 复用气泡的皮肤桥接，Liquid 仍从真实 View 取得位置与刷新登记。
    private val persistentSurface = surfaceBackground?.let {
        BubbleSkinSurfaceView(context, it, fallbackColor)
    }
    private val contentClip = Path()
    val usesPersistentSurface: Boolean get() = persistentSurface != null

    private val motionBounds = RectF()
    private var motionRadius = 0f
    private var shaped = false

    /**
     * 形变期间吞掉全部触摸：卡片正在移动，落点与用户看到的位置对不上。
     *
     * 用 `isClickable` 让 `View.onTouchEvent` 自己消费，而不是重写 `onTouchEvent`——后者会触发
     * lint 的 `ClickableViewAccessibility`，而这个层本来就没有点击语义，不该为了绕检查去补一个
     * 空的 `performClick`。拦截交给 [onInterceptTouchEvent]，消费交给 clickable。
     */
    var blockInteraction = false
        set(value) {
            field = value
            isClickable = value
            isFocusable = value
            importantForAccessibility = if (value) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }

    init {
        clipChildren = true
        clipToPadding = false
        persistentSurface?.let { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        // 卡片阴影由本层持有：elevation 是层在 root 里的兄弟 Z 序（不影响子 View
        // 顺序——表面在下、卡片在上），outline 决定投影形状。形变期 outline=形变
        // 矩形，落定后 outline=卡片矩形，阴影全程连续、落定不弹出。表面 View 不能
        // 带 elevation（Z>0 的子 View 会排到最后绘制，半透明表面会盖住正文）。
        elevation = surfaceElevation
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (shaped && !motionBounds.isEmpty) {
                    outline.setRoundRect(
                        floor(motionBounds.left).toInt(),
                        floor(motionBounds.top).toInt(),
                        ceil(motionBounds.right).toInt(),
                        ceil(motionBounds.bottom).toInt(),
                        motionRadius
                    )
                    outline.alpha = 1f
                } else if (!motionBounds.isEmpty) {
                    // 落定态：updateRestingSurface 已把卡片矩形写回 motionBounds，
                    // 圆角回卡片固定半径——阴影贴着稳定卡片，不随覆盖正文伸缩。
                    outline.setRoundRect(
                        floor(motionBounds.left).toInt(),
                        floor(motionBounds.top).toInt(),
                        ceil(motionBounds.right).toInt(),
                        ceil(motionBounds.bottom).toInt(),
                        surfaceRadiusPx
                    )
                    outline.alpha = 1f
                } else {
                    outline.setRect(0, 0, view.width, view.height)
                    outline.alpha = 0f
                }
            }
        }
    }

    fun applyFrame(left: Float, top: Float, right: Float, bottom: Float, radiusPx: Float) {
        val normalizedRadius = radiusPx.coerceAtLeast(0f)
        if (shaped && (clipToOutline || usesPersistentSurface) &&
            motionBounds.left == left && motionBounds.top == top &&
            motionBounds.right == right && motionBounds.bottom == bottom &&
            motionRadius == normalizedRadius
        ) {
            return
        }
        motionBounds.set(left, top, right, bottom)
        motionRadius = normalizedRadius
        shaped = true
        // 表面 drawable 的绘制几何跟随形变矩形：挂在承载层上的 background 默认按
        // 全屏 View bounds 绘制，模态描边/顶沿高光会绕窗口矩形计算再被 outline
        // 裁掉——形变全程卡片上看不到边缘光，落定交还卡片自身 drawable 才出现
        // （"通透效果等动画播完才加载"的割裂）。Liquid 经 provider 读同一份
        // 形变边界，Material 的 Drawable 读 bounds，两条通道同步同一矩形。
        background?.setBounds(
            floor(motionBounds.left).toInt(), floor(motionBounds.top).toInt(),
            ceil(motionBounds.right).toInt(), ceil(motionBounds.bottom).toInt()
        )
        if (usesPersistentSurface) {
            // 表面直接画当前尺寸的圆角和描边，不能再被父层 outline 二次裁切。
            // 但 outline 仍要逐帧刷新——本层的 elevation 投影形状就是它。
            clipToOutline = false
            contentClip.rewind()
            contentClip.addRoundRect(motionBounds, motionRadius, motionRadius, Path.Direction.CW)
            persistentSurface?.updateFrame(motionBounds, motionRadius, 1f)
            invalidateOutline()
            invalidate()
        } else {
            clipToOutline = true
            invalidateOutline()
        }
    }

    /**
     * 回到稳定端：关闭正文裁剪。普通弹窗交还卡片背景，覆盖式面板保留同一个表面。
     *
     * 必须显式清掉 outline，否则最后一帧的圆角会永久留在层上，卡片内容一旦超出该矩形
     * （例如展开的搜索结果列表）就会被裁掉。
     */
    fun clearShape() {
        if (!shaped && !clipToOutline) {
            updateRestingSurface()
            return
        }
        shaped = false
        clipToOutline = false
        motionBounds.setEmpty()
        motionRadius = 0f
        contentClip.rewind()
        // 形变矩形已摘下：表面 drawable 的几何回归 View bounds（若有残留 background）。
        background?.setBounds(0, 0, width, height)
        updateRestingSurface()
        invalidateOutline()
        invalidate()
    }

    private fun updateRestingSurface() {
        val surface = persistentSurface ?: return
        if (childCount < 2) return
        val card = getChildAt(1)
        motionBounds.set(card.left.toFloat(), card.top.toFloat(), card.right.toFloat(), card.bottom.toFloat())
        surface.updateFrame(motionBounds, surfaceRadiusPx, 1f)
        // 落定矩形同时是投影轮廓：卡片重排版后阴影跟着走，不留旧形。
        invalidateOutline()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 稳定后正文高度仍可能改变；表面跟随真实布局，不保留旧动画终点。
        if (!shaped) updateRestingSurface()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (!usesPersistentSurface || child === persistentSurface || !shaped) {
            return super.drawChild(canvas, child, drawingTime)
        }
        val saved = canvas.save()
        return try {
            canvas.clipPath(contentClip)
            super.drawChild(canvas, child, drawingTime)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = blockInteraction

    // LiquidMotionSurfaceFrameProvider：Liquid 表面 drawable 在 draw 时读取形变矩形，
    // 描边、顶沿高光与光学采样原点都按运动中的卡片几何落位，而不是承载层全屏矩形。
    // 未成形（shaped=false）时返回空矩形，drawable 退回 View bounds 的正常路径。
    override fun copyLiquidMotionBounds(outBounds: RectF) {
        if (shaped) outBounds.set(motionBounds) else outBounds.setEmpty()
    }

    override fun liquidMotionCornerRadiusPx(): Float =
        if (shaped) motionRadius else surfaceRadiusPx

    override fun liquidMotionFallbackColor(): Int = fallbackColor
}
