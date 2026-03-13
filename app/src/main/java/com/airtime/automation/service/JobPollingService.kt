package com.airtime.automation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.airtime.automation.MainActivity
import com.airtime.automation.R
import com.airtime.automation.data.api.ApiService
import com.airtime.automation.data.model.AirtimeJob
import com.airtime.automation.data.model.HeartbeatRequest
import com.airtime.automation.data.model.JobRequest
import com.airtime.automation.data.model.JobResult
import com.airtime.automation.security.HmacValidator
import com.airtime.automation.security.SecurePrefs
import com.airtime.automation.ussd.UssdController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class JobPollingService : Service() {

    @Inject lateinit var apiService: ApiService
    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var ussdController: UssdController
    @Inject lateinit var hmacValidator: HmacValidator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        const val CHANNEL_ID = "AirtimeAutomationChannel"
        const val NOTIFICATION_ID = 1
        const val POLLING_INTERVAL = 5000L // 5 seconds
        const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AirtimeAutomation::WakeLock"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!securePrefs.isRegistered || !securePrefs.isTokenValid()) {
            Timber.e("Service started but device not registered or token expired")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        wakeLock.acquire(10*60*1000L) // 10 minutes timeout
        
        isRunning = true
        startPolling()
        startHeartbeat()

        return START_STICKY
    }

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (isActive && securePrefs.isTokenValid()) {
                try {
                    checkForJobs()
                    delay(POLLING_INTERVAL)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error in polling loop")
                    delay(POLLING_INTERVAL * 2) // Back off on error
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    sendHeartbeat()
                    delay(HEARTBEAT_INTERVAL)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Heartbeat failed")
                }
            }
        }
    }

    private suspend fun checkForJobs() {
        val deviceId = securePrefs.deviceId ?: return
        val token = securePrefs.token ?: return
        val apiKey = securePrefs.apiKey ?: return

        try {
            val response = apiService.getJob(
                request = JobRequest(deviceId, token),
                auth = "Bearer $token",
                apiKey = apiKey
            )

            if (response.isSuccessful) {
                val job = response.body()?.data
                job?.let {
                    if (validateJob(it)) {
                        processJob(it)
                    } else {
                        reportJobFailure(it.jobId, "Security validation failed")
                    }
                }
            } else if (response.code() == 401) {
                // Token expired
                handleTokenExpiry()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch job")
        }
    }

    private fun validateJob(job: AirtimeJob): Boolean {
        // Check timestamp (prevent replay attacks)
        val currentTime = System.currentTimeMillis()
        if (Math.abs(currentTime - job.timestamp) > 300000) { // 5 minute window
            Timber.w("Job timestamp too old")
            return false
        }

        // Validate HMAC signature
        return hmacValidator.validateJobSignature(
            job.jobId,
            job.phoneNumber,
            job.airtimeAmount,
            job.timestamp,
            job.signature
        )
    }

    private suspend fun processJob(job: AirtimeJob) {
        Timber.d("Processing job ${job.jobId}")

        // Construct USSD code
        val ussdCode = job.ussdCode ?: ussdController.constructAirtimeUssd(
            job.phoneNumber,
            job.airtimeAmount,
            job.pin
        )

        // Execute USSD
        val result = ussdController.executeUssd(ussdCode, job.simSlot)

        // Report result
        val jobResult = when {
            result.isSuccess -> JobResult(
                jobId = job.jobId,
                status = "success",
                message = "Airtime sent successfully",
                ussdResponse = result.getOrNull(),
                deviceId = securePrefs.deviceId ?: ""
            )
            else -> JobResult(
                jobId = job.jobId,
                status = "failed",
                message = result.exceptionOrNull()?.message ?: "Unknown error",
                deviceId = securePrefs.deviceId ?: ""
            )
        }

        submitJobResult(jobResult)
        updateNotification("Last job: ${jobResult.status}")
    }

    private suspend fun submitJobResult(result: JobResult) {
        try {
            val token = securePrefs.token ?: return
            val apiKey = securePrefs.apiKey ?: return
            
            apiService.submitJobResult(
                result = result,
                auth = "Bearer $token",
                apiKey = apiKey
            )
            Timber.d("Job result submitted: ${result.jobId}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to submit job result")
            // Store locally for retry
            storeFailedResult(result)
        }
    }

    private fun storeFailedResult(result: JobResult) {
        // Implement local storage for retry logic
        val prefs = getSharedPreferences("pending_results", Context.MODE_PRIVATE)
        val pending = prefs.getStringSet("pending", mutableSetOf()) ?: mutableSetOf()
        pending.add("${result.jobId}|${result.status}|${result.message}")
        prefs.edit().putStringSet("pending", pending).apply()
    }

    private suspend fun sendHeartbeat() {
        try {
            val deviceId = securePrefs.deviceId ?: return
            val token = securePrefs.token ?: return
            
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            
            val request = HeartbeatRequest(
                deviceId = deviceId,
                token = token,
                batteryLevel = batteryLevel,
                networkStatus = getNetworkStatus(),
                simStatus = getSimStatus()
            )

            val response = apiService.sendHeartbeat(
                request = request,
                auth = "Bearer $token"
            )

            // Check kill switch
            response.body()?.data?.let { killSwitch ->
                if (!killSwitch.active) {
                    Timber.w("Kill switch activated: ${killSwitch.reason}")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Heartbeat failed")
        }
    }

    private fun getNetworkStatus(): String {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) 
            as android.net.ConnectivityManager
        val networkInfo = connectivityManager.activeNetworkInfo
        return when {
            networkInfo == null -> "disconnected"
            networkInfo.isConnected -> "connected"
            else -> "unknown"
        }
    }

    private fun getSimStatus(): String {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        return when (telephonyManager.simState) {
            android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
            android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
            else -> "unknown"
        }
    }

    private suspend fun reportJobFailure(jobId: String, reason: String) {
        val result = JobResult(
            jobId = jobId,
            status = "failed",
            message = reason,
            deviceId = securePrefs.deviceId ?: ""
        )
        submitJobResult(result)
    }

    private fun handleTokenExpiry() {
        securePrefs.isRegistered = false
        stopSelf()
        // Notify user to re-register
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("TOKEN_EXPIRED", true)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Airtime Automation Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for USSD automation"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Airtime Automation Active")
            .setContentText("Listening for airtime jobs...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Airtime Automation Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        
        // Restart service if still registered (for persistent operation)
        if (securePrefs.isRegistered && securePrefs.isTokenValid()) {
            val intent = Intent(this, JobPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
