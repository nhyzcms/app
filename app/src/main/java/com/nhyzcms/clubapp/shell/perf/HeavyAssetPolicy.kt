package com.nhyzcms.clubapp.shell.perf

/**
 * 「大而不必要」的静态资源判定（**纯逻辑**，不 import android.*，可在 JVM 单测）。
 *
 * ## 背景（用户明确要求 + 实测数据）
 * 用户要求「App 上不展示 live2d」。看板娘素材（模型 + 4096 纹理）在服务器上约 60 MB，
 * 而服务器只有 3 M 带宽、无 CDN —— 一旦下载，代价是几十秒的卡顿与几十 MB 流量。
 *
 * 实测（2026-10-13，生产 access.log 按 UA 过滤）：App 会话里
 * **没有任何 `/static/live2d/` 请求** —— 网站侧已按「窗口宽度 ≤ 1024 px 不挂载看板娘」挡住。
 * 所以本策略是**兜底**：万一网站改了规则、或个别 ROM 上报的视口宽度偏大，
 * 壳也不会去下这些素材。开关 `blockLive2dAssets`（默认开）可远程关掉。
 *
 * ## 只有"子资源"会被拦
 * 主文档**永远**不拦（拦它等于白屏）；命中拦截的请求返回「空体 200」，
 * 网页看起来只是"这个文件不存在"，会自然降级，而不是报网络错误。
 */
object HeavyAssetPolicy {

    /**
     * 是否拦截这次请求。
     *
     * 四条硬约束，任何一条不满足都返回 false（＝放行）：
     *   ① [enabled] 为真（清单开关）；
     *   ② **不是主文档**（[forMainFrame] = false）；
     *   ③ 与站点**同源**：必须严格以 `siteOrigin + "/"` 开头
     *      （防止 `8.153.145.175.evil.example` 这类前缀陷阱）；
     *   ④ 路径命中 [prefixes] 之一（前缀从 [com.nhyzcms.clubapp.shell.AppConfig] 传入，不在本文件写死）。
     */
    fun shouldBlock(
        url: String?,
        forMainFrame: Boolean,
        siteOrigin: String,
        prefixes: List<String>,
        enabled: Boolean,
    ): Boolean {
        if (!enabled || forMainFrame) return false
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return false
        val origin = siteOrigin.trim().trimEnd('/')
        if (origin.isEmpty() || !text.startsWith(origin + "/")) return false
        val path = text.substring(origin.length).substringBefore('?').substringBefore('#')
        return prefixes.any { prefix -> prefix.isNotEmpty() && path.startsWith(prefix) }
    }

    /** 拦截后的应答 MIME：给网页一个"没有这个文件"的空体，让它自然降级。 */
    const val EMPTY_MIME = "text/plain"

    /** 空体应答的缓存秒数：别让网页每次会话都反复问同一个不存在的文件。 */
    const val EMPTY_CACHE_SECONDS = 600L
}
