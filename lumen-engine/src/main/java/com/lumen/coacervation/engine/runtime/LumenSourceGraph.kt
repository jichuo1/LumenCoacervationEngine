package com.lumen.coacervation.engine.runtime

/** Binding-time validation; nothing traverses this graph from a draw callback. */
internal object LumenSourceGraph {
    enum class Result { VALID, CYCLE, MISSING_DEPENDENCY, BUDGET_EXCEEDED }
    fun validate(nodes: Map<Long,LongArray>,limit: Int=32): Result {
        if(nodes.size>limit||nodes.values.sumOf {it.size}>limit*limit)return Result.BUDGET_EXCEEDED
        val visiting=HashSet<Long>();val done=HashSet<Long>()
        fun visit(id: Long): Result {
            if(id in done)return Result.VALID
            if(!visiting.add(id))return Result.CYCLE
            val edges=nodes[id]?:return Result.MISSING_DEPENDENCY
            for(next in edges){val result=visit(next);if(result!=Result.VALID)return result}
            visiting.remove(id);done.add(id);return Result.VALID
        }
        for(id in nodes.keys){val result=visit(id);if(result!=Result.VALID)return result}
        return Result.VALID
    }
}
