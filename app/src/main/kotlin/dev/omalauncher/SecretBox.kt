package dev.omalauncher

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts widgets' `secret` states with an AES key in Android's keystore, which never leaves it:
 * the files are useless to Linux, a backup, or another install. AES-GCM, the 12-byte IV first.
 */
object SecretBox {
    private const val ALIAS = "owl-secrets"
    private const val IV = 12
    private const val CIPHER = "AES/GCM/NoPadding"

    fun seal(plain: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key()) }
            cipher.iv + cipher.doFinal(plain)
        }.onFailure { Log.w(TAG, "can't encrypt", it) }.getOrNull()

    fun unseal(sealed: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV)) }
            cipher.doFinal(sealed, IV, sealed.size - IV)
        }.onFailure { Log.w(TAG, "can't decrypt", it) }.getOrNull()

    /** Loaded once: each keystore load is a call into another process. */
    private var cached: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { cached = it; return it }
        val spec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey().also { cached = it }
    }

    private const val TAG = "secret-box"
}
