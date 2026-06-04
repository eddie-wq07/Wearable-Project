package com.example.testwatch.kiosk

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinSet(): Boolean =
        prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(pin: String): Boolean {
        val saltStr = prefs.getString(KEY_SALT, null) ?: return false
        val hashStr = prefs.getString(KEY_HASH, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        val expected = Base64.decode(hashStr, Base64.NO_WRAP)
        val actual = pbkdf2(pin, salt)
        return constantTimeEquals(expected, actual)
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    private companion object {
        const val PREFS_NAME = "kiosk_pin"
        const val KEY_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val SALT_BYTES = 16
        const val ITERATIONS = 100_000
        const val KEY_BITS = 256
    }
}
