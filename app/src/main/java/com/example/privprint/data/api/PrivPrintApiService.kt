package com.example.privprint.data.api

import com.example.privprint.data.api.models.CleanupStatusDto
import com.example.privprint.data.api.models.CompleteUploadRequest
import com.example.privprint.data.api.models.CompleteUploadResponse
import com.example.privprint.data.api.models.CreateJobRequest
import com.example.privprint.data.api.models.CreateSessionRequest
import com.example.privprint.data.api.models.HealthResponse
import com.example.privprint.data.api.models.IncrementCopyResponse
import com.example.privprint.data.api.models.InitUploadRequest
import com.example.privprint.data.api.models.InitUploadResponse
import com.example.privprint.data.api.models.JobResponse
import com.example.privprint.data.api.models.LoginRequest
import com.example.privprint.data.api.models.LoginResponse
import com.example.privprint.data.api.models.NearbyShopDto
import com.example.privprint.data.api.models.OtpRequestResponse
import com.example.privprint.data.api.models.PhoneOtpRequest
import com.example.privprint.data.api.models.PhoneOtpVerifyRequest
import com.example.privprint.data.api.models.PermanentQrResponse
import com.example.privprint.data.api.models.PrinterDto
import com.example.privprint.data.api.models.RefreshTokenRequest
import com.example.privprint.data.api.models.RevokeSessionRequest
import com.example.privprint.data.api.models.SessionResponse
import com.example.privprint.data.api.models.ShopDto
import com.example.privprint.data.api.models.TokenResponse
import com.example.privprint.data.api.models.TokenRevocationRequest
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Canonical PrivPrint API Specification (/api/v1/...)
 * All sensitive endpoints require Bearer authentication.
 */
interface PrivPrintApiService {

    // Health
    @GET("healthz")
    suspend fun getHealth(): Response<HealthResponse>

    // Authentication & Token Lifecycle
    @POST("api/v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>

    @POST("api/v1/auth/phone/request-otp")
    suspend fun requestPhoneOtp(
        @Body request: PhoneOtpRequest
    ): Response<OtpRequestResponse>

    @POST("api/v1/auth/phone/verify-otp")
    suspend fun verifyPhoneOtp(
        @Body request: PhoneOtpVerifyRequest
    ): Response<TokenResponse>

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshTokenRequest
    ): Response<LoginResponse>

    @POST("api/v1/auth/refresh")
    fun refreshTokenSync(
        @Body request: RefreshTokenRequest
    ): retrofit2.Call<LoginResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") bearerToken: String
    ): Response<Unit>

    @POST("api/v1/auth/revoke")
    suspend fun revokeToken(
        @Header("Authorization") bearerToken: String,
        @Body request: TokenRevocationRequest
    ): Response<Unit>

    // Shops
    @GET("api/v1/shops")
    suspend fun getShops(): Response<List<ShopDto>>

    @GET("api/v1/shops/nearby")
    suspend fun getNearbyShops(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radiusKm: Double = 10.0
    ): Response<List<NearbyShopDto>>

    @GET("api/v1/shops/{id}")
    suspend fun getShopById(
        @Path("id") shopId: String
    ): Response<ShopDto>

    @GET("api/v1/shops/{id}/permanent-qr")
    suspend fun getShopPermanentQr(
        @Path("id") shopId: String
    ): Response<PermanentQrResponse>

    @GET("api/v1/shops/{id}/printers")
    suspend fun getShopPrinters(
        @Path("id") shopId: String
    ): Response<List<PrinterDto>>

    // Ephemeral Sessions (Pairing)
    @POST("api/v1/sessions")
    suspend fun createSession(
        @Header("Authorization") bearerToken: String,
        @Body request: CreateSessionRequest
    ): Response<SessionResponse>

    @GET("api/v1/sessions/{id}")
    suspend fun getSession(
        @Header("Authorization") bearerToken: String,
        @Path("id") sessionId: String
    ): Response<SessionResponse>

    @POST("api/v1/sessions/{id}/revoke")
    suspend fun revokeSession(
        @Header("Authorization") bearerToken: String,
        @Path("id") sessionId: String,
        @Body request: RevokeSessionRequest
    ): Response<Unit>

    // Document Ciphertext Upload
    @POST("api/v1/documents/init-upload")
    suspend fun initUpload(
        @Header("Authorization") bearerToken: String,
        @Body request: InitUploadRequest
    ): Response<InitUploadResponse>

    @POST("api/v1/documents/{uploadId}/chunk")
    suspend fun uploadCiphertextChunk(
        @Header("Authorization") bearerToken: String,
        @Path("uploadId") uploadId: String,
        @Body ciphertextBody: RequestBody
    ): Response<Unit>

    @POST("api/v1/documents/{uploadId}/complete-upload")
    suspend fun completeUpload(
        @Header("Authorization") bearerToken: String,
        @Path("uploadId") uploadId: String,
        @Body request: CompleteUploadRequest
    ): Response<CompleteUploadResponse>

    // Print Jobs (Customer)
    @POST("api/v1/jobs")
    suspend fun createJob(
        @Header("Authorization") bearerToken: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: CreateJobRequest
    ): Response<JobResponse>

    @GET("api/v1/jobs/{id}")
    suspend fun getJob(
        @Header("Authorization") bearerToken: String,
        @Path("id") jobId: String
    ): Response<JobResponse>

    @POST("api/v1/jobs/{id}/authorize")
    suspend fun authorizeJob(
        @Header("Authorization") bearerToken: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Path("id") jobId: String
    ): Response<JobResponse>

    // Print Queue & Hardware Spooling (Shop Operator / Print Device)
    @GET("api/v1/print/jobs")
    suspend fun getPrintQueue(
        @Header("Authorization") bearerToken: String,
        @Query("shopId") shopId: String
    ): Response<List<JobResponse>>

    @POST("api/v1/print/jobs/{id}/start")
    suspend fun startPrinting(
        @Header("Authorization") bearerToken: String,
        @Path("id") jobId: String
    ): Response<JobResponse>

    @POST("api/v1/print/jobs/{id}/increment-copy")
    suspend fun incrementCopy(
        @Header("Authorization") bearerToken: String,
        @Path("id") jobId: String
    ): Response<IncrementCopyResponse>

    @POST("api/v1/print/jobs/{id}/complete")
    suspend fun completeJob(
        @Header("Authorization") bearerToken: String,
        @Path("id") jobId: String
    ): Response<JobResponse>

    @POST("api/v1/print/jobs/{id}/fail")
    suspend fun failJob(
        @Header("Authorization") bearerToken: String,
        @Path("id") jobId: String,
        @Query("reason") reason: String
    ): Response<JobResponse>

    // Verified Cleanup & Storage Deletion
    @GET("api/v1/cleanup/status/{jobId}")
    suspend fun getCleanupStatus(
        @Header("Authorization") bearerToken: String,
        @Path("jobId") jobId: String
    ): Response<CleanupStatusDto>

    @POST("api/v1/cleanup/execute/{jobId}")
    suspend fun executeCleanup(
        @Header("Authorization") bearerToken: String,
        @Path("jobId") jobId: String
    ): Response<CleanupStatusDto>

    companion object {
        fun create(baseUrl: String = "http://10.0.2.2:8000/"): PrivPrintApiService {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            val retrofit = retrofit2.Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(retrofit2.converter.moshi.MoshiConverterFactory.create(moshi))
                .build()
            return retrofit.create(PrivPrintApiService::class.java)
        }
    }
}
