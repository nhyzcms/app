package com.nhyzcms.clubapp.shell.security

/**
 * App **主动**申请相机权限的时机判定（纯函数，有 JVM 单测）。2026-11。
 *
 * 背景：网页 `/#/v`（社员查询）的扫码走 `getUserMedia`。壳接到
 * `onPermissionRequest` 时若系统还没给相机权限，只能先拒一次再拉系统授权框，
 * 用户看到的是「第一下扫码失败、再点一次才好」。
 *
 * 现在的两条路：
 *  1. 主动（本文件）：进了 `/#/v` 就先问一次系统，用户点下去时权限大概率已就绪；
 *  2. 兜底：网页真的来要摄像头时，挂起请求等系统结果再答（见 `MainActivity`）。
 *
 * 这里只做**判定**，弹框/授权全在 Activity 里 —— 与 `ScanEntryPolicy` / `WebPermissionPolicy`
 * 的分层保持一致。
 */
object CameraPermissionPolicy {

    /** 扫码页路由（站点用 hash 路由：`https://host/#/v`）。`/v/<码>` 是校验页，不取景，故不算。 */
    const val SCAN_ROUTE = "/v"

    /**
     * 当前地址是不是扫码页。
     *
     * 同时认两种写法：hash 路由（`/#/v`）与 path 路由（`/v`），取不到就当作不是。
     * 查询串、结尾斜杠、旧的 `#!/v` 写法都归一掉，避免「带了 `?q=` 就不认」。
     */
    fun isScanRoute(url: String?): Boolean = routeOf(url) == SCAN_ROUTE

    /**
     * 进到扫码页时要不要**主动**问一次。
     *
     * @param granted        系统相机权限是否已授予（有就别打扰）
     * @param askedForRoute  本次停留在扫码页期间是否已经问过（拒绝后不再连环弹窗）
     * @param promptInFlight 系统授权框是否正开着（重复 launch 会把两次结果弄串）
     */
    fun shouldAskOnScanRoute(
        granted: Boolean,
        askedForRoute: Boolean,
        promptInFlight: Boolean,
    ): Boolean = !granted && !askedForRoute && !promptInFlight

    /** 网页来要摄像头、但系统还没授权时的处理：挂起等结果，还是当场拒绝。 */
    enum class WebAsk {
        /** 挂起这个 `PermissionRequest`，拉系统授权框，结果回来后 grant/deny。 */
        Hold,

        /** 问也问不出结果（设备没有摄像头），只好拒。 */
        Deny,
    }

    fun webAskAction(hasCameraHardware: Boolean): WebAsk =
        if (hasCameraHardware) WebAsk.Hold else WebAsk.Deny

    /** 取出归一化后的路由（`/v`、`/m`…）；没有可识别路径时返回 null。 */
    private fun routeOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val hashAt = url.indexOf('#')
        val raw = if (hashAt >= 0) {
            val hash = url.substring(hashAt + 1)
            // `https://host/#/v` 与 `https://host#/v` 都取 hash；空 hash 退回看 path。
            if (hash.isBlank()) pathOf(url.substring(0, hashAt)) else hash
        } else {
            pathOf(url)
        }
        val path = raw.substringBefore('?').substringBefore('#').removePrefix("!").trim()
        if (path.isBlank()) return null
        val withSlash = if (path.startsWith("/")) path else "/$path"
        return if (withSlash.length > 1) withSlash.trimEnd('/').ifEmpty { "/" } else withSlash
    }

    /** 从完整地址里截出路径部分（scheme://host 之后）。 */
    private fun pathOf(url: String): String {
        val schemeAt = url.indexOf("://")
        val pathStart = if (schemeAt >= 0) url.indexOf('/', schemeAt + 3) else 0
        return if (pathStart >= 0) url.substring(pathStart) else "/"
    }
}
