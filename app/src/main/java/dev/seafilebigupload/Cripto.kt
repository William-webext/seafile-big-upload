package dev.seafilebigupload

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra il token con una chiave che resta nel Keystore di Android (non esce dal telefono e non si può copiare). */
object Cripto {
    private const val ALIAS = "token-seafile"
    private const val IV_LEN = 12

    private fun chiave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return g.generateKey()
    }

    fun cifra(testo: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chiave())
        val dati = c.iv + c.doFinal(testo.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(dati, Base64.NO_WRAP)
    }

    /** Stringa vuota se il dato non si può decifrare (chiave persa, dato corrotto): in quel caso serve rifare l'accesso. */
    fun decifra(cifrato: String): String = try {
        val b = Base64.decode(cifrato, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, b, 0, IV_LEN))
        String(c.doFinal(b, IV_LEN, b.size - IV_LEN), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }
}
