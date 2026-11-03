package com.derfurt.deryurt.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.derfurt.deryurt.core.Credentials
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Kimlik bilgilerini Android Keystore'daki AES-256-GCM anahtarıyla şifreleyip
 * noBackupFilesDir içinde saklar (yedeğe ve cihaz taşımaya girmez).
 *
 * Düz SharedPreferences yok, log yok. Anahtar cihazdan çıkarılamaz.
 */
class CredentialStore(context: Context) {

    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    fun hasCredentials(): Boolean = file.exists()

    fun save(credentials: Credentials) {
        val plain = (credentials.username + SEPARATOR + credentials.password).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain)
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.outputStream().use { out ->
                out.write(iv.size)
                out.write(iv)
                out.write(encrypted)
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Okunamazsa (anahtar silinmiş, dosya bozuk) bilgileri temizler ve null döner; uygulama tekrar sorar. */
    fun load(): Credentials? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val encrypted = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(encrypted)
            val text = String(plain, Charsets.UTF_8)
            plain.fill(0)
            val i = text.indexOf(SEPARATOR)
            if (i < 0) null else Credentials(text.substring(0, i), text.substring(i + 1))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        file.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val FILE_NAME = "credentials.bin"
        private const val KEY_ALIAS = "deryurt_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val SEPARATOR = '\u0000'

        /** Ekranda gösterim: "12345678901" → "123 •••• •• 01". */
        fun mask(username: String): String {
            val u = username.trim()
            if (u.length < 6) return "•".repeat(u.length.coerceAtLeast(4))
            return u.take(3) + " •••• •• " + u.takeLast(2)
        }
    }
}
