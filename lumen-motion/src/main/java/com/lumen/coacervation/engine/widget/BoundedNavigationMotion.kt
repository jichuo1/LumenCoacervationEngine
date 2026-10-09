package com.lumen.coacervation.engine.widget

/** 宿主声明的条目上限；几何仍共用 NavigationBarMotion，不改变默认控件的容量。 */
public open class BoundedNavigationMotion(public val MAX_ITEMS: Int) {
    init { require(MAX_ITEMS > 0) }
    public val BAR_HEIGHT_DP: Int get() = NavigationBarMotion.BAR_HEIGHT_DP
    public val INSET_DP: Int get() = NavigationBarMotion.INSET_DP
    public val MAX_TRAVEL_DP: Float get() = NavigationBarMotion.MAX_TRAVEL_DP
    public val SPRING_DURATION_MS: Long get() = NavigationBarMotion.SPRING_DURATION_MS

    public fun position(value: Float, count: Int): Float =
        NavigationBarMotion.position(value, count.coerceIn(1, MAX_ITEMS))
    public fun nearestPage(value: Float, count: Int): Int =
        NavigationBarMotion.nearestPage(value, count.coerceIn(1, MAX_ITEMS))
    public fun physicalSlot(value: Float, count: Int, rtl: Boolean): Float =
        NavigationBarMotion.physicalSlot(value, count.coerceIn(1, MAX_ITEMS), rtl)
    public fun indexAt(x: Float, width: Float, inset: Float, count: Int, rtl: Boolean): Int =
        if (count !in 1..MAX_ITEMS) -1 else NavigationBarMotion.indexAt(x, width, inset, count, rtl)
    public fun scrubPosition(start: Float, distance: Float, slot: Float, count: Int, rtl: Boolean): Float =
        NavigationBarMotion.scrubPosition(start, distance, slot, count.coerceIn(1, MAX_ITEMS), rtl)
    public fun displacementScale(dx: Float, dy: Float, limit: Float, resistance: Float): Float =
        NavigationBarMotion.displacementScale(dx, dy, limit, resistance)
    public fun travelClampScale(x: Float, y: Float, limit: Float): Float =
        NavigationBarMotion.travelClampScale(x, y, limit)
    public fun lensScaleX(press: Float): Float = NavigationBarMotion.lensScaleX(press)
    public fun lensScaleY(press: Float): Float = NavigationBarMotion.lensScaleY(press)
}
