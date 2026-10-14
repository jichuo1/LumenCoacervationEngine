package com.lumen.coacervation.engine.glow

import android.os.Build

/**
 * Android 12（API 31）合成带 RenderEffect 的节点时缓存滤镜结果：只有滤镜对象或局部裁剪变了才重算
 * （AOSP `RenderNode::updateSnapshotIfRequired`），节点内容变了也继续合成第一次的结果。12L（API 32）
 * 起把层的 generation 加进了这个判断。
 *
 * 在 API 31 上，引擎为每个带滤镜的节点预建一个像素等价的别名（零偏移包装），节点内容可能变化时在两者之间
 * 切换，让 HWUI 看到一个它没缓存过的滤镜；不在逐帧路径上新建 RenderEffect。其他版本不切换。
 * CI 的 API 31 模拟器（SE1A.220826.008）上复现过：模糊玻璃滚动后仍显示滚动前的内容。
 */
internal object FilteredLayerRefresh {
    fun required(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk == Build.VERSION_CODES.S
}
