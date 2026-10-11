package com.nhyzcms.clubapp.shell

import android.net.Uri
import java.util.Locale

/**
 * URL 的可测试投影：只保留导航裁决需要的字段。
 *
 * 存在的意义：安卓框架类（Uri）在普通 JVM 单测里不可用，
 * 把裁决逻辑限定在纯数据上，区块 9 就能直接写不依赖设备的单测。
 */
data class UrlParts(val scheme: String, val host: String, val raw: String) {
    companion object {
        /** 归一化：scheme 与 host 一律小写（Android 的 Uri 已小写 host，但不能假设）。 */
        fun of(scheme: String?, host: String?, raw: String): UrlParts = UrlParts(
            scheme = scheme.orEmpty().lowercase(Locale.ROOT),
            host = host.orEmpty().lowercase(Locale.ROOT),
            raw = raw,
        )

        fun of(uri: Uri): UrlParts = of(uri.scheme, uri.host, uri.toString())
    }
}

/** 导航裁决结果。 */
sealed interface NavDecision {
    /** 在壳内加载该地址。 */
    data class InApp(val url: String) : NavDecision

    /** 交给系统浏览器/对应 App。 */
    data class External(val url: String) : NavDecision

    /** 直接拦截（未知 scheme、明文、畸形地址）。 */
    data class Blocked(val url: String, val reason: String) : NavDecision
}

/**
 * 导航策略（纯函数，无 Android 依赖）。
 *
 * 规则与理由见 docs/SECURITY.md §3：
 *   https + 白名单主机 → 壳内
 *   其它 https          → 系统浏览器
 *   同站 http           → 拦截（绝不发明文请求）
 *   系统 scheme         → 交给系统
 *   其余一切            → 拦截
 */
object NavPolicy {

    /** 生产环境恒为 false。仅供将来本地调试时临时放开（需同步改测试）。 */
    const val ALLOW_CLEARTEXT = false

    const val REASON_CLEARTEXT = "cleartext"
    const val REASON_MALFORMED = "malformed"
    const val REASON_UNKNOWN_SCHEME = "unknown-scheme"

    fun decide(parts: UrlParts): NavDecision {
        if (parts.raw.isBlank() || parts.scheme.isEmpty()) {
            return NavDecision.Blocked(parts.raw, REASON_MALFORMED)
        }
        return when (parts.scheme) {
            "https" ->
                if (isSameSite(parts)) NavDecision.InApp(parts.raw)
                else NavDecision.External(parts.raw)

            "http" ->
                if (!isSameSite(parts)) NavDecision.External(parts.raw)
                else if (ALLOW_CLEARTEXT) NavDecision.InApp(parts.raw)
                else NavDecision.Blocked(parts.raw, REASON_CLEARTEXT)

            else ->
                if (parts.scheme in AppConfig.SYSTEM_SCHEMES) NavDecision.External(parts.raw)
                else NavDecision.Blocked(parts.raw, REASON_UNKNOWN_SCHEME + ":" + parts.scheme)
        }
    }

    fun isSameSite(parts: UrlParts): Boolean = parts.host in AppConfig.ALLOWED_HOSTS
}
