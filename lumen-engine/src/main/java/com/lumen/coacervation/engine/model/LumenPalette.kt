package com.lumen.coacervation.engine.model

/**
 * 引擎消费的配色：宿主把自己的主题（Material You 动态取色、品牌色、固定配色……）换算成这 9 个颜色后交给引擎。
 *
 * 引擎**不取色**：从壁纸或种子色生成调色板属于宿主的主题系统，引擎只读取这里的数值。
 * 全部是 ARGB 整数。
 *
 * @property primary 强调色：选中态、高光、涟漪、描边。
 * @property onPrimary 强调色上的前景色。
 * @property secondary 次强调色。
 * @property tertiary 第三强调色。
 * @property surface 表面色：卡片、按钮、悬浮栏的材质底色。
 * @property background 窗口背景色：环境底图、深浅判定都以它为准。
 * @property surfaceVariant 表面变体：输入框、选中项等强调区域。
 * @property textPrimary 主文字色（标题、正文、未选中控件的前景）。
 * @property textSecondary 次要文字色（说明、按钮文字、禁用态的基准）。
 */
public data class LumenPalette(
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val tertiary: Int,
    val surface: Int,
    val background: Int,
    val surfaceVariant: Int,
    val textPrimary: Int,
    val textSecondary: Int
) {
    public companion object {
        /**
         * 推荐的"中性表面 + 强调色"组合：强调色取宿主主题，大面积表面保持中性、保证可读。
         * 这是引擎来源工程（Bilibili Innocent Lab）使用的配色策略，数值原样保留。
         */
        public fun modern(
            primary: Int,
            onPrimary: Int,
            secondary: Int,
            tertiary: Int,
            dark: Boolean
        ): LumenPalette = LumenPalette(
            primary = primary,
            onPrimary = onPrimary,
            secondary = secondary,
            tertiary = tertiary,
            background = if (dark) 0xFF101114.toInt() else 0xFFF3F2F6.toInt(),
            surface = if (dark) 0xFF24252A.toInt() else 0xFFFCFBFE.toInt(),
            surfaceVariant = if (dark) 0xFF292A30.toInt() else 0xFFF8F7FB.toInt(),
            textPrimary = if (dark) 0xFFD3D3D3.toInt() else 0xFF323B42.toInt(),
            textSecondary = if (dark) 0xFFCFCFCF.toInt() else 0xFF777777.toInt()
        )

        /** 没有主题系统时的中性灰阶调色板（强调色为中性灰）。 */
        public fun neutral(dark: Boolean): LumenPalette = modern(
            primary = if (dark) 0xFFC6C6CC.toInt() else 0xFF5D5E66.toInt(),
            onPrimary = if (dark) 0xFF2F3036.toInt() else 0xFFFFFFFF.toInt(),
            secondary = if (dark) 0xFFC5C6D0.toInt() else 0xFF5C5E67.toInt(),
            tertiary = if (dark) 0xFFE2BCC4.toInt() else 0xFF745158.toInt(),
            dark = dark
        )
    }
}
