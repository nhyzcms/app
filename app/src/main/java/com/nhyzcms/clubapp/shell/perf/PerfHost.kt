package com.nhyzcms.clubapp.shell.perf

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.StatFs
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.DiagLog
import com.nhyzcms.clubapp.shell.update.UpdatePolicy
import java.io.ByteArrayInputStream

/**
 * 「弱网与性能」宿主（区块 6）：设备分级、大资源拦截、缓存清理时机。
 *
 * 所有决策都在纯逻辑文件里（[DeviceClassifier] / [HeavyAssetPolicy] / [CacheTrimPolicy]），
 * 本类只做三件事：读系统事实、把决策落到 WebView、记一行轨迹。
 * 与 [com.nhyzcms.clubapp.shell.feel.FeelHost] 的分工：FeelHost 管"看护与手感"，
 * 本类管"省流量与省内存"。
 */
class PerfHost(
    private val context: Context,
    private val webView: WebView,
    /** 读清单开关（updates.flags()）。不缓存：清单可能被更新流程换掉。 */
    private val flags: () -> Map<String, Boolean>,
) {

    /** 设备档位与派生参数。构造时算一次：内存/存储不会在会话中途变档。 */
    val profile: PerfProfile = DeviceClassifier.profileOf(detectDeviceClass())

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 大资源拦截：命中白名单前缀时返回「空体 200」；返回 null 表示交给 WebView 正常处理。
     *
     * ⚠️ 本方法由 WebView 在**后台线程**调用（`shouldInterceptRequest` 的契约）：
     *   · 不许碰 WebView 实例、不许访问 WebSettings（AOSP 会直接抛异常，见 2026-10-12 闪退事故）；
     *   · 不许做网络 I/O —— 必须立刻返回，否则拖慢整页加载；
     *   只读共享配置（[AppConfig] 常量 + 清单开关快照）与纯逻辑判定。
     */
    fun interceptHeavyAsset(request: WebResourceRequest): WebResourceResponse? {
        val blocked = HeavyAssetPolicy.shouldBlock(
            url = runCatching { request.url?.toString() }.getOrNull(),
            forMainFrame = request.isForMainFrame,
            siteOrigin = AppConfig.SITE_ORIGIN,
            prefixes = AppConfig.BLOCKED_ASSET_PREFIXES,
            enabled = UpdatePolicy.flag(flags(), FLAG_BLOCK_LIVE2D_ASSETS, true),
        )
        if (!blocked) return null
        DiagLog.append(context, "拦截大资源请求（省流量与内存）：" + pathOf(request))
        return WebResourceResponse(
            HeavyAssetPolicy.EMPTY_MIME,
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to ("max-age=" + HeavyAssetPolicy.EMPTY_CACHE_SECONDS)),
            ByteArrayInputStream(EMPTY_BODY),
        )
    }

    /**
     * 冷启动时的缓存维护：按 [CacheTrimPolicy.shouldTrimDiskCache] 决定要不要清一次磁盘缓存。
     * 大部分启动什么都不做（这正是我们要的：长缓存是弱网下"不重复下载"的依靠）。
     */
    fun scheduleCacheMaintenance() {
        val now = System.currentTimeMillis()
        val free = freeCacheBytes()
        val last = prefs.getLong(KEY_LAST_DISK_TRIM, 0L)
        if (!CacheTrimPolicy.shouldTrimDiskCache(now, last, free, profile)) return
        DiagLog.append(
            context,
            "清理 WebView 磁盘缓存（剩余 " + (free / 1024 / 1024) + " MB，档位 " + profile.deviceClass + "）",
        )
        // clearCache 必须在主线程调用；这里只投递，不阻塞冷启动。
        webView.post {
            runCatching { webView.clearCache(true) }
            prefs.edit().putLong(KEY_LAST_DISK_TRIM, System.currentTimeMillis()).apply()
        }
    }

    /**
     * 内存压力回调（由 `MainActivity.onTrimMemory` 转发）。
     * 只清 **内存** 缓存（`clearCache(false)`），不动磁盘与 Cookie。
     */
    fun onTrimMemory(level: Int) {
        val pressure = pressureOf(level)
        if (pressure == MemoryPressure.NONE) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_MEMORY_TRIM, 0L)
        if (!CacheTrimPolicy.shouldTrimMemoryCache(now, last, pressure, profile)) return
        DiagLog.append(context, "清理 WebView 内存缓存（系统内存压力 level=" + level + "）")
        runCatching { webView.clearCache(false) }
        prefs.edit().putLong(KEY_LAST_MEMORY_TRIM, now).apply()
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private fun detectDeviceClass(): DeviceClass {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lowRam = manager?.isLowRamDevice ?: false
        val memoryClass = manager?.memoryClass ?: 0
        return DeviceClassifier.classify(lowRam, memoryClass, freeCacheBytes())
    }

    /** 缓存分区可用空间；读不到返回 -1（策略层会当作"不紧张"，绝不因为读不到就狂清缓存）。 */
    private fun freeCacheBytes(): Long =
        runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrDefault(-1L)

    /**
     * onTrimMemory 的 level → 压力档位。
     *
     * `UI_HIDDEN` / `BACKGROUND` / `MODERATE` 刻意不算压力：它们表达的是"退到后台"，
     * 清内存缓存只会让回到前台时重新解码图片（该做的事在 onPause 已经做了：停探测）。
     */
    private fun pressureOf(level: Int): MemoryPressure = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> MemoryPressure.LOW
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> MemoryPressure.CRITICAL

        else -> MemoryPressure.NONE
    }

    /** 日志脱敏：只记路径，不记完整 URL 与查询串。 */
    private fun pathOf(request: WebResourceRequest): String =
        runCatching { request.url?.path }.getOrNull().orEmpty().ifEmpty { "(unknown)" }

    private companion object {
        const val PREFS_NAME = "clubapp-perf"
        const val KEY_LAST_DISK_TRIM = "last-disk-trim-ms"
        const val KEY_LAST_MEMORY_TRIM = "last-memory-trim-ms"

        /** 清单开关：壳侧是否拦截 Live2D 素材（默认开，见 docs/APP-MANIFEST.md §3）。 */
        const val FLAG_BLOCK_LIVE2D_ASSETS = "blockLive2dAssets"

        /** 空体应答的字节（复用同一个不可变实例）。 */
        val EMPTY_BODY = ByteArray(0)
    }
}
