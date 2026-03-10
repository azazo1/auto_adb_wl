package com.azazo1.auto_adb_wl_client.network

import com.azazo1.auto_adb_wl_client.data.AdbConnectRequest
import com.azazo1.auto_adb_wl_client.data.AdbPairRequest
import com.azazo1.auto_adb_wl_client.data.ScrcpyLaunchRequest
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * API 服务接口
 */
interface ApiService {
    @POST("/adb/connect")
    suspend fun adbConnect(@Body request: AdbConnectRequest): String

    @POST("/adb/pair")
    suspend fun adbPair(@Body request: AdbPairRequest): String

    @POST("/scrcpy/launch")
    suspend fun scrcpyLaunch(@Body request: ScrcpyLaunchRequest): String

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        private val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        fun create(baseUrl: String): ApiService {
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ApiService::class.java)
        }
    }
}
