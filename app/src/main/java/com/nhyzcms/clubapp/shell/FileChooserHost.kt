package com.nhyzcms.clubapp.shell

import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * 网页 `<input type="file">` 的唯一入口。
 *
 * 壳**不实现 tus 上传**：只负责把用户选中的 URI 交回网页，网页里的 tus 客户端
 * 会完成分片上传（docs/ARCHITECTURE.md §5.4）。壳要做的是「打开选择器并回传 URI」。
 *
 * 三条必须守住的规则：
 *  1. `onShowFileChooser` 拿到的 `ValueCallback` **必须且只能被回调一次**，
 *     否则网页那个 input 会永久卡死（再点毫无反应，用户只能杀掉 App）。
 *     所以本类的每个出口（成功 / 取消 / 失败 / 被新请求打断 / Activity 销毁）都会释放它。
 *  2. accept 与多选由 `FileChooserParams.createIntent()` 负责，不要自己拼 Intent。
 *  3. 拍照是「额外入口」（`EXTRA_INITIAL_INTENTS`），由 [CameraCapture] 提供。
 */
class FileChooserHost(private val cameraCapture: CameraCapture) {

    private var callback: ValueCallback<Array<Uri>>? = null

    /** 是否有还没结束的选择请求（供调试与生命周期收尾判断）。 */
    val isWaiting: Boolean
        get() = callback != null

    /**
     * 开始一次文件选择。会先释放上一次未结束的请求（旧回调拿 null，网页不会卡住）。
     *
     * @param includeCamera 是否把「拍照」塞进选择器（见 [PermissionsPolicy.shouldOfferCapture]）。
     */
    fun start(
        params: WebChromeClient.FileChooserParams,
        includeCamera: Boolean,
        cb: ValueCallback<Array<Uri>>,
    ): Intent {
        abort()
        callback = cb

        val intent = params.createIntent()
        intent.putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
        )
        if (includeCamera) {
            cameraCapture.createCaptureIntentOrNull()?.let { cameraIntent ->
                intent.putExtra(EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }
        return intent
    }

    /** 交付结果：uris 为 null 表示「用户没选/被取消」。已被回调过则返回 false。 */
    fun deliver(uris: Array<Uri>?): Boolean {
        val cb = callback ?: return false
        callback = null
        runCatching { cb.onReceiveValue(uris) }
        return true
    }

    /** 放弃当前请求（含清理拍照临时文件），网页那边等价于「取消选择」。 */
    fun abort() {
        cameraCapture.discardPending()
        deliver(null)
    }

    private companion object {
        /**
         * `Intent.EXTRA_INITIAL_INTENTS` 的字面量。
         * 它只是选择器协议里的一个固定字符串键，用字面量可避免不同 SDK 版本上的可访问性差异。
         */
        const val EXTRA_INITIAL_INTENTS = "android.intent.extra.INITIAL_INTENTS"
    }
}
