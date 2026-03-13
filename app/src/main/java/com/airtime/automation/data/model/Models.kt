package com.airtime.automation.data.model

import com.google.gson.annotations.SerializedName

data class DeviceRegistrationRequest(
    val email: String,
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val simSlot: Int,
    val etopPin: String,
    val fcmToken: String? = null
)

data class DeviceRegistrationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String,
    @SerializedName("api_key") val apiKey: String?,
    @SerializedName("device_token") val deviceToken: String?,
    @SerializedName("expires_at") val expiresAt: Long?
)

data class JobRequest(
    @SerializedName("device_id") val deviceId: String,
    val token: String
)

data class AirtimeJob(
    @SerializedName("job_id") val jobId: String,
    @SerializedName("phone_number") val phoneNumber: String,
    @SerializedName("airtime_amount") val airtimeAmount: String,
    val pin: String,
    @SerializedName("sim_slot") val simSlot: Int,
    @SerializedName("ussd_code") val ussdCode: String? = null,
    val signature: String,
    @SerializedName("timestamp") val timestamp: Long
)

data class JobResult(
    @SerializedName("job_id") val jobId: String,
    val status: String, // "success" or "failed"
    val message: String,
    @SerializedName("ussd_response") val ussdResponse: String? = null,
    @SerializedName("executed_at") val executedAt: Long = System.currentTimeMillis(),
    @SerializedName("device_id") val deviceId: String
)

data class HeartbeatRequest(
    @SerializedName("device_id") val deviceId: String,
    val token: String,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("network_status") val networkStatus: String,
    @SerializedName("sim_status") val simStatus: String
)

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("message") val message: String?
)

data class KillSwitchResponse(
    @SerializedName("active") val active: Boolean,
    @SerializedName("reason") val reason: String?
)
