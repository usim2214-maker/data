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
    suspend fun garage() = api.garage()
    suspend fun summon(id: String) = api.summon(SummonRequest(id))
    suspend fun dismiss() = api.dismiss()
    suspend fun preferences() = api.preferences()
    suspend fun savePreferences(prefs: Preferences) = api.savePreferences(prefs)
}
