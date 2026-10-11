package com.nhyzcms.clubapp.shell.update

/**
 * 签名一致性判定（纯函数，可 JVM 单测）。
 *
 * 为什么必须比签名：sha256 只能证明"下载到的字节和清单里写的一致"，
 * 但清单本身是服务器上的静态文件。签名比对证明的是**这个包确实出自我们自己**——
 * 否则一份被替换过的清单 + 一份被替换过的 APK 就能骗过校验。
 *
 * 判定方式：本机已装 APK 的签名证书集合 与 待安装包里的证书集合必须完全相同
 * （证书 DER 字节逐个比对，用哈希串做集合比较）。
 */
object SignatureCheck {

    fun sameSigners(local: List<ByteArray>, downloaded: List<ByteArray>): Boolean {
        if (local.isEmpty() || downloaded.isEmpty()) return false
        val a = local.map { ApkDigest.toHex(it) }.toSet()
        val b = downloaded.map { ApkDigest.toHex(it) }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b
    }

    /** 证书指纹（sha256，十六进制）——只用于日志，不用于判定。 */
    fun fingerprints(signers: List<ByteArray>): List<String> =
        signers.map { ApkDigest.toHex(ApkDigest.newDigest().digest(it)) }
}
