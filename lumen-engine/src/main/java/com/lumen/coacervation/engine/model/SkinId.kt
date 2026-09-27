package com.lumen.coacervation.engine.model

/**
 * 可持久化的界面皮肤标识。
 *
 * [storageValue] 是偏好协议的一部分，不跟随 Kotlin 枚举名重命名。新增皮肤必须分配新的、
 * 永不复用的稳定值。
 */
public enum class SkinId(public val storageValue: String) {
    MATERIAL_YOU("material_you"),
    LIQUID("liquid_v1");

    public companion object {
        /** 严格解析持久化值；未知值不会做大小写或别名兼容。 */
        public fun fromStorageValue(value: String?): SkinId? =
            entries.firstOrNull { it.storageValue == value }
    }
}
