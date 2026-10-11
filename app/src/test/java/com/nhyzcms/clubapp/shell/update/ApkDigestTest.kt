package com.nhyzcms.clubapp.shell.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ApkDigest] 与 [SignatureCheck] 的单测。 */
class ApkDigestTest {

    /** 标准向量：SHA-256("abc")。 */
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(abcSha, ApkDigest.sha256Of("abc"))
    }

    @Test
    fun sha256OfEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkDigest.sha256Of(""),
        )
    }

    @Test
    fun hexEncodingIsLowercaseAndZeroPadded() {
        assertEquals("000fff", ApkDigest.toHex(byteArrayOf(0, 15, -1)))
        assertEquals("", ApkDigest.toHex(ByteArray(0)))
    }

    @Test
    fun sha256OfFileEqualsSha256OfContent() {
        val file = File.createTempFile("apk-digest", ".bin")
        try {
            file.writeBytes("abc".toByteArray(Charsets.UTF_8))
            assertEquals(abcSha, ApkDigest.sha256Of(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sha256OfMissingFileIsNull() {
        assertEquals(null, ApkDigest.sha256Of(File("/definitely/not/here.bin")))
    }

    @Test
    fun matchesIsCaseInsensitiveAndValidated() {
        assertTrue(ApkDigest.matches(abcSha, abcSha))
        assertTrue(ApkDigest.matches(abcSha.uppercase(), abcSha))
        assertFalse(ApkDigest.matches(abcSha, null))
        assertFalse(ApkDigest.matches(null, abcSha))
        assertFalse(ApkDigest.matches("zz", "zz")) // 格式不合法 → 一律 false
        assertFalse(ApkDigest.matches(abcSha, "0".repeat(64)))
    }

    @Test
    fun sameSignersRequiresExactSetMatch() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        assertTrue(SignatureCheck.sameSigners(listOf(a), listOf(byteArrayOf(1, 2, 3))))
        assertTrue(SignatureCheck.sameSigners(listOf(a, b), listOf(b, a))) // 顺序无关
        assertFalse(SignatureCheck.sameSigners(listOf(a), listOf(b)))
        assertFalse(SignatureCheck.sameSigners(listOf(a), emptyList()))
        assertFalse(SignatureCheck.sameSigners(emptyList(), listOf(a)))
        assertFalse(SignatureCheck.sameSigners(emptyList(), emptyList()))
    }

    @Test
    fun fingerprintsAreStable() {
        val prints = SignatureCheck.fingerprints(listOf(byteArrayOf(1, 2, 3)))
        assertEquals(1, prints.size)
        assertEquals(64, prints[0].length)
        assertEquals(prints[0].lowercase(), prints[0])
    }
}
