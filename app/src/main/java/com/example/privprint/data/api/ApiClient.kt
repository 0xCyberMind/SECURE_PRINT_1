package com.example.privprint.data.api

import com.example.BuildConfig
import com.example.privprint.data.api.models.RefreshTokenRequest
import com.example.privprint.data.auth.AuthTokenManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    const val PROD_BASE_URL = "https://secure-print-1.onrender.com/"
    const val DEV_BASE_URL = "https://ais-dev-6u62dc37mqabbjyehi6umo-408539472511.asia-southeast1.run.app/"
    const val DEFAULT_BASE_URL = BuildConfig.API_BASE_URL

    private var baseUrl: String = DEFAULT_BASE_URL
    private var tokenManager: AuthTokenManager? = null

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    fun init(tokenManager: AuthTokenManager, baseUrl: String = DEFAULT_BASE_URL) {
        this.tokenManager = tokenManager
        this.baseUrl = baseUrl
    }

    private val authInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val accessToken = tokenManager?.getAccessToken()

        val requestBuilder = originalRequest.newBuilder()
        if (!accessToken.isNullOrEmpty() && originalRequest.header("Authorization") == null) {
            requestBuilder.header("Authorization", "Bearer $accessToken")
        }

        chain.proceed(requestBuilder.build())
    }

    private val tokenAuthenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.url.encodedPath.contains("/auth/refresh")) {
                return null
            }

            val tm = tokenManager ?: return null
            val refreshToken = tm.getRefreshToken() ?: return null

            synchronized(this) {
                val currentAccessToken = tm.getAccessToken()
                val requestToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                if (currentAccessToken != null && currentAccessToken != requestToken) {
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $currentAccessToken")
                        .build()
                }

                try {
                    val refreshService = Retrofit.Builder()
                        .baseUrl(baseUrl)
                        .addConverterFactory(MoshiConverterFactory.create(moshi))
                        .build()
                        .create(PrivPrintApiService::class.java)

                    val refreshCall = refreshService.refreshTokenSync(RefreshTokenRequest(refreshToken))
                    val refreshResponse = refreshCall.execute()

                    if (refreshResponse.isSuccessful && refreshResponse.body() != null) {
                        val body = refreshResponse.body()!!
                        tm.updateTokens(body.accessToken, body.refreshToken)

                        return response.request.newBuilder()
                            .header("Authorization", "Bearer ${body.accessToken}")
                            .build()
                    } else {
                        tm.clearSession()
                    }
                } catch (e: Exception) {
                    tm.clearSession()
                }
            }
            return null
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Redact authorization tokens & sensitive keys in logs
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Idempotency-Key")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    val apiService: PrivPrintApiService by lazy {
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(PrivPrintApiService::class.java)
    }
}
