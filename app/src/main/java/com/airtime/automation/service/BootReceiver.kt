package com.airtime.automation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.airtime.automation.security.SecurePrefs
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var securePrefs: SecurePrefs

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Timber.d("Boot completed received")
            
            if (securePrefs.isRegistered && securePrefs.isTokenValid()) {
                startAutomationService(context)
            }
        }
    }

    private fun startAutomationService(context: Context) {
        val serviceIntent = Intent(context, JobPollingService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        
        Timber.d("Automation service started from boot")
    }
}
