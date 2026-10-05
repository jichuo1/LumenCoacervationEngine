package com.lumen.coacervation.engine.interaction

/** 长按拖动的行程边界；在每次按下时固定，本次手势中不再改变。 */
public enum class ElasticTravelPolicy {
    /** 默认：限制在父容器内，同时避让同层兄弟，保留拉伸余量。 */
    AVOID_NEIGHBORS,
    /** 只使用父容器边界与最低行程预算，适用于需要保留原列表交叠效果的宿主。 */
    PARENT_BOUNDS
}
