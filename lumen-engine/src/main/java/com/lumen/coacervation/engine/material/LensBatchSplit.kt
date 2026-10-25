package com.lumen.coacervation.engine.material

/**
 * 透镜批次的并行拆分（纯策略，JVM 单测）：按录制分组，把较轻的一半交给第二条透镜线程。
 *
 * 同一份录制（[groups] 中引用相同的元素）的作业不拆开：组录制只能在一条线程上回放。
 * 组数不足两组、或处理器少于 [MIN_PROCESSORS] 时不拆分，避免与 UI/RenderThread 争核。
 */
internal object LensBatchSplit {
    const val MIN_PROCESSORS = 4

    /**
     * 进程内只查一次：`availableProcessors` 每次都要读 sysfs（2026-10-25 API 35 实测 1.44µs/次，
     * 在 pre-draw 采集里占约 8%），而设备配置的核数在进程生命周期内不变。
     */
    val deviceProcessors: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { Runtime.getRuntime().availableProcessors() }

    /**
     * @param groups 每个作业的录制对象，按引用相等分组
     * @param costs 每个作业的估计开销（采样图像素数），与 [groups] 一一对应
     * @return 放到第二条线程的作业下标（升序）；不拆分时为 null
     */
    fun secondHalf(groups: List<Any>, costs: List<Long>, processors: Int): List<Int>? {
        require(groups.size == costs.size)
        if (processors < MIN_PROCESSORS) return null
        val keys = ArrayList<Any>()
        val members = ArrayList<MutableList<Int>>()
        val weights = ArrayList<Long>()
        groups.forEachIndexed { index, group ->
            var key = keys.indexOfFirst { it === group }
            if (key < 0) {
                keys += group; members += ArrayList<Int>(); weights += 0L
                key = keys.lastIndex
            }
            members[key] += index
            weights[key] = weights[key] + costs[index]
        }
        if (keys.size < 2) return null
        var first = 0L
        var second = 0L
        val offloaded = ArrayList<Int>()
        // 最重的组先放第一条线程，其余依次放进当前较轻的一边。
        for (key in keys.indices.sortedByDescending { weights[it] }) {
            if (first <= second) first += weights[key]
            else { second += weights[key]; offloaded += members[key] }
        }
        return offloaded.sorted()
    }
}
