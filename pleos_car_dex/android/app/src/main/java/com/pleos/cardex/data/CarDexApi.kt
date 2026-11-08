package com.pleos.cardex.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pleos.cardex.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

interface CarDexApi {
    @Multipart
    @POST("api/v1/recognize")
    suspend fun recognize(
        @Part image: MultipartBody.Part,
        @Part("lat") lat: RequestBody?,
        @Part("lon") lon: RequestBody?,
        @Part("keywords") keywords: RequestBody? = null,
    ): RecognitionResponse

    @GET("api/v1/collection")
    suspend fun collection(): CollectionResponse

    @DELETE("api/v1/collection/{id}")
    suspend fun deleteEntry(@Path("id") id: String)

    @GET("api/v1/preferences")
    suspend fun preferences(): Preferences

    @PUT("api/v1/preferences")
    suspend fun savePreferences(@Body prefs: Preferences): Preferences

    companion object {
        val baseUrl: String = BuildConfig.API_BASE_URL

        fun create(): CarDexApi {
            val json = Json { ignoreUnknownKeys = true }
            val client = OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS) // YOLO + LLM 2회 호출
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CarDexApi::class.java)
        }

        /** 백엔드가 주는 상대 경로(/static/crops/..)를 절대 URL 로 변환 */
        fun absolute(url: String?): String? = url?.let { if (it.startsWith("http")) it else baseUrl.trimEnd('/') + it }
    }
}
