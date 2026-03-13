package com.airtime.automation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airtime.automation.data.api.ApiService
import com.airtime.automation.databinding.ActivityMainBinding
import com.airtime.automation.security.SecurePrefs
import com.airtime.automation.service.JobPollingService
import com.airtime.automation.ui.registration.RegistrationActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var apiService: ApiService

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!securePrefs.isRegistered) {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
            return
        }

        setupDashboard()
        updateStatus()
    }

    private fun setupDashboard() {
        binding.tvDeviceId.text = "Device ID: ${securePrefs.deviceId}"
        binding.tvSimSlot.text = "SIM Slot: ${if (securePrefs.simSlot == 0) "SIM 1" else "SIM 2"}"
        binding.tvDeviceName.text = "Name: ${securePrefs.deviceName}"

        binding.btnStartService.setOnClickListener {
            if (!JobPollingService.isRunning) {
                startService(Intent(this, JobPollingService::class.java))
                Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }

        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, JobPollingService::class.java))
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        binding.btnLogout.setOnClickListener {
            securePrefs.clearAll()
            stopService(Intent(this, JobPollingService::class.java))
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
        }

        binding.swAutoStart.setOnCheckedChangeListener { _, isChecked ->
            // Save preference for boot auto-start
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putBoolean("auto_start", isChecked)
                .apply()
        }
    }

    private fun updateStatus() {
        binding.tvConnectionStatus.text = "Service: ${if (JobPollingService.isRunning) "Running" else "Stopped"}"
        binding.tvTokenStatus.text = "Token: ${if (securePrefs.isTokenValid()) "Valid" else "Expired"}"
        
        lifecycleScope.launch {
            try {
                val response = apiService.verifyToken(
                    deviceId = securePrefs.deviceId ?: "",
                    auth = "Bearer ${securePrefs.token ?: ""}"
                )
                binding.tvServerStatus.text = "Server: ${if (response.isSuccessful) "Connected" else "Error"}"
            } catch (e: Exception) {
                binding.tvServerStatus.text = "Server: Offline"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}
