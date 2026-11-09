package com.pleos.cardex.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class CarDexRepository(private val api: CarDexApi = CarDexApi.create()) {

    suspend fun recognize(jpeg: ByteArray, fileName: String, location: GeoPoint?): RecognitionResponse {
        val text = "text/plain".toMediaType()
        val image = MultipartBody.Part.createFormData(
            "image", fileName, jpeg.toRequestBody("image/jpeg".toMediaType()),
        )
        return api.recognize(
            image = image,
            lat = location?.lat?.toString()?.toRequestBody(text),
            lon = location?.lon?.toString()?.toRequestBody(text),
        )
    }

    suspend fun collection() = api.collection()
    suspend fun delete(id: String) = api.deleteEntry(id)
    suspend fun catalog() = api.catalog()
    suspend fun showroom() = api.showroom()
    suspend fun updateShowroom(req: ShowroomUpdate) = api.updateShowroom(req)
    suspend fun decorItems() = api.decorItems()
    suspend fun stamps() = api.stamps()
    suspend fun visit(location: GeoPoint) = api.visit(VisitRequest(location.lat, location.lon))
    suspend fun landmarksNearby(at: GeoPoint) = api.landmarksNearby(at.lat, at.lon)
    suspend fun visitLandmark(id: String, at: GeoPoint) = api.visitLandmark(id, VisitRequest(at.lat, at.lon))
    suspend fun recommendCourse(at: GeoPoint, maxHours: Double, note: String) =
        api.recommendCourse(CourseRequest(at.lat, at.lon, maxHours, note))
    suspend fun startChallenge(course: DriveCourse) = api.startChallenge(course)
    suspend fun challenge() = api.challenge()
    suspend fun abandonChallenge() = api.abandonChallenge()
    suspend fun weeklyRanking(regionId: String?) = api.weeklyRanking(regionId)
    suspend fun masters() = api.masters()
    suspend fun home() = api.home()
    suspend fun demoSeed() = api.demoSeed()
    suspend fun demoReset() = api.demoReset()
    suspend fun preferences() = api.preferences()
    suspend fun fields() = api.fields()
    suspend fun savePreferences(prefs: Preferences) = api.savePreferences(prefs)
}
