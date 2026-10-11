package com.nhyzcms.clubapp.shell.update

import java.net.URI
import java.util.Locale

/**
 * 更新策略（纯函数，可 JVM 单测）：路由校验、下载源白名单、摘要格式、版本决策。
 *
 * 这里是**安全边界**，所以规则写得比"够用"更严：
 *  - 起始路由只接受本站站内路由（以 `/#/` 开头的相对路径）；
 *  - APK 下载地址必须 https 且主机在白名单里，并且不允许带用户信息（user:pass@host）；
 *  - sha256 必须 64 位十六进制，否则宁可「不更新」也不安装来源不明的东西；
 *  - 清单里任何一项不合法都只是「忽略该项」，绝不抛异常。
 */
object UpdatePolicy {

    /** 站内路由前缀（哈希路由）。 */
    const val ROUTE_PREFIX = "/#/"

    /** 路由长度上限。 */
    const val MAX_ROUTE_LENGTH = 160

    /** sha256 十六进制串长度。 */
    const val SHA256_HEX_LENGTH = 64

    /** 低于这个体积的"APK"必然不是安装包（防止清单被写成一个小文件把我们骗去安装）。 */
    const val MIN_APK_BYTES: Long = 64L * 1024L

    /** 体积上限 200 MB：即使清单被投毒，也不会把手机磁盘写满。 */
    const val MAX_APK_BYTES: Long = 200L * 1024L * 1024L

    const val ACTION_BLOCK = "block"
    const val ACTION_WARN = "warn"

    /** 决策原因码（UI 据此选文案，不把中文文案写进逻辑层）。 */
    const val REASON_VERSION_BLOCKED = "version-blocked"
    const val REASON_SHELL_TOO_OLD = "shell-too-old"
    const val REASON_NO_DOWNLOAD_SOURCE = "no-download-source"

    /** 更新决策。 */
    sealed interface Decision {
        /** 什么都不做。 */
        object None : Decision

        /** 提示用户更新（[mandatory] = 不可跳过）。 */
        data class Update(
            val release: LatestRelease,
            val mandatory: Boolean,
            val urls: List<String>,
        ) : Decision

        /** 本机版本被停用，或壳太旧且拿不到可用下载源。 */
        data class Blocked(val reason: String, val message: String?) : Decision
    }

    /**
     * 校验起始路由：必须是站内哈希路由，否则退回 [fallback]。
     *
     * 这一项直接决定"App 启动后打开哪个页面"，如果放任外部字符串进来，
     * 就等于允许远端改掉壳的入口（甚至塞进外部地址）。
     */
    fun sanitizeStartRoute(raw: String?, fallback: String): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.length > MAX_ROUTE_LENGTH) return fallback
        if (!candidate.startsWith(ROUTE_PREFIX)) return fallback
        if (candidate.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return fallback
        if (candidate.contains('\\') || candidate.contains('@')) return fallback
        if (candidate.contains("://")) return fallback
        return candidate
    }

    /** sha256 必须是 64 位十六进制（大小写都接受）。 */
    fun isValidSha256(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.length != SHA256_HEX_LENGTH) return false
        return text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** 声明的体积是否像一份真实安装包（0 表示清单没写，按"未知"放行）。 */
    fun isPlausibleSize(size: Long): Boolean = size == 0L || size in MIN_APK_BYTES..MAX_APK_BYTES

    /** 只接受 https，且主机必须落在白名单里；不接受用户信息与空主机。 */
    fun isAllowedHttpsUrl(url: String?, allowedHosts: Set<String>): Boolean {
        val text = url?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_URL_LENGTH) return false
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host.isEmpty()) return false
        return allowedHosts.any { it.trim().lowercase(Locale.ROOT) == host }
    }

    /** 可在壳里直接打开的站内公告链接（相对路由）。 */
    fun sanitizeNoticeUrl(raw: String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.length > MAX_ROUTE_LENGTH) return null
        if (!candidate.startsWith(ROUTE_PREFIX)) return null
        if (candidate.any { it.isWhitespace() || it.code < 0x20 }) return null
        return candidate
    }

    /** 下载源列表：清单里写的 apk.url 优先，其后是 updateSources；全部过白名单校验并去重。 */
    fun downloadUrls(manifest: UpdateManifest, allowedHosts: Set<String>): List<String> {
        val result = LinkedHashSet<String>()
        val primary = manifest.latest?.apk?.url
        if (primary != null && isAllowedHttpsUrl(primary, allowedHosts)) result.add(primary.trim())
        for (source in manifest.updateSources) {
            if (isAllowedHttpsUrl(source, allowedHosts)) result.add(source.trim())
        }
        return result.toList()
    }

    /** 版本比较：1 = 远端更新，-1 = 本机更新，0 = 相同。 */
    fun compareVersions(local: Int, remote: Int): Int = when {
        remote > local -> 1
        remote < local -> -1
        else -> 0
    }

    /**
     * 主决策。优先级：被停用 > 强制更新 > 普通更新 > 不处理。
     *
     * @param localVersionCode 本机 BuildConfig.VERSION_CODE。
     * @param allowedHosts     允许的下载主机（唯一真源在 AppConfig.UPDATE_ALLOWED_HOSTS）。
     */
    fun decide(
        manifest: UpdateManifest,
        localVersionCode: Int,
        allowedHosts: Set<String>,
    ): Decision {
        val blocked = manifest.blockedVersions.firstOrNull {
            it.versionCode == localVersionCode && it.action.equals(ACTION_BLOCK, ignoreCase = true)
        }
        if (blocked != null) return Decision.Blocked(REASON_VERSION_BLOCKED, blocked.message)

        val release = manifest.latest
        val forced = manifest.minShellVersionCode > localVersionCode

        if (release == null || release.versionCode <= localVersionCode) {
            return if (forced) Decision.Blocked(REASON_SHELL_TOO_OLD, null) else Decision.None
        }

        val apk = release.apk
        val usable = apk != null &&
            isValidSha256(apk.sha256) &&
            isPlausibleSize(apk.size) &&
            isAllowedHttpsUrl(apk.url, allowedHosts)
        if (!usable) {
            // 没有可信的分发信息：非强制时静默忽略；强制时也得如实告诉用户"更新拿不到"。
            return if (forced || release.mandatory) {
                Decision.Blocked(REASON_NO_DOWNLOAD_SOURCE, null)
            } else {
                Decision.None
            }
        }

        val urls = downloadUrls(manifest, allowedHosts)
        if (urls.isEmpty()) {
            return if (forced || release.mandatory) {
                Decision.Blocked(REASON_NO_DOWNLOAD_SOURCE, null)
            } else {
                Decision.None
            }
        }

        return Decision.Update(release, mandatory = release.mandatory || forced, urls = urls)
    }

    /** 读开关：优先用清单里的值，缺失则用内置默认值。 */
    fun flag(flags: Map<String, Boolean>, name: String, default: Boolean): Boolean =
        flags[name] ?: default

    /** 合并内置默认开关与清单开关（清单里没写的键保持默认）。 */
    fun mergedFlags(manifest: UpdateManifest): Map<String, Boolean> =
        UpdateManifest.DEFAULT_FLAGS + manifest.flags

    /** 同一个公告只弹一次（按 id 去重）。 */
    fun shouldShowNotice(notice: Notice?, dismissedId: String?): Boolean {
        if (notice == null) return false
        return notice.id != dismissedId
    }

    /** 长评论/公告文本的展示上限，防止一份超大清单糊满屏幕。 */
    const val MAX_URL_LENGTH = 512
}
