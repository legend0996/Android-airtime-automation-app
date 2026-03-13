package com.airtime.automation.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * AccessibilityService stub for USSD automation.
 *
 * The actual USSD handling logic may live in a separate helper (e.g. UssdAccessibilityService),
 * but this service is required to satisfy the manifest declaration for the accessibility service.
 */
class UssdService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("UssdService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // No-op: this service is only here to satisfy the manifest requirement.
        // The USSD logic is handled by UssdAccessibilityService if needed.
    }

    override fun onInterrupt() {
        Timber.d("UssdService interrupted")
    }
}
