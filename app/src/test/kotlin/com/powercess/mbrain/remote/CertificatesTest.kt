package com.powercess.mbrain.remote

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import java.util.Date

internal fun testCertificate(algorithm: String = "RSA"): TunnelCertificate {
    val directory = Files.createTempDirectory("certificate-test").toFile()
    try {
        val keytool = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
        val file = File(directory, "test.p12")
        val password = "unit-test-only".toCharArray()
        val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", "test", "-storetype", "PKCS12",
            "-keyalg", algorithm, "-dname", "CN=localhost", "-validity", "30", "-storepass", String(password),
            "-keystore", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        fun pem(label: String, bytes: ByteArray) = "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) + "\n-----END $label-----"
        return TunnelCertificate(name = "test", pem = pem("CERTIFICATE", store.getCertificate("test").encoded),
            keyPem = pem("PRIVATE KEY", store.getKey("test", password).encoded))
    } finally { directory.deleteRecursively() }
}

class CertificatesTest {
    @Test fun `RSA and EC certificate pairs are validated and CA can omit a key`() {
        listOf("RSA", "EC").forEach { algorithm ->
            val certificate = testCertificate(algorithm)
            certificate.validate()
            certificate.copy(keyPem = "").validate()
            assertEquals("已过期", certificate.expiryMessage(Date(certificate.chain().first().notAfter.time + 1)))
            assertEquals("尚未生效", certificate.expiryMessage(Date(0)))
        }
    }

    @Test fun `mismatched key and malformed PEM are rejected`() {
        val first = testCertificate()
        val second = testCertificate()
        assertThrows(IllegalArgumentException::class.java) { first.copy(keyPem = second.keyPem).validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(pem = "not a certificate").validate() }
        assertThrows(IllegalArgumentException::class.java) { first.copy(keyPem = "-----BEGIN ENCRYPTED PRIVATE KEY-----").validate() }
    }
}
