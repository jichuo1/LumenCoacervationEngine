package com.lumen.coacervation.engine.liquid

/**
 * 标记"玻璃背后只有背景、没有内容透出"的 Activity（二级页：设置备份、统一功能诊断）。
 *
 * 这类页面的卡片只压在静态背景上，实时截图折射出来的仍是背景本身，没有视觉收益；反而在
 * 形变动画结束后约 0.5s（抑制解除 160–320ms + 截图后处理约 250ms）把采样源从稳定底图换成
 * 截图，整页控件光影跳变一次（2026-09-24 用户报告）。实现本接口的 Activity 保留实时档的
 * 光学参数，但从不发起 PixelCopy，玻璃始终采稳定底图。
 */
public interface LiquidStaticBackdropHost
