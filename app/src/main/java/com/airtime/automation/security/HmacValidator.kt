package com.airtime.automation.security

import android.util.Base64
import timber.log.Timber
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HmacValidator @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }

    fun validateJobSignature(jobId: String, phoneNumber: String, amount: String, timestamp: Long, signature: String): Boolean {
        return try {
            val apiKey = securePrefs.apiKey ?: return false
            
            val data = "$jobId:$phoneNumber:$amount:$timestamp"
            val computedSignature = generateHmac(data, apiKey)
            
            // Constant-time comparison to prevent timing attacks
            val result = MessageDigest.isEqual(
                signature.toByteArray(),
                computedSignature.toByteArray()
            )
            
            if (!result) {
                Timber.w("HMAC validation failed for job $jobId")
            }
            
            result
        } catch (e: Exception) {
            Timber.e(e, "Error validating HMAC")
            false
        }
    }

    @Throws(NoSuchAlgorithmException::class, InvalidKeyException::class)
    private fun generateHmac(data: String, key: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
        mac.init(secretKey)
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}

// Extension for constant-time comparison
object MessageDigest {
    fun isEqual(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
