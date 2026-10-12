package com.nhyzcms.clubapp.shell.update

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nhyzcms.clubapp.BuildConfig
import com.nhyzcms.clubapp.R
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.UploadLimit
import kotlin.concurrent.thread

/**
 * 三级更新的壳侧协调器（区块 3）。
 *
 * 职责：
 *  - L0：把"重新取入口 HTML"的决定权交给清单开关（forceRefreshEntry），
 *    真正的清理动作由 MainActivity 执行（它才持有 WebView）；
 *  - L1：后台拉 `app-update/manifest.json` → 解析 → 落盘开关/起始路由 → 弹公告；
 *  - L2：版本对比 → 提示 → 下载（可续传）→ sha256 校验 → 签名比对 → 拉起安装器。
 *
 * 设计原则：
 *  1. **配置拉不到不能让 App 不可用**：任何一环失败都只是"这次不更新"，不弹错误面板；
 *  2. **提示只在主线程弹**：网络与摘要计算都在后台线程；
 *  3. 冷启动后延迟几秒才检查（不抢首屏带宽——服务器只有 3 M）；
 *  4. 回到前台时按 30 分钟节流检查（见 docs/UPDATE-STRATEGY.md §3）；
 *  5. **线程红线**：WebView 只能在主线程访问，UA 一律在主线程取（见 [userAgentOnMainThread]）。
 *     ❗ 违反这条曾经直接导致"一进 App 就闪退"（2026-10-12），详见 docs/FIX-20261012-STARTUP-CRASH.md。
 */
