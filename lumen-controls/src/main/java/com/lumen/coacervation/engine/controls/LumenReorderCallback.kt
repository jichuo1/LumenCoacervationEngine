package com.lumen.coacervation.engine.controls

import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlin.math.hypot

/**
 * 长按拖拽排序（适配标准 §12.5、§14.5）：长按拾起一行（轻触觉 + 1.03 放大 + 0.9 透明），拖动时其余行逐级补位，
 * 松手按拖动距离决定落位滑行时长，抬起时只提交一次。从来源工程「管理常用」面板抽出。
 *
 * 用法：`ItemTouchHelper(LumenReorderCallback(...)).attachToRecyclerView(list)`。
 *
 * 宿主必须遵守：**拖动中途不要向适配器提交新数据**——`ItemTouchHelper` 正持有被拖行的索引，外部提交会把顺序
 * 弹回去；拖动期间只在 [onMove] 里同步本地顺序，松手后在 [onDrop] 里一次性落库。另外建议关掉默认的 change
 * 交叉淡化（`SimpleItemAnimator.supportsChangeAnimations = false`），让行内状态自己渐变、不整行闪一下。
 *
 * @param canDrag 这一行能不能被拾起（例如过滤视图下顺序与存储顺序不同构时整体禁用）。
 * @param onMove 交换两行；返回 false 拒绝（例如越出可排序的前缀）。宿主在这里移动适配器数据并 `notifyItemMoved`。
 * @param onDrop 松手：参数是被拖行最终的位置（拿不到时为 -1）。宿主在这里提交一次排序。
 * @param directions 允许的拖动方向（`ItemTouchHelper.UP` 等的组合）；null 表示按布局自动判断（适配标准 §14.5）：
 *   网格与瀑布流四个方向，横向列表左右，竖向列表上下。
 */
public class LumenReorderCallback @JvmOverloads constructor(
    private val canDrag: (position: Int) -> Boolean,
    private val onMove: (from: Int, to: Int) -> Boolean,
    private val onDrop: (finalPosition: Int) -> Unit,
    private val directions: Int? = null
) : ItemTouchHelper.Callback() {

    /** 当前是否有行正被拖动（宿主据此暂停外部数据提交）。 */
    public var isDragging: Boolean = false
        private set

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val position = viewHolder.bindingAdapterPosition
        val flags = if (position >= 0 && canDrag(position)) directions ?: directionsFor(recyclerView.layoutManager) else 0
        return makeMovementFlags(flags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        val from = viewHolder.bindingAdapterPosition
        val to = target.bindingAdapterPosition
        if (from < 0 || to < 0) return false
        return onMove(from, to)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
            isDragging = true
            viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // 放大量按卡片尺寸封顶：大卡片（网格大方块、横幅）按比例放大 3% 会鼓出一大圈。
            val view = viewHolder.itemView
            val lift = liftScale(maxOf(view.width, view.height), view.resources.displayMetrics.density)
            view.animate()
                .scaleX(lift).scaleY(lift).alpha(LIFT_ALPHA)
                .setInterpolator(settleEase)
                .setDuration(LIFT_MS).start()
        }
    }

    /**
     * 松手回位滑行时长随距离走：默认实现只返回固定的 moveDuration，长拖后的落位在同样的时长里"撞回去"显得生硬；
     * 给长距离留足缓冲、短距离不拖沓。
     */
    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float
    ): Long {
        if (animationType != ItemTouchHelper.ANIMATION_TYPE_DRAG) {
            return super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
        }
        return dropSettleDurationMs(hypot(animateDx, animateDy))
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(settleEase)
            .setDuration(DROP_RESTORE_MS).start()
        isDragging = false
        onDrop(viewHolder.bindingAdapterPosition)
    }

    override fun isLongPressDragEnabled(): Boolean = true
    override fun isItemViewSwipeEnabled(): Boolean = false
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int): Unit = Unit

    public companion object {
        /** 拾起/放下的缓出曲线：快起缓落，落位末尾是减速而不是匀速急停。 */
        private val settleEase = PathInterpolator(0.2f, 0f, 0f, 1f)
        public const val LIFT_SCALE: Float = 1.03f
        /** 拾起时最多长大这么多（dp，按长边算）。 */
        public const val LIFT_GROWTH_CAP_DP: Float = 8f
        public const val LIFT_ALPHA: Float = 0.9f
        public const val LIFT_MS: Long = 140L
        public const val DROP_RESTORE_MS: Long = 220L
        /** 落位滑行：最短 220ms，随距离每像素 +0.16ms，封顶 420ms。 */
        public const val DROP_SETTLE_MIN_MS: Long = 220L
        public const val DROP_SETTLE_PER_PX_MS: Float = 0.16f
        public const val DROP_SETTLE_MAX_MS: Long = 420L

        /** 拾起时的放大倍率：一般行 1.03；长边超过约 267dp 的大卡片按"最多长大 8dp"封顶。 */
        @JvmStatic
        public fun liftScale(longSidePx: Int, density: Float): Float {
            if (longSidePx <= 0 || !density.isFinite() || density <= 0f) return LIFT_SCALE
            return minOf(LIFT_SCALE, 1f + LIFT_GROWTH_CAP_DP * density / longSidePx)
        }

        /** 按布局判断允许的拖动方向：网格/瀑布流（多列）四向，横向列表左右，其余上下。 */
        @JvmStatic
        public fun directionsFor(layoutManager: RecyclerView.LayoutManager?): Int = when {
            layoutManager is GridLayoutManager && layoutManager.spanCount > 1 -> ALL_DIRECTIONS
            layoutManager is StaggeredGridLayoutManager && layoutManager.spanCount > 1 -> ALL_DIRECTIONS
            layoutManager is LinearLayoutManager && layoutManager.orientation == RecyclerView.HORIZONTAL ->
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            layoutManager is StaggeredGridLayoutManager && layoutManager.orientation == RecyclerView.HORIZONTAL ->
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            else -> ItemTouchHelper.UP or ItemTouchHelper.DOWN
        }

        private const val ALL_DIRECTIONS =
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT

        /** 落位滑行时长（纯函数，便于测试）。 */
        @JvmStatic
        public fun dropSettleDurationMs(distancePx: Float): Long =
            (DROP_SETTLE_MIN_MS + (distancePx.coerceAtLeast(0f) * DROP_SETTLE_PER_PX_MS).toLong())
                .coerceAtMost(DROP_SETTLE_MAX_MS)
    }
}
