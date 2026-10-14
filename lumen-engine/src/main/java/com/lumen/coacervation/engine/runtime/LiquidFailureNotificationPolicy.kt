package com.lumen.coacervation.engine.runtime

import com.lumen.coacervation.engine.model.SkinId

/** 本会话已成功保存高级材质失败的回退，宿主才可收到可能触发 recreate 的通知。 */
internal object LiquidFailureNotificationPolicy {
    fun canNotify(result: SkinStateMutationResult?): Boolean =
        result != null &&
            result.persisted &&
            result.reason == SkinRecoveryReason.LIQUID_VALIDATION_FAILED &&
            result.state.selectedSkin == SkinId.MATERIAL_YOU
}
