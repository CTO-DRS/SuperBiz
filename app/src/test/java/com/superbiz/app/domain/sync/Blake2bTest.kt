package com.superbiz.app.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * اختبارات [Blake2b] — متجهات معيارية رسمية (RFC 7693 / hashlib المرجعي)
 * + متجه H0 لـArgon2id من RFC 9106 §5.3 كتكامل مسار المعيارية.
 */
class Blake2bTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun `blake2b-512 of abc — RFC 7693 vector`() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            Blake2b.digest("abc".toByteArray(Charsets.UTF_8), 64).toHex()
        )
    }

    @Test
    fun `blake2b-512 of empty message`() {
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
                "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            Blake2b.digest(ByteArray(0), 64).toHex()
        )
    }

    @Test
    fun `blake2b-256 of abc`() {
        assertEquals(
            "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319",
            Blake2b.digest("abc".toByteArray(Charsets.UTF_8), 32).toHex()
        )
    }

    @Test
    fun `multi-block inputs — 200 and exact multiples of 128`() {
        val in200 = ByteArray(200) { (it and 0xFF).toByte() }
        assertEquals(
            "fb3c1f0f56a56f8e316fdf5d853c8c872c39635d083634c3904fc3ac07d1b57" +
                "8e85ff0e480e92d44ade33b62e893ee32343e79ddf6ef292e89b582d312502314",
            Blake2b.digest(in200, 64).toHex()
        )
        assertEquals(
            "2319e3789c47e2daa5fe807f61bec2a1a6537fa03f19ff32e87eecbfd64b7e0e" +
                "8ccff439ac333b040f19b0c4ddd11a61e24ac1fe0f10a039806c5dcc0da3d115",
            Blake2b.digest(ByteArray(128) { (it and 0xFF).toByte() }, 64).toHex()
        )
        assertEquals(
            "1ecc896f34d3f9cac484c73f75f6a5fb58ee6784be41b35f46067b9c65c63a67" +
                "94d3d744112c653f73dd7deb6666204c5a9bfa5b46081fc10fdbe7884fa5cbf8",
            Blake2b.digest(ByteArray(256) { (it and 0xFF).toByte() }, 64).toHex()
        )
    }

    @Test
    fun `h0 of RFC 9106 argon2id vector — pre-hashing digest`() {
        // مدخل H0 من متجه RFC 9106 §5.3: p=4، T=32، m=32، t=3، v=0x13، y=2
        val pwd = ByteArray(32) { 0x01 }
        val salt = ByteArray(16) { 0x02 }
        val secret = ByteArray(8) { 0x03 }
        val ad = ByteArray(12) { 0x04 }
        val input = java.io.ByteArrayOutputStream().apply {
            fun le32(v: Int) { write(v); write(v ushr 8); write(v ushr 16); write(v ushr 24) }
            le32(4); le32(32); le32(32); le32(3); le32(0x13); le32(2)
            le32(pwd.size); write(pwd)
            le32(salt.size); write(salt)
            le32(secret.size); write(secret)
            le32(ad.size); write(ad)
        }.toByteArray()
        assertEquals(
            "2889de487eb42ae500c0007ed9252f1069eadec40d5765b485de6dc2437a67b8" +
                "546a2f0acc1a0882db8fcf74714b472e94df421a5da1112ffa11434370a1e997",
            Blake2b.digest(input, 64).toHex()
        )
    }
}
