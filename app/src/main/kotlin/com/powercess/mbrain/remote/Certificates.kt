package com.powercess.mbrain.remote

import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

@Serializable
data class TunnelCertificate(val id: String = newId(), val name: String = "", val pem: String = "", val keyPem: String = "") {
    fun chain(): List<X509Certificate> = CertificateFactory.getInstance("X.509")
        .generateCertificates(pem.byteInputStream()).map { it as X509Certificate }.also { require(it.isNotEmpty()) { "证书文件为空" } }

    fun validate() {
        require(validId(id) && name.isNotBlank() && name.length <= 80) { "请填写证书名称（最多 80 字）" }
        require(pem.toByteArray().size in 1..65536 && keyPem.toByteArray().size <= 65536) { "证书或私钥超过 64 KiB" }
        val cert = runCatching { chain().first() }.getOrElse { throw IllegalArgumentException("无法读取 PEM X.509 证书") }
        if (keyPem.isNotBlank()) {
            require("BEGIN PRIVATE KEY" in keyPem || "BEGIN RSA PRIVATE KEY" in keyPem) { "请导入未加密的 PKCS#8 或 RSA PKCS#1 私钥" }
            try {
                val raw = Base64.getMimeDecoder().decode(keyPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
                val pkcs8 = if ("BEGIN RSA PRIVATE KEY" in keyPem) wrapRsaKey(raw) else raw
                val algorithm = when (cert.publicKey.algorithm) { "RSA" -> "SHA256withRSA"; "EC" -> "SHA256withECDSA"; else -> error("Unsupported key") }
                val privateKey = KeyFactory.getInstance(cert.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
                val probe = "MBrain certificate pair check".toByteArray()
                val signature = Signature.getInstance(algorithm).run { initSign(privateKey); update(probe); sign() }
                require(Signature.getInstance(algorithm).run { initVerify(cert.publicKey); update(probe); verify(signature) })
            } catch (_: Exception) { throw IllegalArgumentException("私钥格式无效或与证书不匹配（支持 RSA / EC）") }
        }
    }

    fun expiryMessage(now: Date = Date()): String {
        val certificate = chain().first()
        return when {
            now.before(certificate.notBefore) -> "尚未生效"
            now.after(certificate.notAfter) -> "已过期"
            certificate.notAfter.time - now.time < 30L * 86400000 -> "将在 30 天内到期"
            else -> "有效"
        }
    }
}

// Wrap the existing RSA PKCS#1 bytes in a PKCS#8 PrivateKeyInfo for Android's KeyFactory.
private fun wrapRsaKey(raw: ByteArray): ByteArray {
    fun der(tag: Int, bytes: ByteArray): ByteArray {
        val length = bytes.size
        val size = if (length < 128) byteArrayOf(length.toByte()) else {
            val parts = listOf(length ushr 24, length ushr 16, length ushr 8, length).map { (it and 255).toByte() }.dropWhile { it == 0.toByte() }
            byteArrayOf((128 or parts.size).toByte()) + parts.toByteArray()
        }
        return byteArrayOf(tag.toByte()) + size + bytes
    }
    val rsaIdentifier = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
    return der(0x30, byteArrayOf(0x02, 0x01, 0x00) + rsaIdentifier + der(0x04, raw))
}
