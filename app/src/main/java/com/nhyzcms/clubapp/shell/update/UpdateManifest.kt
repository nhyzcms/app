package com.nhyzcms.clubapp.shell.update

/** 清单里的公告横幅（字段契约见 docs/APP-MANIFEST.md §2）。 */
data class Notice(
    val id: String,
    val text: String,
    val level: String,
    val url: String?,
    val dismissible: Boolean,
)

/** 最新 APK 的分发信息。**这里的值还未经校验**，能不能用由 [UpdatePolicy] 判定。 */
data class ApkInfo(
    val url: String,
    val sha256: String,
    val size: Long,
)

/** 清单里的最新版本。 */
data class LatestRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val mandatory: Boolean,
    val apk: ApkInfo?,
)

/** 被处置的壳版本：action = block 直接停用，warn 只提示。 */
data class BlockedVersion(
    val versionCode: Int,
    val action: String,
    val message: String?,
)

/**
 * L1 清单的解析结果。
 *
 * 注意：本类只保证「JSON 结构与类型对得上」，**不做安全判定**——
 * URL 是否属于本站、sha256 是否合法、起始路由能不能用，全部由 [UpdatePolicy] 判定，
 * 这样安全规则可以脱离 Android 单独单测。
 */
data class UpdateManifest(
    val schema: Int,
    val generatedAt: String?,
    val startRoute: String?,
    val flags: Map<String, Boolean>,
    val notice: Notice?,
    val minShellVersionCode: Int,
    val latest: LatestRelease?,
    val blockedVersions: List<BlockedVersion>,
    val updateSources: List<String>,
) {
    companion object {
        /** 当前清单格式版本（壳认识的最高版本）。 */
        const val SUPPORTED_SCHEMA = 1

        /**
         * 内置默认开关，与 docs/APP-MANIFEST.md §3 的表逐字一致。
         * 键名一旦发布就不要再改名（旧壳读不到新键会退回默认值，但没有默认值的键会变成 false）。
         */
        val DEFAULT_FLAGS: Map<String, Boolean> = mapOf(
            "live2d" to false,
            "panorama" to true,
            "autoUpdateCheck" to true,
            "externalLinkInBrowser" to true,
            "forceRefreshEntry" to false,
            // 区块 4（原生手感）
            "pullToRefresh" to true,
            "showBackToTop" to true,
            // 铺满状态栏（沉浸式）：默认关闭，原因见 shell/feel/EdgeToEdgePolicy 的注释
            "edgeToEdge" to false,
            // 区块 6（弱网与性能）：壳侧拦截 Live2D 素材（约 60 MB）。
            // 网站侧已按视口宽度不挂载看板娘，这层是兜底；远程置 false 可关掉拦截。
            "blockLive2dAssets" to true,
            // 区块 8（原生扫码）：右下角「扫码核验」按钮。
            // 置 false 即整颗按钮消失（原生扫码页也就不再可达），不发版就能撤。
            "showScanEntry" to true,
        )
    }
}

/**
 * 把 JSON 文本转成 [UpdateManifest]。
 *
 * 容错原则：只有「顶层不是对象」才算整体失败（返回 null，壳回退到内置默认值）；
 * 单个字段类型不对就丢掉那一个字段，不影响其余部分——一份写坏的清单绝不能让 App 不可用。
 */
object UpdateManifestCodec {

    fun parse(text: String?): UpdateManifest? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        return UpdateManifest(
            schema = intOf(root["schema"]) ?: UpdateManifest.SUPPORTED_SCHEMA,
            generatedAt = stringOf(root["generatedAt"]),
            startRoute = stringOf(root["startRoute"]),
            flags = flagsOf(root["flags"]),
            notice = noticeOf(root["notice"]),
            minShellVersionCode = intOf(root["minShellVersionCode"]) ?: 0,
            latest = latestOf(root["latest"]),
            blockedVersions = blockedOf(root["blockedVersions"]),
            updateSources = stringListOf(root["updateSources"]),
        )
    }

    /** 清单版本是否在壳能理解的范围内（更高的版本也不拒绝，只是忽略不认识的字段）。 */
    fun isSchemaSupported(schema: Int): Boolean = schema >= 1

    private fun stringOf(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun intOf(value: Any?): Int? {
        val number = value as? Double ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        if (number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) return null
        return number.toInt()
    }

    private fun longOf(value: Any?): Long? {
        val number = value as? Double ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        if (number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()) return null
        return number.toLong()
    }

    private fun flagsOf(value: Any?): Map<String, Boolean> {
        val map = value as? Map<*, *> ?: return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        for ((rawKey, rawValue) in map) {
            val name = rawKey as? String ?: continue
            val flag = rawValue as? Boolean ?: continue
            if (name.isNotBlank()) result[name] = flag
        }
        return result
    }

    private fun noticeOf(value: Any?): Notice? {
        val map = value as? Map<*, *> ?: return null
        val id = stringOf(map["id"]) ?: return null
        val text = stringOf(map["text"]) ?: return null
        return Notice(
            id = id,
            text = text,
            level = stringOf(map["level"]) ?: "info",
            url = stringOf(map["url"]),
            dismissible = map["dismissible"] as? Boolean ?: true,
        )
    }

    private fun latestOf(value: Any?): LatestRelease? {
        val map = value as? Map<*, *> ?: return null
        val versionCode = intOf(map["versionCode"]) ?: return null
        if (versionCode <= 0) return null
        return LatestRelease(
            versionCode = versionCode,
            versionName = stringOf(map["versionName"]) ?: versionCode.toString(),
            notes = stringOf(map["notes"]),
            mandatory = map["mandatory"] as? Boolean ?: false,
            apk = apkOf(map["apk"]),
        )
    }

    private fun apkOf(value: Any?): ApkInfo? {
        val map = value as? Map<*, *> ?: return null
        val url = stringOf(map["url"]) ?: return null
        val sha256 = stringOf(map["sha256"]) ?: return null
        return ApkInfo(
            url = url,
            sha256 = sha256.lowercase(),
            size = longOf(map["size"]) ?: 0L,
        )
    }

    private fun blockedOf(value: Any?): List<BlockedVersion> {
        val list = value as? List<*> ?: return emptyList()
        val result = ArrayList<BlockedVersion>()
        for (item in list) {
            val map = item as? Map<*, *> ?: continue
            val versionCode = intOf(map["versionCode"]) ?: continue
            result.add(
                BlockedVersion(
                    versionCode = versionCode,
                    action = stringOf(map["action"]) ?: "warn",
                    message = stringOf(map["message"]),
                ),
            )
        }
        return result
    }

    private fun stringListOf(value: Any?): List<String> {
        val list = value as? List<*> ?: return emptyList()
        val result = ArrayList<String>()
        for (item in list) {
            val text = item as? String ?: continue
            val trimmed = text.trim()
            if (trimmed.isNotEmpty()) result.add(trimmed)
        }
        return result
    }
}
