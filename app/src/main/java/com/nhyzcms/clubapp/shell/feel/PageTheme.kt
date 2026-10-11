package com.nhyzcms.clubapp.shell.feel

import com.nhyzcms.clubapp.shell.update.MiniJson

/**
 * 网页主题 → 壳配色（纯逻辑，可 JVM 单测）。
 *
 * 为什么壳要去读网页的主题色：站点自己在 localStorage 里存深浅色偏好（键 `cobalt:theme`，
 * 默认浅色），系统的深色设置与网页实际配色可能不一致。若状态栏只跟着系统走，
 * 就会出现「网页是浅色底 + 状态栏是深色」这种突兀组合，正是区块 4 验收点里说的「刺眼」。
 * 因此这里**以网页声明的 `meta[name=theme-color]` 为准**（站点会随主题改写它：
 * 浅色 `#ffffff`、深色 `#080f17`，见网站仓库 frontend/src/theme.ts），
 * 探测不到时才回退到系统深浅色。
 *
 * 纯逻辑边界：本文件不 import 任何 android.*，颜色解析、亮度判定、配色决策
 * 全部可以在 JVM 上单测（见 app/src/test/java/com/nhyzcms/clubapp/shell/feel/）。
 */
object PageTheme {

    /** 站点浅色底（模板 index.html 里 `meta[name=theme-color]` 的默认值）。 */
    const val FALLBACK_LIGHT_COLOR: Int = 0xFFFFFFFF.toInt()

    /** 站点深色底（网站仓库 frontend/src/theme.ts 的 THEME_COLORS.dark = #080f17）。 */
    const val FALLBACK_DARK_COLOR: Int = 0xFF080F17.toInt()

    /** 相对亮度阈值：亮于此值的底色，状态栏图标要用深色。 */
    const val LIGHT_BACKGROUND_THRESHOLD: Double = 0.5

    /** 一次探测的结果：网页主题色（拿不到为 null）+ 页面滚动位置。 */
    data class Probe(val color: Int?, val scrollY: Int)

    /** 壳的配色方案。 */
    data class Palette(
        val statusBarColor: Int,
        val windowBackgroundColor: Int,
        val lightStatusBarIcons: Boolean,
    )

    /**
     * 解析 `#RGB` / `#RRGGBB` / `#AARRGGBB`（`#` 可省略，大小写不敏感）。
     *
     * 非法输入一律返回 null：网页/清单里的任何奇怪字符串都不能让壳崩掉，
     * 解析不出来就退回 [FALLBACK_LIGHT_COLOR] / [FALLBACK_DARK_COLOR]。
     */
    fun parseColor(raw: String?): Int? {
        val text = raw?.trim()?.removePrefix("#")?.trim().orEmpty()
        val expanded = when (text.length) {
            3 -> buildString {
                for (ch in text) {
                    append(ch).append(ch)
                }
            }
            6, 8 -> text
            else -> return null
        }
        if (!expanded.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val value = expanded.toLongOrNull(16) ?: return null
        return if (expanded.length == 6) (0xFF000000L or value).toInt() else value.toInt()
    }

    /**
     * sRGB 相对亮度（0 = 黑，1 = 白），用于决定状态栏图标该用深色还是浅色。
     *
     * 用 WCAG 的线性化公式而不是「(r+g+b)/3」：人眼对绿色最敏感，
     * 简单的算术平均会把深绿、深蓝判成亮色，导致状态栏图标看不清。
     */
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val srgb = (value and 0xFF) / 255.0
            return if (srgb <= 0.03928) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        val red = channel((color shr 16) and 0xFF)
        val green = channel((color shr 8) and 0xFF)
        val blue = channel(color and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    /** 底色是否属于「亮色」（亮色底 → 状态栏图标用深色）。 */
    fun isLightBackground(color: Int): Boolean = luminance(color) >= LIGHT_BACKGROUND_THRESHOLD

    /**
     * 配色决策：网页给得出主题色就用它；给不出就按系统深浅色兜底。
     *
     * 状态栏色 = 窗口底色 = 网页底色，这样状态栏与网页顶栏（半透明毛玻璃）
     * 在视觉上是连成一片的，没有色带。
     */
    fun decide(webColor: Int?, systemDark: Boolean): Palette {
        val background = webColor ?: if (systemDark) FALLBACK_DARK_COLOR else FALLBACK_LIGHT_COLOR
        return Palette(
            statusBarColor = background,
            windowBackgroundColor = background,
            lightStatusBarIcons = isLightBackground(background),
        )
    }

    /**
     * 解析 [com.nhyzcms.clubapp.shell.feel.PageStateProbe] 的返回值。
     *
     * `WebView.evaluateJavascript` 会把 JS 的返回值**再 JSON 化一次**，
     * 而我们的脚本本身返回 `JSON.stringify([color, scrollY])`，
     * 所以这里要解两层：外层字符串 → 内层数组。
     *
     * 任何一层解不出来都返回「没有主题色、滚动 0」，绝不抛异常。
     */
    fun parseProbeResult(rawJsValue: String?): Probe {
        val inner = MiniJson.parse(rawJsValue) as? String ?: return Probe(null, 0)
        val values = MiniJson.parse(inner) as? List<*> ?: return Probe(null, 0)
        val color = parseColor(values.getOrNull(0) as? String)
        val scrollY = (values.getOrNull(1) as? Double)
            ?.takeIf { it >= 0.0 && it <= Int.MAX_VALUE.toDouble() }
            ?.toInt()
            ?: 0
        return Probe(color, scrollY)
    }
}
