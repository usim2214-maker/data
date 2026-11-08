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
data class SynergyBonus(val name: String, val points: Int, val description: String)

@Serializable
data class SynergyScore(val total: Int, val base: Int, val travel: Int = 0, val bonuses: List<SynergyBonus>)

@Serializable
data class CollectionResponse(val total: Int, val entries: List<CollectionEntry>, val synergy: SynergyScore)

/** 지역 스탬프 = 쇼룸 배경 테마 */
@Serializable
data class Region(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val landmark: String,
    val emoji: String,
    val colors: List<String>, // [시작, 끝] #RRGGBB
    @SerialName("travel_points") val travelPoints: Int = 10, // 첫 방문 점수 (지방일수록 높음)
    val area: String = "",
)

@Serializable
data class Stamp(
    val region: Region,
    val acquired: Boolean,
    @SerialName("first_visited_at") val firstVisitedAt: String? = null,
    @SerialName("visit_count") val visitCount: Int = 0,
)

/** TourAPI 관광지/축제 */
@Serializable
data class Attraction(
    @SerialName("content_id") val contentId: String,
    val title: String,
    val addr: String = "",
    val lat: Double,
    val lon: Double,
    val image: String? = null,
    val kind: String = "attraction", // attraction | festival
    @SerialName("event_start") val eventStart: String? = null,
    @SerialName("event_end") val eventEnd: String? = null,
    @SerialName("region_id") val regionId: String? = null,
)

@Serializable
data class HiddenStamp(val attraction: Attraction, @SerialName("acquired_at") val acquiredAt: String, val points: Int)

@Serializable
data class LandmarkNearby(
    val attraction: Attraction,
    @SerialName("distance_m") val distanceM: Int,
    val collected: Boolean,
    val points: Int,
)

@Serializable
data class LandmarkVisitResponse(
    val acquired: Boolean,
    @SerialName("is_new") val isNew: Boolean = false,
    val stamp: HiddenStamp? = null,
    val message: String = "",
)

@Serializable
data class StampBook(val acquired: Int, val total: Int, val stamps: List<Stamp>, val hidden: List<HiddenStamp> = emptyList())

// ---------------- AI 드라이브 코스 ----------------
@Serializable
data class CourseRequest(
    val lat: Double,
    val lon: Double,
    @SerialName("max_hours") val maxHours: Double,
    val note: String = "",
)

@Serializable
data class CourseStop(
    @SerialName("content_id") val contentId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    @SerialName("region_id") val regionId: String? = null,
    val reason: String,
    @SerialName("new_region_stamp") val newRegionStamp: Boolean = false,
    @SerialName("new_hidden_stamp") val newHiddenStamp: Boolean = false,
)

@Serializable
data class DriveCourse(
    val title: String,
    val summary: String,
    val stops: List<CourseStop>,
    @SerialName("est_drive_hours") val estDriveHours: Double,
    @SerialName("expected_points") val expectedPoints: Int,
    @SerialName("candidates_source") val candidatesSource: String,
)

@Serializable
data class VisitRequest(val lat: Double, val lon: Double)

@Serializable
data class VisitResponse(val stamp: Stamp? = null, @SerialName("is_new") val isNew: Boolean = false, val message: String = "")

/** 카트라이더식 쇼룸: 대표 차량 + 스탬프로 꾸민 배경/스티커 */
@Serializable
data class Showroom(val car: VehicleCard? = null, val background: Region? = null, val stickers: List<Region> = emptyList())

@Serializable
data class ShowroomUpdate(
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("background_region_id") val backgroundRegionId: String? = null,
    @SerialName("sticker_region_ids") val stickerRegionIds: List<String> = emptyList(),
)

const val MAX_STICKERS = 3
