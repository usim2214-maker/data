package com.pleos.cardex.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// backend/app/schemas.py 와 1:1 대응

@Serializable
data class BoundingBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

@Serializable
data class Detection(val label: String, val confidence: Float, val bbox: BoundingBox)

@Serializable
data class VehicleIdentity(
    @SerialName("is_vehicle") val isVehicle: Boolean,
    val brand: String,
    val model: String,
    @SerialName("body_type") val bodyType: String,
    val confidence: Float,
)

@Serializable
data class KeywordFact(val keyword: String, val value: String)

@Serializable
data class VehicleCard(
    val id: String,
    val brand: String,
    val model: String,
    @SerialName("body_type") val bodyType: String,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("one_liner") val oneLiner: String,
    val facts: List<KeywordFact>,
    val rarity: String = "common",
)

@Serializable
data class RecognitionResponse(
    val detected: Boolean,
    val detection: Detection? = null,
    val identity: VehicleIdentity? = null,
    val card: VehicleCard? = null,
    @SerialName("is_new_entry") val isNewEntry: Boolean = false,
    val message: String = "",
)

@Serializable
data class Preferences(val keywords: List<String>, val language: String = "ko")

@Serializable
data class GeoPoint(val lat: Double, val lon: Double)

@Serializable
data class CollectionEntry(
    val card: VehicleCard,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("seen_count") val seenCount: Int = 1,
    @SerialName("first_seen_location") val firstSeenLocation: GeoPoint? = null,
)

@Serializable
data class CollectionResponse(val total: Int, val entries: List<CollectionEntry>)
