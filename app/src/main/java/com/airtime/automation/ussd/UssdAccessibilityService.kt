package com.airtime.automation.ussd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Accessibility Service to handle USSD dialogs silently
 * This is required for Android 10+ where direct USSD execution is restricted
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        var instance: UssdAccessibilityService? = null
        var pendingUssd: String? = null
        var expectedResponse: ((String) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.android.phone")
        }
        Timber.d("USSD Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val nodeInfo = event.source ?: return
            val text = event.text.joinToString(" ")
            
            // Check if this is a USSD dialog
            if (isUssdDialog(nodeInfo, text)) {
                handleUssdDialog(nodeInfo, text)
            }
            
            nodeInfo.recycle()
        }
    }

    private fun isUssdDialog(nodeInfo: AccessibilityNodeInfo, text: String): Boolean {
        return text.contains("USSD") || 
               text.contains("M-Pesa") ||
               text.contains("airtime") ||
               text.contains("Please wait") ||
               nodeInfo.findAccessibilityNodeInfosByText("OK").isNotEmpty() ||
               nodeInfo.findAccessibilityNodeInfosByText("Cancel").isNotEmpty()
    }

    private fun handleUssdDialog(nodeInfo: AccessibilityNodeInfo, text: String) {
        Timber.d("USSD Dialog detected: $text")
        
        // Capture the response
        expectedResponse?.invoke(text)
        
        // Auto-dismiss the dialog
        val okButtons = nodeInfo.findAccessibilityNodeInfosByText("OK")
        okButtons.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        val closeButtons = nodeInfo.findAccessibilityNodeInfosByText("Close")
        closeButtons.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        // Alternative: look for button by ID
        val buttons = nodeInfo.findAccessibilityNodeInfosByViewId("android:id/button1")
        buttons.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() {
        Timber.d("USSD Accessibility Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Send USSD request through accessibility (for Android 10+)
     */
    fun sendUssdRequest(ussdCode: String, callback: (String) -> Unit) {
        pendingUssd = ussdCode
        expectedResponse = callback
        
        // Open dialer with USSD code
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = android.net.Uri.parse("tel:${android.net.Uri.encode(ussdCode)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }
}
