package com.airtime.automation.data.api

import com.airtime.automation.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    
    @POST("api/register-device")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest
    ): Response<ApiResponse<DeviceRegistrationResponse>>

    @POST("api/get-job")
    suspend fun getJob(
        @Body request: JobRequest,
        @Header("Authorization") auth: String,
        @Header("X-API-Key") apiKey: String
    ): Response<ApiResponse<AirtimeJob>>

    @POST("api/job-result")
    suspend fun submitJobResult(
        @Body result: JobResult,
        @Header("Authorization") auth: String,
        @Header("X-API-Key") apiKey: String
    ): Response<ApiResponse<Unit>>

    @POST("api/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: HeartbeatRequest,
        @Header("Authorization") auth: String
    ): Response<ApiResponse<KillSwitchResponse>>

    @GET("api/verify-token")
    suspend fun verifyToken(
        @Query("device_id") deviceId: String,
        @Header("Authorization") auth: String
    ): Response<ApiResponse<Boolean>>
}
