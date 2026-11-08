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
    val challenge: ChallengeUpdate? = null,
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

/** 축제 한정 코스 정보 */
@Serializable
data class LimitedEvent(
    @SerialName("content_id") val contentId: String,
    val title: String,
    @SerialName("event_end") val eventEnd: String,
)

@Serializable
data class DriveCourse(
    val title: String,
    val summary: String,
    val stops: List<CourseStop>,
    @SerialName("est_drive_hours") val estDriveHours: Double,
    @SerialName("expected_points") val expectedPoints: Int,
    @SerialName("candidates_source") val candidatesSource: String,
    val limited: LimitedEvent? = null,
    @SerialName("completion_bonus") val completionBonus: Int = 0,
)

// ---------------- 경쟁 ----------------
@Serializable
data class Challenge(
    val course: DriveCourse,
    @SerialName("started_at") val startedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val visited: List<String> = emptyList(),
    val status: String = "active", // active | completed | expired
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class LimitedBadge(
    val id: String,
    val title: String,
    @SerialName("festival_title") val festivalTitle: String,
    @SerialName("region_id") val regionId: String? = null,
    @SerialName("earned_at") val earnedAt: String,
)

@Serializable
data class ChallengeUpdate(
    val challenge: Challenge,
    @SerialName("just_completed") val justCompleted: Boolean = false,
    @SerialName("bonus_points") val bonusPoints: Int = 0,
    val badge: LimitedBadge? = null,
)

@Serializable
data class RankRow(
    val rank: Int,
    @SerialName("user_id") val userId: String,
    val nickname: String,
    val points: Int,
    @SerialName("is_me") val isMe: Boolean = false,
)

@Serializable
data class Leaderboard(
    val period: String,
    @SerialName("region_id") val regionId: String? = null,
    val rows: List<RankRow>,
    val me: RankRow? = null,
    @SerialName("gap_to_next") val gapToNext: Int? = null,
)

@Serializable
data class RegionMaster(
    val region: Region,
    val master: RankRow? = null,
    @SerialName("my_points") val myPoints: Int = 0,
    val gap: Int = 0,
    @SerialName("is_me") val isMe: Boolean = false,
)

@Serializable
data class MastersResponse(val period: String, val masters: List<RegionMaster>, @SerialName("my_titles") val myTitles: List<String>)

@Serializable
data class VisitRequest(val lat: Double, val lon: Double)

@Serializable
data class VisitResponse(val stamp: Stamp? = null, @SerialName("is_new") val isNew: Boolean = false, val message: String = "")

/** 카트라이더식 쇼룸: 대표 차량 + 스탬프로 꾸민 배경/스티커 */
@Serializable
data class Showroom(
    val car: VehicleCard? = null,
    val background: Region? = null,
    val stickers: List<Region> = emptyList(),
    @SerialName("background_mastered") val backgroundMastered: Boolean = false,
    val titles: List<String> = emptyList(),
    val badges: List<LimitedBadge> = emptyList(),
)

@Serializable
data class ShowroomUpdate(
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("background_region_id") val backgroundRegionId: String? = null,
    @SerialName("sticker_region_ids") val stickerRegionIds: List<String> = emptyList(),
)

const val MAX_STICKERS = 3
