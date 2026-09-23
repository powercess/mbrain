package com.powercess.mbrain.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, backup-excluded encrypted preferences. Fail closed on unreadable keys. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote_secrets", Context.MODE_PRIVATE)
    private val alias = "mbrain.remote.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        val parts = encoded.split(':')
        require(parts.size == 2) { "凭据无法读取" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            updateAAD(name.toByteArray())
            doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }
    @Synchronized fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray())
        }
        val encrypted = cipher.doFinal(value.toByteArray())
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "凭据保存失败" }
    }
    @Synchronized fun gatewayToken(): String = read("gateway_token") ?: newToken().also { write("gateway_token", it) }
    companion object {
        fun newToken(): String = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
}
