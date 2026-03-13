package com.airtime.automation.ui.registration

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airtime.automation.MainActivity
import com.airtime.automation.data.api.ApiService
import com.airtime.automation.data.model.DeviceRegistrationRequest
import com.airtime.automation.databinding.ActivityRegistrationBinding
import com.airtime.automation.security.SecurePrefs
import com.airtime.automation.service.JobPollingService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class RegistrationActivity : AppCompatActivity() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var securePrefs: SecurePrefs

    private lateinit var binding: ActivityRegistrationBinding
    private val requiredPermissions = mutableListOf<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            proceedWithRegistration()
        } else {
            Toast.makeText(this, "All permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if already registered
        if (securePrefs.isRegistered && securePrefs.isTokenValid()) {
            startMainActivity()
            return
        }

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        // SIM Slot selector
        val simSlots = listOf("SIM 1", "SIM 2")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, simSlots)
        binding.spinnerSimSlot.setAdapter(adapter)

        binding.btnRegister.setOnClickListener {
            if (validateInputs()) {
                checkPermissions()
            }
        }
    }

    private fun validateInputs(): Boolean {
        var valid = true
        
        if (binding.etEmail.text.isNullOrBlank()) {
            binding.etEmail.error = "Email required"
            valid = false
        }
        
        if (binding.etToken.text.isNullOrBlank()) {
            binding.etToken.error = "Token required"
            valid = false
        }
        
        if (binding.etDeviceName.text.isNullOrBlank()) {
            binding.etDeviceName.error = "Device name required"
            valid = false
        }
        
        if (binding.etEtopPin.text.isNullOrBlank()) {
            binding.etEtopPin.error = "ETOP PIN required"
            valid = false
        }

        return valid
    }

    private fun checkPermissions() {
        requiredPermissions.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Manifest.permission.CALL_PHONE)
                add(Manifest.permission.READ_PHONE_STATE)
                add(Manifest.permission.READ_SMS)
                add(Manifest.permission.INTERNET)
            }
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            proceedWithRegistration()
        }
    }

    private fun proceedWithRegistration() {
        lifecycleScope.launch {
            try {
                binding.btnRegister.isEnabled = false
                binding.btnRegister.text = "Registering..."

                val deviceId = getOrCreateDeviceId()
                val simSlot = binding.spinnerSimSlot.selectedItemPosition

                val request = DeviceRegistrationRequest(
                    email = binding.etEmail.text.toString(),
                    token = binding.etToken.text.toString(),
                    deviceId = deviceId,
                    deviceName = binding.etDeviceName.text.toString(),
                    simSlot = simSlot,
                    etopPin = binding.etEtopPin.text.toString()
                )

                val response = apiService.registerDevice(request)

                if (response.isSuccessful && response.body()?.success == true) {
                    val data = response.body()?.data
                    data?.let {
                        // Save credentials securely
                        securePrefs.deviceId = deviceId
                        securePrefs.token = it.deviceToken
                        securePrefs.apiKey = it.apiKey
                        securePrefs.email = request.email
                        securePrefs.deviceName = request.deviceName
                        securePrefs.simSlot = simSlot
                        securePrefs.etopPin = request.etopPin
                        securePrefs.tokenExpiry = it.expiresAt ?: (System.currentTimeMillis() + 86400000)
                        securePrefs.isRegistered = true

                        // Start service
                        startService(Intent(this@RegistrationActivity, JobPollingService::class.java))
                        
                        Toast.makeText(this@RegistrationActivity, "Registration successful", Toast.LENGTH_SHORT).show()
                        startMainActivity()
                    }
                } else {
                    val error = response.body()?.message ?: "Registration failed"
                    Toast.makeText(this@RegistrationActivity, error, Toast.LENGTH_LONG).show()
                    binding.btnRegister.isEnabled = true
                    binding.btnRegister.text = "Register Device"
                }
            } catch (e: Exception) {
                Timber.e(e, "Registration error")
                Toast.makeText(this@RegistrationActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register Device"
            }
        }
    }

    private fun getOrCreateDeviceId(): String {
        return securePrefs.deviceId ?: run {
            val newId = "android_${UUID.randomUUID().toString().take(8)}"
            securePrefs.deviceId = newId
            newId
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
