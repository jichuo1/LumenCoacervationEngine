package com.lumen.coacervation.engine.material

/**
 * 透镜采集的录制分组：采样区两两重叠的表面收进同一组，一组只遍历一次视图树。
 *
 * 分组是"重叠"关系的连通分量（A 叠 B、B 叠 C ⇒ 三者一组）。表面数只有个位数，用并查集即可。
 * 纯函数，不引用 Android 类型，JVM 可测。
 *
 * 输出顺序稳定：组按最小成员下标排序，组内升序——同一批表面每帧得到同一套分组，
 * 录制池里的第 k 个 Picture 总是给同一组用。
 */
internal object LensCaptureGrouping {
    fun group(count: Int, overlaps: (Int, Int) -> Boolean): List<IntArray> {
        if (count <= 0) return emptyList()
        val parent = IntArray(count) { it }
        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var node = index
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }
        for (a in 0 until count) {
            for (b in a + 1 until count) {
                if (!overlaps(a, b)) continue
                val rootA = find(a)
                val rootB = find(b)
                if (rootA != rootB) parent[maxOf(rootA, rootB)] = minOf(rootA, rootB)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (index in 0 until count) groups.getOrPut(find(index)) { ArrayList() } += index
        return groups.values.map(MutableList<Int>::toIntArray)
    }
}