class UpdateController(
    private val activity: AppCompatActivity,
    /** UA 延迟到真正发起请求时再取：那会儿 WebView 已经配置好并带上了 App 标记。 */
    private val userAgentProvider: () -> String?,
) {

    private val store = UpdateStore(activity)

    /**
     * 下载器只在主线程被构造/使用（cleanStaleFiles、beginDownload、dispose 全在主线程），
     * 但仍然统一走 [userAgentOnMainThread]：UA 的取法只有这一个入口，便于一眼查清调用点。
     */
    private val downloader: ApkDownloader by lazy {
        ApkDownloader(activity, userAgentOnMainThread(), AppConfig.UPDATE_ALLOWED_HOSTS)
    }
    private val installer = UpdateInstaller(activity)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var checking = false
    private var disposed = false
    private var downloadDialog: AlertDialog? = null

    // ── 供 MainActivity 使用 ─────────────────────────────────────────────────

    /** 当前生效的开关（内置默认 + 上次清单）。 */
    fun flags(): Map<String, Boolean> = store.flags()

    /** 冷启动要打开的地址：用上次缓存的 startRoute，拿不到就内置默认。 */
    fun startUrl(): String {
        val route = UpdatePolicy.sanitizeStartRoute(store.startRoute, AppConfig.START_PATH)
        return AppConfig.SITE_ORIGIN + route
    }

    /** 是否需要"强制重新取入口 HTML"（调试缓存问题用，见清单 flags）。 */
    fun shouldForceRefreshEntry(): Boolean =
        UpdatePolicy.flag(store.flags(), FLAG_FORCE_REFRESH_ENTRY, false)

    /** 清掉过期更新包残留（进程启动时调一次）。 */
    fun cleanStaleFiles() {
        downloader.cleanStaleFiles()
    }

    /** 冷启动：延迟几秒再检查（不跟首屏抢带宽）。 */
    fun onShellReady() {
        mainHandler.postDelayed({ checkNow(manual = false) }, STARTUP_DELAY_MS)
    }

    /** 回到前台：距上次成功检查超过 30 分钟才重新检查。 */
    fun checkIfDue() {
        if (disposed || checking) return
        if (!autoUpdateEnabled()) return
        val now = System.currentTimeMillis()
        if (now - store.lastCheckAtMs < FOREGROUND_INTERVAL_MS) return
        checkNow(manual = false)
    }

    /** 手动检查（错误面板的"检查更新"按钮）。 */
    fun checkManually() {
        checkNow(manual = true)
    }

    fun dispose() {
        disposed = true
        downloader.cancel()
        downloadDialog = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ── L1 + L2：检查 ────────────────────────────────────────────────────────

    private fun checkNow(manual: Boolean) {
        if (disposed) return
        if (!manual && !autoUpdateEnabled()) return
        if (checking) {
            if (manual) toast(R.string.update_checking)
            return
        }
        checking = true
        if (manual) toast(R.string.update_checking)

        // ⚠️ UA 必须在**本线程（主线程）**先取好，再带进后台线程（见 [userAgentOnMainThread]）。
        // 修 2026-10-12 启动闪退：以前这里放的是 `fetcher` 的 lazy 初始化，它会在后台线程里
        // 调 userAgentProvider() → 后台线程访问 webView.settings → WebView 直接抛异常 → 进程被杀。
        val userAgent = userAgentOnMainThread()

        thread(name = "clubapp-update-check") {
            val text = ManifestFetcher(userAgent, AppConfig.UPDATE_ALLOWED_HOSTS)
                .fetch(AppConfig.UPDATE_MANIFEST_URL)
            val manifest = UpdateManifestCodec.parse(text)
            val decision = if (manifest == null) {
                UpdatePolicy.Decision.None
            } else {
                UpdatePolicy.decide(manifest, BuildConfig.VERSION_CODE, AppConfig.UPDATE_ALLOWED_HOSTS)
            }
            mainHandler.post {
                checking = false
                if (disposed) return@post
                if (manifest == null) {
                    if (manual) toast(R.string.update_check_failed)
                    return@post
                }
                applyManifest(manifest)
                when (decision) {
                    is UpdatePolicy.Decision.Update -> promptUpdate(decision)
                    is UpdatePolicy.Decision.Blocked -> showBlocked(decision)
                    UpdatePolicy.Decision.None -> {
                        if (manual) toast(R.string.toast_up_to_date)
                        maybeShowNotice(manifest.notice)
                    }
                }
            }
        }
    }

    /** 把清单里"能落盘的部分"存起来（下次冷启动生效）。 */
    private fun applyManifest(manifest: UpdateManifest) {
        store.lastCheckAtMs = System.currentTimeMillis()
        val route = UpdatePolicy.sanitizeStartRoute(manifest.startRoute, AppConfig.START_PATH)
        if (route != store.startRoute) store.startRoute = route
        store.saveFlags(UpdatePolicy.mergedFlags(manifest))
    }

    private fun promptUpdate(decision: UpdatePolicy.Decision.Update) {
        val release = decision.release
        val notes = release.notes?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.update_no_notes)
        val message = if (decision.mandatory) {
            activity.getString(R.string.update_mandatory_message, release.versionName, notes)
        } else {
            activity.getString(R.string.update_optional_message, release.versionName, notes)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setCancelable(!decision.mandatory)
            .setPositiveButton(R.string.action_update_now) { _, _ -> beginDownload(decision) }
        if (decision.mandatory) {
            builder.setNegativeButton(R.string.action_exit_app) { _, _ -> activity.finish() }
        } else {
            builder.setNegativeButton(R.string.action_update_later, null)
        }
        builder.show()
    }

    private fun showBlocked(decision: UpdatePolicy.Decision.Blocked) {
        val message = when (decision.reason) {
            UpdatePolicy.REASON_VERSION_BLOCKED ->
                decision.message ?: activity.getString(R.string.update_blocked_message)

            UpdatePolicy.REASON_NO_DOWNLOAD_SOURCE ->
                activity.getString(R.string.update_no_source_message)

            else -> activity.getString(R.string.update_shell_too_old_message)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_blocked_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_open_browser) { _, _ ->
                openInBrowser(AppConfig.SITE_ORIGIN + AppConfig.START_PATH)
            }
            .setNegativeButton(R.string.action_exit_app) { _, _ -> activity.finish() }
            .show()
    }

    // ── L2：下载 → 校验 → 安装 ───────────────────────────────────────────────

    private fun beginDownload(decision: UpdatePolicy.Decision.Update) {
        val apk = decision.release.apk ?: return
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading_title)
            .setMessage(activity.getString(R.string.update_downloading_starting))
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel) { _, _ -> downloader.cancel() }
            .create()
        downloadDialog = dialog
        dialog.show()

        // 只有"上次下到一半的是同一个包"时才续传（否则那是别的版本，续上去必然校验失败）。
        val resumeFrom = if (store.pendingSha256 == apk.sha256) downloader.partialLength() else 0L
        downloader.start(
            urls = decision.urls,
            expectedSha256 = apk.sha256,
            expectedSize = apk.size,
            resumeFrom = resumeFrom,
            onProgress = { downloaded, total -> updateProgress(dialog, downloaded, total) },
            onFinished = { outcome -> onDownloadFinished(dialog, outcome, decision) },
        )
    }

    private fun onDownloadFinished(
        dialog: AlertDialog,
        outcome: ApkDownloader.Outcome,
        decision: UpdatePolicy.Decision.Update,
    ) {
        if (downloadDialog === dialog) downloadDialog = null
        runCatching { dialog.dismiss() }
        if (disposed) return

        val apk = decision.release.apk ?: return
        when (outcome) {
            is ApkDownloader.Outcome.Ok -> {
                store.pendingSha256 = null
                verifyAndInstall(outcome.file)
            }

            is ApkDownloader.Outcome.Failed -> {
                // 记住摘要：下次可以对残留的 .part 续传
                store.pendingSha256 = apk.sha256
                Log.w(TAG, "更新包下载失败：" + outcome.reason)
                showDownloadFailed(outcome.reason, decision)
            }

            ApkDownloader.Outcome.Cancelled -> {
                store.pendingSha256 = apk.sha256
                toast(R.string.update_download_cancelled)
            }
        }
    }

    /**
     * 安装前最后一道关：sha256 在下载器里已经过了，这里再比签名。
     *
     * 两者缺一不可——sha256 只能证明"和清单里写的一致"，
     * 签名才能证明"这个包确实出自我们自己"（清单是服务器上的静态文件，可能被换）。
     */
    private fun verifyAndInstall(apkFile: java.io.File) {
        val local = ApkSignatures.installed(activity)
        val remote = ApkSignatures.archive(activity, apkFile)
        if (!SignatureCheck.sameSigners(local, remote)) {
            apkFile.delete()
            Log.w(TAG, "更新包签名与本机不一致，已拒绝安装")
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_signature_title)
                .setMessage(R.string.update_signature_message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        if (!installer.canInstall()) {
            askInstallPermission()
            return
        }
        if (!installer.install(apkFile)) {
            toast(R.string.update_install_failed)
        }
    }

    private fun askInstallPermission() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                installer.openInstallPermissionSettings()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDownloadFailed(reason: String, decision: UpdatePolicy.Decision.Update) {
        val message = when (reason) {
            ApkDownloader.REASON_NO_SPACE -> activity.getString(R.string.update_no_space)
            ApkDownloader.REASON_HASH_MISMATCH,
            ApkDownloader.REASON_SIZE_MISMATCH,
            ApkDownloader.REASON_TOO_SMALL,
            -> activity.getString(R.string.update_verify_failed)

            else -> activity.getString(R.string.update_download_failed)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(message)
            .setCancelable(!decision.mandatory)
            .setPositiveButton(R.string.action_retry) { _, _ -> beginDownload(decision) }
        if (decision.mandatory) {
            builder.setNegativeButton(R.string.action_exit_app) { _, _ -> activity.finish() }
        } else {
            builder.setNegativeButton(R.string.action_update_later, null)
        }
        builder.show()
    }

    private fun updateProgress(dialog: AlertDialog, downloaded: Long, total: Long) {
        if (disposed || !dialog.isShowing) return
        val message = if (total > 0) {
            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            activity.getString(
                R.string.update_downloading_message,
                UploadLimit.formatBytes(downloaded),
                UploadLimit.formatBytes(total),
                percent,
            )
        } else {
            activity.getString(R.string.update_downloading_unknown, UploadLimit.formatBytes(downloaded))
        }
        dialog.setMessage(message)
    }

    // ── L1：公告 ────────────────────────────────────────────────────────────

    private fun maybeShowNotice(notice: Notice?) {
        if (!UpdatePolicy.shouldShowNotice(notice, store.dismissedNoticeId)) return
        val current = notice ?: return
        val title = if (current.level.equals("warn", ignoreCase = true)) {
            R.string.notice_title_warn
        } else {
            R.string.notice_title
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(current.text)
            .setCancelable(true)
            .setPositiveButton(R.string.action_notice_ok) { _, _ ->
                store.dismissedNoticeId = current.id
            }
            .setOnCancelListener { store.dismissedNoticeId = current.id }
            .show()
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    /**
     * 取 WebView 的 UA —— **只能在主线程调用**。
     *
     * 为什么必须单独拎出来：WebView 的所有方法都只能在创建它的线程（主线程）调用。
     * AOSP `WebView.getSettings()` 内部会 `checkThread()`，而
     * `sEnforceThreadChecking = targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN_MR2`
     * （本工程 targetSdk = 34）→ 命中即**直接抛 RuntimeException**；该异常发生在后台线程、
     * 没有任何人接住 → 进程被杀 → 用户看到的就是"点进 App 一秒就闪退"，且每次必现。
     *
     * 因此所有需要 UA 的地方都必须经过这里；万一将来有人在后台线程调用，
     * 这里让本次请求退回"不带 App UA 标记"（站点最多把这次请求当普通浏览器），
     * 而不是把整个 App 拖死。
     */
    private fun userAgentOnMainThread(): String? {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.w(TAG, "userAgentProvider 只能在主线程调用，本次跳过（不带 App UA 标记）")
            return null
        }
        return runCatching { userAgentProvider() }.getOrNull()
    }

    private fun autoUpdateEnabled(): Boolean =
        UpdatePolicy.flag(store.flags(), FLAG_AUTO_UPDATE_CHECK, true)

    private fun openInBrowser(url: String) {
        runCatching {
            activity.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            )
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "ClubApp"

        /** 冷启动后延迟多久检查：先让首屏加载完（3 M 带宽下这很关键）。 */
        const val STARTUP_DELAY_MS = 3_000L

        /** 前台节流：与 docs/UPDATE-STRATEGY.md §3 的"30 分钟"一致。 */
        const val FOREGROUND_INTERVAL_MS = 30L * 60L * 1000L

        const val FLAG_AUTO_UPDATE_CHECK = "autoUpdateCheck"
        const val FLAG_FORCE_REFRESH_ENTRY = "forceRefreshEntry"
    }
}
