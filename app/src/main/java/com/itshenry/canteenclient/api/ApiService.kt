package com.itshenry.canteenclient.api

import com.itshenry.canteenclient.api.models.LoginRequest
import com.itshenry.canteenclient.api.models.LoginResponse
import com.itshenry.canteenclient.api.models.ScanRequest
import com.itshenry.canteenclient.api.models.ScanResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {
    @POST("/api/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("/api/canteen/scan")
    suspend fun scanQrCode(
        @Header("Authorization") token: String,
        @Body request: ScanRequest
    ): ScanResponse
}