package com.airtime.automation.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurePrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_airtime_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_EMAIL = "email"
        private const val KEY_SIM_SLOT = "sim_slot"
        private const val KEY_ETOP_PIN = "etop_pin"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_REGISTERED = "is_registered"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
    }

    var deviceId: String?
        get() = securePrefs.getString(KEY_DEVICE_ID, null)
        set(value) = securePrefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var token: String?
        get() = securePrefs.getString(KEY_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_TOKEN, value).apply()

    var apiKey: String?
        get() = securePrefs.getString(KEY_API_KEY, null)
        set(value) = securePrefs.edit().putString(KEY_API_KEY, value).apply()

    var email: String?
        get() = securePrefs.getString(KEY_EMAIL, null)
        set(value) = securePrefs.edit().putString(KEY_EMAIL, value).apply()

    var simSlot: Int
        get() = securePrefs.getInt(KEY_SIM_SLOT, 0)
        set(value) = securePrefs.edit().putInt(KEY_SIM_SLOT, value).apply()

    var etopPin: String?
        get() = securePrefs.getString(KEY_ETOP_PIN, null)
        set(value) = securePrefs.edit().putString(KEY_ETOP_PIN, value).apply()

    var deviceName: String?
        get() = securePrefs.getString(KEY_DEVICE_NAME, null)
        set(value) = securePrefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var isRegistered: Boolean
        get() = securePrefs.getBoolean(KEY_REGISTERED, false)
        set(value) = securePrefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    var tokenExpiry: Long
        get() = securePrefs.getLong(KEY_TOKEN_EXPIRY, 0)
        set(value) = securePrefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    fun clearAll() {
        securePrefs.edit().clear().apply()
    }

    fun isTokenValid(): Boolean {
        return token != null && System.currentTimeMillis() < tokenExpiry
    }
}
