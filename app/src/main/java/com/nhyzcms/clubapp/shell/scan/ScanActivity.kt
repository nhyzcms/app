package com.nhyzcms.clubapp.shell.scan

import android.os.Bundle
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.nhyzcms.clubapp.R

/**
 * 原生扫码页（竖屏、黑底）。
 *
 * 解码、对焦、旋转与结果回传全部复用 zxing-android-embedded 的 [CaptureActivity]，
 * 本类只补三件壳自己的事：换成自己的布局（所以能换主题、加标题与按钮）、
 * 接上关闭按钮、接上手电筒开关。
 *
 * 识别到的文本由基类以 `SCAN_RESULT` extra 随 RESULT_OK 回给 MainActivity，
 * 再交给 [ScanRouter] 决定落到站内哪一页（业务判断在网页，壳不复制一份）。
 *
 * 为什么不用库里那个 CaptureActivity：它的清单写死了 `sensorLandscape` 与
 * `clearTaskOnLaunch`。竖屏拿手机凑近工牌更顺手，横屏还会把界面转一下再转回来。
 */
class ScanActivity : CaptureActivity() {

    private lateinit var scanner: DecoratedBarcodeView

    private var torchOn = false

    /** 基类的 onCreate 会调用它，并把返回值当成取景视图。 */
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        scanner = findViewById(R.id.scan_view)
        return scanner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        findViewById<ImageButton>(R.id.scan_close).setOnClickListener { finish() }

        val torch = findViewById<ImageButton>(R.id.scan_torch)
        torch.setOnClickListener {
            setTorch(!torchOn)
            torch.alpha = if (torchOn) 1f else TORCH_OFF_ALPHA
        }
        torch.alpha = TORCH_OFF_ALPHA
    }

    /**
     * 没有闪光灯的设备调用这两个方法不会崩（相机参数会被安静地拒掉），
     * 所以不做「有没有闪光灯」的预判，少一条分支。
     */
    private fun setTorch(on: Boolean) {
        torchOn = on
        if (on) scanner.setTorchOn() else scanner.setTorchOff()
    }

    private companion object {
        /**
         * 手电筒「关」时的按钮透明度。
         *
         * 有意不做跨重建的记忆：取景页锁竖屏、重建极少；而恢复时机常赶在相机 open 之前，
         * ZXing 的 CameraInstance.setTorch 只在相机已打开时才生效，会被静默丢掉 ——
         * 那就会出现「按钮亮着、灯没开」。每次从「关」开始，状态和画面才一致。
         */
        const val TORCH_OFF_ALPHA = 0.75f
    }
}
