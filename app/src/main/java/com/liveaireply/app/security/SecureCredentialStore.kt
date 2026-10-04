package com.liveaireply.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the API key encrypted with a hardware backed Android Keystore key.
 *
 * The plaintext key never touches disk: what is written to SharedPreferences is
 * `iv || ciphertext`, and the AES/GCM key itself lives in the Keystore (StrongBox or
 * TEE where the device has one) and cannot be exported.
 *
 * Nothing about this class logs the key, and [apiKey] returns "" when unset so callers
 * fail with a clear "not configured" message instead of sending an empty bearer token.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("live_ai_reply_secrets", Context.MODE_PRIVATE)

    fun apiKey(): String = decrypt(prefs.getString(KEY_CIPHERTEXT, null)) ?: ""

    fun hasApiKey(): Boolean = !prefs.getString(KEY_CIPHERTEXT, null).isNullOrBlank()

    fun saveApiKey(plainKey: String) {
        if (plainKey.isBlank()) {
            clearApiKey()
            return
        }
        val encrypted = encrypt(plainKey)
        prefs.edit().putString(KEY_CIPHERTEXT, encrypted).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_CIPHERTEXT).apply()
    }

    /** A short masked form for display, e.g. "sk-o••••••••7890". Never the full key. */
    fun maskedApiKey(): String {
        val key = apiKey()
        return if (key.isBlank()) "" else LogRedactor.mask(key)
    }

    // ------------------------------------------------------------------ internals

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            val ivSize = GCM_IV_BYTES
            if (combined.size <= ivSize) return null
            val iv = combined.copyOfRange(0, ivSize)
            val ciphertext = combined.copyOfRange(ivSize, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            // A corrupted blob or a restored backup from another device: report "unset"
            // rather than crashing the app on launch.
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "live_ai_reply_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val KEY_CIPHERTEXT = "api_key_encrypted"
    }
}
