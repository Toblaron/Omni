package com.omni.inbox

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

/**
 * Random secrets (database passphrases) wrapped by a hardware-backed Android Keystore key.
 * Only the wrapped form is stored; the Keystore key never leaves secure hardware. No user
 * authentication is required to unwrap, because the notification listener must write
 * messages in the background while the app is locked.
 */
object Vault {
    private const val ALIAS = "omni_vault"

    /** A stable random secret for [name], created on first use. */
    @Synchronized
    fun secret(ctx: Context, name: String): ByteArray {
        val prefs = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
        prefs.getString(name, null)?.let { return unwrap(Base64.decode(it, Base64.NO_WRAP)) }
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(name, Base64.encodeToString(wrap(secret), Base64.NO_WRAP)).commit()
        return secret
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain) // 12-byte IV prefix
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
