package com.nhyzcms.clubapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nhyzcms.clubapp.shell.AppConfig
import com.nhyzcms.clubapp.shell.CameraCapture
import com.nhyzcms.clubapp.shell.DownloadPolicy
import com.nhyzcms.clubapp.shell.DownloadStarter
import com.nhyzcms.clubapp.shell.FileChooserHost
import com.nhyzcms.clubapp.shell.LongPressPolicy
import com.nhyzcms.clubapp.shell.MediaSharePolicy
import com.nhyzcms.clubapp.shell.MediaSharer
import com.nhyzcms.clubapp.shell.NavDecision
import com.nhyzcms.clubapp.shell.NavPolicy
import com.nhyzcms.clubapp.shell.PermissionsPolicy
import com.nhyzcms.clubapp.shell.ShellErrorKind
import com.nhyzcms.clubapp.shell.UploadLimit
import com.nhyzcms.clubapp.shell.UrlParts
import com.nhyzcms.clubapp.shell.WebShellChromeClient
import com.nhyzcms.clubapp.shell.WebShellClient

/**
 * 壳的入口：一个 Activity 加一个 WebView。
 *
 * 职责边界（重要）：本类不做任何业务判断（登录、路由、上传协议），
 * 只负责把网页跑起来、把原生能力接上去。详见 docs/ARCHITECTURE.md 第 4 节。
 *
 * 区块 2 新增的四条能力：
 *   ① input type=file 选择（含“拍照”入口）→ [FileChooserHost] + [CameraCapture]
 *   ② 下载落盘（带会话 Cookie）→ [DownloadStarter] + [DownloadPolicy]
 *   ③ 长按图片/链接（保存/分享/复制/浏览器打开）→ [LongPressPolicy] + [MediaSharer]
 *   ④ 上传体积前置提示 → [UploadLimit]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView

    /** 最后一次加载或失败的地址，供“重试”与“用浏览器打开”使用。 */
    private var lastUrl: String = AppConfig.START_URL

    private lateinit var cameraCapture: CameraCapture
    private lateinit var fileChooser: FileChooserHost
    private lateinit var downloads: DownloadStarter
    private lateinit var mediaSharer: MediaSharer

    /** 等相机权限期间暂存的选择请求：权限弹窗会打断流程，必须先把回调记住。 */
    private var pendingChooser: PendingChooser? = null

    /** 等存储权限期间暂存的下载请求（Android 8/9 才可能出现）。 */
    private var pendingDownload: PendingDownload? = null

    /** 本进程内拒绝过相机权限：只影响本次会话，下次启动再给一次机会。 */
    private var cameraDeniedInSession = false

    /** 本进程内拒绝过存储权限：不再反复弹窗，下载直接走 App 目录。 */
    private var storageDeniedInSession = false

    private class PendingChooser(
        val callback: ValueCallback<Array<Uri>>,
        val params: WebChromeClient.FileChooserParams,
    )

    private class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long,
    )

    /** 长按菜单里的动作（用枚举，而不是拿显示文案去比较）。 */
    private enum class LongPressAction { SAVE_IMAGE, SHARE_IMAGE, SHARE_LINK, COPY_LINK, OPEN_BROWSER }

    /** 文件选择 / 拍照的结果统一回到 [handleChooserResult]。 */
    private val chooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleChooserResult(result.resultCode, result.data)
        }

    /** 相机权限：拿到结果后再开选择器（“拍照”入口能不能用，取决于这一下）。 */
    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingChooser ?: return@registerForActivityResult
            pendingChooser = null
            if (!granted) {
                cameraDeniedInSession = true
                explainCameraDenied()
            }
            // 无论授没授权都要把选择器开起来，否则网页那个 input 会一直卡着。
            launchChooser(pending.callback, pending.params, includeCamera = granted)
        }

    /** 存储权限（仅 Android 8/9 需要）：拒了也要把下载继续下去，只是换个目录。 */
    private val storagePermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingDownload ?: return@registerForActivityResult
            pendingDownload = null
            if (!granted) {
                storageDeniedInSession = true
                toast(R.string.toast_download_app_dir_fallback)
            }
            startDownloadNow(pending, writePermissionGranted = granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()

        cameraCapture = CameraCapture(this)
        fileChooser = FileChooserHost(cameraCapture)
        downloads = DownloadStarter(this)
        mediaSharer = MediaSharer(this)
        // 上次拍完照/分享完没来得及清的临时文件在这里收尾。
        cameraCapture.cleanStaleFiles()
        mediaSharer.cleanStaleFiles()

        // release 包关闭 WebView 远程调试（安全文档第 2 节）；debug 包保留 chrome://inspect 能力。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        configureWebView()
        registerBackCallback()
        bindErrorPanelActions()

        // 进程被系统回收后重建：优先恢复浏览历史，避免用户回到首页。
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            load(intentUrlOrNull(intent) ?: AppConfig.START_URL)
        }
    }

    // ── 初始化 ────────────────────────────────────────────────────────────────

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.errorPanel)
        errorTitle = findViewById(R.id.errorTitle)
        errorMessage = findViewById(R.id.errorMessage)
    }

    @Suppress("SetJavaScriptEnabled", "DEPRECATION")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true // SPA 必需
            domStorageEnabled = true // localStorage（主题偏好等）
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // 壳只加载网络内容，不需要本地文件能力，全部关闭（安全文档第 2 节）
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false

            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString + " " + AppConfig.uaMarker()

            // 注：这里用显式方法名而不是属性语法：
            // WebSettings 对 safe browsing 只提供了 setter，没有 getter，
            // 没有 getter 就无法用 Kotlin 的属性语法（会直接编译失败）。
            setSafeBrowsingEnabled(true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true) // 登录会话靠它，必须开
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = WebShellClient(
            onError = ::showError,
            onExternalOpened = { toast(R.string.toast_external_opened) },
            onBlocked = { reason, _ ->
                Log.w(TAG, "拦截导航：$reason")
                toast(R.string.toast_link_blocked)
            },
            onPageStarted = { hideError() },
            onPageFinished = {
                progress.visibility = View.GONE
                hideError()
            },
        )

        webView.webChromeClient = WebShellChromeClient(
            onProgress = { value ->
                if (value in 1..99) {
                    progress.visibility = View.VISIBLE
                    progress.progress = value
                } else {
                    progress.visibility = View.GONE
                }
            },
            log = { message -> if (BuildConfig.DEBUG) Log.d(TAG, message) },
            onShowFileChooser = ::handleFileChooser,
        )

        // 下载：WebView 自己不会落盘，交给系统 DownloadManager（带会话 Cookie，见 DownloadStarter）。
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            startDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // 长按：只在图片/链接上接管；其它命中（文本选择、输入框、电话/邮箱/地图）返回 false 交回系统。
        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            when (val target = LongPressPolicy.classify(hit.type, hit.extra)) {
                is LongPressPolicy.Target.Image -> {
                    showLongPressMenu(R.string.dialog_image_actions, target.url, withImageItems = true)
                    true
                }
                is LongPressPolicy.Target.Link -> {
                    showLongPressMenu(R.string.dialog_link_actions, target.url, withImageItems = false)
                    true
                }
                LongPressPolicy.Target.None -> false
            }
        }
    }

    // ── 文件选择 / 拍照（区块 2）─────────────────────────────────────────────

    /**
     * 网页 input type=file 被点击。
     *
     * 只要返回 true，就必须保证 callback 最终被回调一次（见 [FileChooserHost] 的注释）。
     * 相机权限还没拿到时，要先申请权限再开选择器 —— 选择器一开，我们就没时机插权限申请了。
     */
    private fun handleFileChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
    ): Boolean {
        val wantCamera = PermissionsPolicy.shouldOfferCapture(params.isCaptureEnabled, params.acceptTypes) &&
            !cameraDeniedInSession &&
            hasCameraHardware()

        if (wantCamera && !hasCameraPermission()) {
            pendingChooser = PendingChooser(callback, params)
            cameraPermissionLauncher.launch(PermissionsPolicy.CAMERA)
            return true
        }
        launchChooser(callback, params, includeCamera = wantCamera)
        return true
    }

    private fun launchChooser(
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams,
        includeCamera: Boolean,
    ) {
        val intent = runCatching { fileChooser.start(params, includeCamera, callback) }.getOrNull()
        if (intent == null) {
            // 构建失败也要把回调释放掉，否则网页 input 卡死。
            fileChooser.abort()
            Log.w(TAG, "无法构建文件选择器 Intent")
            toast(R.string.toast_no_file_picker)
            return
        }
        try {
            chooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            fileChooser.abort()
            toast(R.string.toast_no_file_picker)
        }
    }

    private fun handleChooserResult(resultCode: Int, data: Intent?) {
        val captured = cameraCapture.consumeCaptureResult(resultCode, data)
        val uris: Array<Uri>? = when {
            resultCode != RESULT_OK -> null
            captured != null -> arrayOf(captured)
            else -> WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        if (uris.isNullOrEmpty()) {
            fileChooser.deliver(null)
            return
        }
        when (val verdict = UploadLimit.check(probeSizes(uris))) {
            is UploadLimit.Verdict.Allowed -> fileChooser.deliver(uris)
            is UploadLimit.Verdict.Exceeded -> confirmOversizeUpload(verdict, uris)
        }
    }

    /** 超过站点上限（20 MB）时先问一句，避免“传到一半被服务器 413”。 */
    private fun confirmOversizeUpload(verdict: UploadLimit.Verdict.Exceeded, uris: Array<Uri>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.upload_too_large_title)
            .setMessage(getString(R.string.upload_too_large_message, UploadLimit.formatBytes(verdict.totalBytes)))
            .setCancelable(false)
            .setPositiveButton(R.string.action_upload_continue) { _, _ -> fileChooser.deliver(uris) }
            // 回传 null = 网页看起来像“用户没选文件”，不会触发上传。
            .setNegativeButton(R.string.action_upload_cancel) { _, _ -> fileChooser.deliver(null) }
            .show()
    }

    /**
     * 取所选文件的字节数；拿不到（部分云盘 provider 不返回 SIZE）就返回 null，交给网页去发现。
     * 异常一律吞掉：量体积失败不能让上传流程断掉。
     */
    private fun probeSizes(uris: Array<Uri>): List<Long?> = uris.map { uri ->
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, PermissionsPolicy.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * 是否持有写外部存储权限。Android 10 及以上不需要这个权限，直接算作“有”
     * （清单里那份声明也带了 maxSdkVersion=28，在高版本上根本不会生效）。
     */
    private fun hasWritePermission(): Boolean =
        !DownloadPolicy.needsWritePermission(Build.VERSION.SDK_INT) ||
            ContextCompat.checkSelfPermission(this, PermissionsPolicy.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /** 没有摄像头的设备不申请、也不给拍照入口。 */
    private fun hasCameraHardware(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** 被拒后的引导：只有“禁止且不再询问”才值得弹设置页。 */
    private fun explainCameraDenied() {
        toast(R.string.toast_camera_denied)
        if (shouldShowRequestPermissionRationale(PermissionsPolicy.CAMERA)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_denied_title)
            .setMessage(R.string.camera_denied_message)
            .setPositiveButton(R.string.action_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        runCatching { startActivity(intent) }
    }

    // ── 下载（区块 2）───────────────────────────────────────────────────────

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val rejection = DownloadPolicy.rejectionOf(url)
        if (rejection != null) {
            // 日志脱敏：只记 host，不记完整 URL 与查询串
            Log.w(TAG, "拒绝下载：$rejection host=" + hostOf(url))
            toast(R.string.toast_download_unsupported)
            return
        }
        val request = PendingDownload(
            url = url,
            userAgent = userAgent?.takeIf { it.isNotBlank() } ?: webView.settings.userAgentString,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
        )
        // Android 8/9 要把文件写进公共“下载”目录得先拿写权限；本次已拒过就不再弹窗，
        // 直接走 App 目录（下载本身不受影响，只是位置不同）。
        if (!hasWritePermission() && !storageDeniedInSession) {
            pendingDownload = request
            storagePermissionLauncher.launch(PermissionsPolicy.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownloadNow(request, writePermissionGranted = hasWritePermission())
    }

    private fun startDownloadNow(request: PendingDownload, writePermissionGranted: Boolean) {
        val destination = DownloadPolicy.destination(Build.VERSION.SDK_INT, writePermissionGranted)
        val id = downloads.start(
            url = request.url,
            userAgent = request.userAgent,
            contentDisposition = request.contentDisposition,
            mimeType = request.mimeType,
            contentLength = request.contentLength,
            destination = destination,
        )
        if (id == null) {
            toast(R.string.toast_download_failed)
            return
        }
        toast(
            if (destination == DownloadPolicy.Destination.PUBLIC_DOWNLOADS) R.string.toast_download_started
            else R.string.toast_download_started_app_dir,
        )
    }

    // ── 长按菜单（区块 2）───────────────────────────────────────────────────

    private fun showLongPressMenu(titleRes: Int, url: String, withImageItems: Boolean) {
        val actions = buildList {
            if (withImageItems) {
                if (DownloadPolicy.rejectionOf(url) == null) add(LongPressAction.SAVE_IMAGE)
                if (MediaSharePolicy.check(url, MediaSharePolicy.UNKNOWN_SIZE) == MediaSharePolicy.Verdict.Allowed) {
                    add(LongPressAction.SHARE_IMAGE)
                }
            }
            add(LongPressAction.SHARE_LINK)
            add(LongPressAction.COPY_LINK)
            add(LongPressAction.OPEN_BROWSER)
        }
        val labels = actions.map { getString(labelOf(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels) { _, which -> performLongPressAction(actions[which], url) }
            .show()
    }

    private fun labelOf(action: LongPressAction): Int = when (action) {
        LongPressAction.SAVE_IMAGE -> R.string.action_save_image
        LongPressAction.SHARE_IMAGE -> R.string.action_share_image
        LongPressAction.SHARE_LINK -> R.string.action_share_link
        LongPressAction.COPY_LINK -> R.string.action_copy_link
        LongPressAction.OPEN_BROWSER -> R.string.action_open_browser
    }

    private fun performLongPressAction(action: LongPressAction, url: String) {
        when (action) {
            LongPressAction.SAVE_IMAGE ->
                startDownload(url, webView.settings.userAgentString, null, "image/*", -1L)

            LongPressAction.SHARE_IMAGE -> shareImage(url)
            LongPressAction.SHARE_LINK -> shareText(url)
            LongPressAction.COPY_LINK -> copyToClipboard(url)
            LongPressAction.OPEN_BROWSER -> openExternally(url)
        }
    }

    /**
     * 分享图片文件本体：先下到缓存再交给系统分享（见 [MediaSharer]）。
     * 失败/过大/地址不支持时自动退化成“分享链接”——不让用户两手空空。
     */
    private fun shareImage(url: String) {
        if (MediaSharePolicy.check(url, MediaSharePolicy.UNKNOWN_SIZE) != MediaSharePolicy.Verdict.Allowed) {
            shareText(url)
            return
        }
        toast(R.string.toast_share_preparing)
        mediaSharer.share(url, webView.settings.userAgentString) { result ->
            when (result) {
                MediaSharer.Result.Success -> Unit
                MediaSharer.Result.TooLarge -> {
                    toast(R.string.toast_share_image_too_large)
                    shareText(url)
                }
                MediaSharer.Result.Failed -> {
                    toast(R.string.toast_share_image_failed)
                    shareText(url)
                }
            }
        }
    }

    private fun shareText(url: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.dialog_share_link)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.toast_share_unavailable)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
        toast(R.string.toast_copied)
    }

    /** 返回键：先关错误面板，再网页后退，最后才退出应用。 */
    private fun registerBackCallback() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (errorPanel.visibility == View.VISIBLE) {
                        hideError()
                        return
                    }
                    if (webView.canGoBack()) {
                        webView.goBack()
                        return
                    }
                    // 把控制权交回系统（退出）。
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
    }

    private fun bindErrorPanelActions() {
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            hideError()
            load(lastUrl)
        }
        findViewById<Button>(R.id.browserButton).setOnClickListener {
            openExternally(lastUrl)
        }
    }

    // ── 错误面板 ──────────────────────────────────────────────────────────────

    private fun showError(kind: ShellErrorKind, failingUrl: String?) {
        if (!failingUrl.isNullOrBlank() && failingUrl.startsWith("http")) {
            lastUrl = failingUrl
        }
        errorTitle.setText(R.string.error_title)
        errorMessage.setText(
            when (kind) {
                ShellErrorKind.NETWORK -> R.string.error_network
                ShellErrorKind.TLS -> R.string.error_tls
                ShellErrorKind.BLOCKED_CLEARTEXT -> R.string.error_cleartext
                ShellErrorKind.GENERIC -> R.string.error_generic
            },
        )
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE

        if (kind.isSecurityRelated) {
            // 日志脱敏：只记 host，不记完整 URL 与查询串
            Log.w(TAG, "安全类加载失败：" + kind.name + " host=" + hostOf(lastUrl))
        }
    }

    private fun hideError() {
        errorPanel.visibility = View.GONE
    }

    // ── 导航与生命周期 ──────────────────────────────────────────────────────

    private fun load(url: String) {
        lastUrl = url
        hideError()
        progress.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    /** 处理 ACTION_VIEW 深链：只在目标属于本站时才交给壳内加载。 */
    private fun intentUrlOrNull(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return when (val decision = NavPolicy.decide(UrlParts.of(data))) {
            is NavDecision.InApp -> decision.url
            else -> null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentUrlOrNull(intent)?.let { load(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume() // 恢复 JS 定时器与动画
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // 未完成的请求必须在销毁前释放，否则网页那边会一直卡着。
        // （abort 会顺带清掉拍照的临时文件）
        pendingChooser = null
        pendingDownload = null
        fileChooser.abort()

        // WebView 不主动销毁会泄露 Activity。
        // ⚠️ 踩坑：API 35 上 setWebViewClient 的参数是 @NonNull，**不能传 null**
        //（会报 “Null cannot be a value of a non-null type” 而编译失败），
        // 所以这里放空实现来切断回调，而不是传 null。
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    // ── 小工具 ────────────────────────────────────────────────────────────────

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            toast(R.string.toast_external_opened)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.toast_link_blocked)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun hostOf(url: String?): String =
        runCatching { Uri.parse(url.orEmpty()).host }.getOrNull().orEmpty().ifEmpty { "-" }

    private companion object {
        const val TAG = "ClubApp"
    }
}
