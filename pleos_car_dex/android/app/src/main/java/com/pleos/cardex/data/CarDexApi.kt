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

    @Multipart
    @POST("api/v1/quiz/question")
    suspend fun quiz(@Part image: MultipartBody.Part): QuizResponse

    @GET("api/v1/quiz/stars")
    suspend fun quizStars(): QuizStars

    @POST("api/v1/quiz/star")
    suspend fun quizStar(@Body req: QuizStarRequest): QuizStars

    @GET("api/v1/collection")
    suspend fun collection(): CollectionResponse

    @GET("api/v1/collection/catalog")
    suspend fun catalog(): List<CatalogVehicle>

    @DELETE("api/v1/collection/{id}")
    suspend fun deleteEntry(@Path("id") id: String)

    @GET("api/v1/showroom")
    suspend fun showroom(): Showroom

    @GET("api/v1/showroom/items")
    suspend fun decorItems(): DecorCatalog

    @PUT("api/v1/showroom")
    suspend fun updateShowroom(@Body req: ShowroomUpdate): Showroom

    @GET("api/v1/stamps")
    suspend fun stamps(): StampBook

    @POST("api/v1/stamps/visit")
    suspend fun visit(@Body req: VisitRequest): VisitResponse

    @GET("api/v1/landmarks/nearby")
    suspend fun landmarksNearby(
        @retrofit2.http.Query("lat") lat: Double,
        @retrofit2.http.Query("lon") lon: Double,
        @retrofit2.http.Query("radius_m") radiusM: Int = 20_000,
    ): List<LandmarkNearby>

    @POST("api/v1/landmarks/{id}/visit")
    suspend fun visitLandmark(@Path("id") id: String, @Body req: VisitRequest): LandmarkVisitResponse

    @POST("api/v1/courses/candidates")
    suspend fun courseCandidates(@Body req: CourseRequest): CourseCandidates

    @POST("api/v1/courses/build")
    suspend fun buildCourse(@Body req: CourseBuildRequest): DriveCourse

    @POST("api/v1/courses/recommend")
    suspend fun recommendCourse(@Body req: CourseRequest): DriveCourse

    @POST("api/v1/challenge")
    suspend fun startChallenge(@Body course: DriveCourse): Challenge

    @GET("api/v1/challenge")
    suspend fun challenge(): Challenge?

    @DELETE("api/v1/challenge")
    suspend fun abandonChallenge()

    @GET("api/v1/rankings/weekly")
    suspend fun weeklyRanking(@retrofit2.http.Query("region_id") regionId: String? = null): Leaderboard

    @GET("api/v1/rankings/masters")
    suspend fun masters(): MastersResponse

    @POST("api/v1/trips/report")
    suspend fun tripReport(@Body req: TripReportRequest): TripReport

    @POST("api/v1/daily/checkin")
    suspend fun checkin(): CheckinResponse

    @GET("api/v1/home")
    suspend fun home(): HomeSummary

    @POST("api/v1/demo/seed")
    suspend fun demoSeed(): kotlinx.serialization.json.JsonObject

    @POST("api/v1/demo/reset")
    suspend fun demoReset(): kotlinx.serialization.json.JsonObject

    @GET("api/v1/preferences")
    suspend fun preferences(): Preferences

    @GET("api/v1/preferences/fields")
    suspend fun fields(): List<String>

    @PUT("api/v1/preferences")
    suspend fun savePreferences(@Body prefs: Preferences): Preferences

    companion object {
        val baseUrl: String = BuildConfig.API_BASE_URL

        fun create(): CarDexApi {
            val json = Json { ignoreUnknownKeys = true }
            val client = OkHttpClient.Builder()
                .readTimeout(90, TimeUnit.SECONDS) // YOLO + LLM / 코스 추천(TourAPI + LLM)
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(CarDexApi::class.java)
        }

        /** 백엔드가 주는 상대 경로(/static/crops/..)를 절대 URL 로 변환 */
        fun absolute(url: String?): String? = url?.let { if (it.contains("://")) it else baseUrl.trimEnd('/') + it }
    }
}
