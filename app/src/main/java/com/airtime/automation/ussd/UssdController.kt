package com.airtime.automation.ussd

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.telephony.SubscriptionManager
import androidx.annotation.RequiresApi
import timber.log.Timber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UssdController @Inject constructor(
    private val context: Context
) {
    private var pendingResult: CompletableDeferred<String>? = null

    /**
     * Execute USSD code on specified SIM slot
     * Returns the USSD response or error message
     */
    @SuppressLint("MissingPermission")
    suspend fun executeUssd(ussdCode: String, simSlot: Int): Result<String> {
        return try {
            // Clean the USSD code
            val cleanCode = ussdCode.replace("#", "").replace("*", "")
            val fullCode = "*$cleanCode#"

            Timber.d("Executing USSD: $fullCode on SIM slot $simSlot")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                executeUssdNewApi(fullCode, simSlot)
            } else {
                executeUssdLegacy(fullCode, simSlot)
            }
        } catch (e: Exception) {
            Timber.e(e, "USSD execution failed")
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun executeUssdNewApi(ussdCode: String, simSlot: Int): Result<String> {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        return try {
            pendingResult = CompletableDeferred()

            // Get subscription ID for the specified SIM slot
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subscriptionId = getSubscriptionIdForSlot(subscriptionManager, simSlot)

            if (subscriptionId == -1) {
                return Result.failure(Exception("SIM slot $simSlot not available"))
            }

            // Create callback
            val callback = object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    Timber.d("USSD Response received: $response")
                    pendingResult?.complete(response.toString())
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    val error = when (failureCode) {
                        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "Service unavailable"
                        TelephonyManager.USSD_RETURN_FAILURE -> "USSD return failure"
                        else -> "Unknown error: $failureCode"
                    }
                    Timber.e("USSD Failed: $error")
                    pendingResult?.completeExceptionally(Exception(error))
                }
            }

            // Execute USSD
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.sendUssdRequest(ussdCode, callback, Handler(Looper.getMainLooper()))
            }

            // Wait for result with timeout
            val result = withTimeout(30000) {
                pendingResult?.await() ?: throw Exception("No result received")
            }

            Result.success(result)
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("USSD request timed out"))
        } catch (e: SecurityException) {
            // Fallback to accessibility service
            executeViaAccessibility(ussdCode, simSlot)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pendingResult = null
        }
    }

    private fun executeUssdLegacy(ussdCode: String, simSlot: Int): Result<String> {
        return try {
            // Use reflection for older devices
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Try to get TelephonyManager for specific SIM
            val telephonyClass = Class.forName(telephonyManager.javaClass.name)
            val method: Method = telephonyClass.getDeclaredMethod("getITelephony")
            method.isAccessible = true
            
            val telephonyStub = method.invoke(telephonyManager)
            val telephonyStubClass = Class.forName(telephonyStub.javaClass.name)
            
            // Handle dual SIM
            val slotMethod = telephonyStubClass.getDeclaredMethod("setDefaultSim", Int::class.java)
            slotMethod.invoke(telephonyStub, simSlot)

            // This is a simplified version - real implementation needs proper handling
            val ussdMethod = telephonyStubClass.getDeclaredMethod(
                "handlePinMmi",
                String::class.java
            )
            val result = ussdMethod.invoke(telephonyStub, ussdCode) as Boolean
            
            if (result) {
                Result.success("USSD initiated")
            } else {
                Result.failure(Exception("Failed to initiate USSD"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Legacy USSD failed")
            // Fallback to dialer intent (will show UI - not silent)
            fallbackToDialer(ussdCode)
        }
    }

    private fun executeViaAccessibility(ussdCode: String, simSlot: Int): Result<String> {
        // Trigger accessibility service to handle USSD silently
        val intent = Intent(context, UssdAccessibilityService::class.java).apply {
            action = "EXECUTE_USSD"
            putExtra("ussd_code", ussdCode)
            putExtra("sim_slot", simSlot)
        }
        context.startService(intent)
        
        // This would need proper implementation with accessibility service callback
        return Result.success("Accessibility service triggered")
    }

    private fun fallbackToDialer(ussdCode: String): Result<String> {
        // This shows UI - use only as last resort
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:${Uri.encode(ussdCode)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return Result.success("Dialer opened (non-silent mode)")
    }

    @SuppressLint("MissingPermission")
    private fun getSubscriptionIdForSlot(subscriptionManager: SubscriptionManager, slot: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val subInfo = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slot)
            subInfo?.subscriptionId ?: -1
        } else {
            // Legacy method
            val subList = subscriptionManager.activeSubscriptionInfoList
            subList.find { it.simSlotIndex == slot }?.subscriptionId ?: -1
        }
    }

    /**
     * Construct USSD string for airtime transfer
     * Format: *180*6*1*PHONE*AMOUNT*1*PIN#
     */
    fun constructAirtimeUssd(phone: String, amount: String, pin: String): String {
        // Validate inputs
        val cleanPhone = phone.replace("[^0-9]".toRegex(), "")
        val cleanAmount = amount.replace("[^0-9]".toRegex(), "")
        
        return "*180*6*1*$cleanPhone*$cleanAmount*1*$pin#"
    }
}
