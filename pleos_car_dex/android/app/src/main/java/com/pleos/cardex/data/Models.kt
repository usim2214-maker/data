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
    @SerialName("is_my_car") val isMyCar: Boolean = false,
    @SerialName("vs_my_car") val vsMyCar: List<String> = emptyList(), // 카드 뒷면 '내 차와 비교'
)

@Serializable
data class RecognitionResponse(
    val detected: Boolean,
    val twin: Boolean = false, // 내 차와 같은 차(쌍둥이)
    @SerialName("daily_bonus") val dailyBonus: Int = 0, // 오늘의 차 보너스
    val detection: Detection? = null, // 카드로 만든 차량
    val detections: List<Detection> = emptyList(), // 화면 속 모든 차량
    val identity: VehicleIdentity? = null,
    val card: VehicleCard? = null,
    @SerialName("is_new_entry") val isNewEntry: Boolean = false,
    val message: String = "",
)

@Serializable
data class Preferences(val keywords: List<String>, val language: String = "ko", @SerialName("my_car_id") val myCarId: String? = null)

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
    val scene: String = "city", // 쇼룸 배경 풍경 (city|sea|mountain|hanok|island|field|lake|bridge|fortress)
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
    @SerialName("legendary_item") val legendaryItem: String? = null, // 브랜드 스팟 전설 아이템
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

/** 도감 칸 (미발견은 실루엣) */
@Serializable
data class CatalogVehicle(val id: String, val brand: String, val model: String, @SerialName("body_type") val bodyType: String, val rarity: String)

/** 도감/여행 탭 상단 '한눈에 보기' */
@Serializable
data class HomeSummary(
    @SerialName("dex_owned") val dexOwned: Int = 0,
    @SerialName("dex_total") val dexTotal: Int = 0,
    @SerialName("legendary_owned") val legendaryOwned: Int = 0,
    @SerialName("legendary_total") val legendaryTotal: Int = 0,
    @SerialName("synergy_total") val synergyTotal: Int,
    @SerialName("weekly_rank") val weeklyRank: Int,
    @SerialName("weekly_points") val weeklyPoints: Int,
    @SerialName("gap_to_next") val gapToNext: Int? = null,
    @SerialName("region_stamps") val regionStamps: Int,
    @SerialName("region_total") val regionTotal: Int,
    @SerialName("hidden_stamps") val hiddenStamps: Int,
    val collection: Int,
    @SerialName("items_unlocked") val itemsUnlocked: Int,
    @SerialName("items_total") val itemsTotal: Int,
    val titles: List<String> = emptyList(),
    @SerialName("challenge_title") val challengeTitle: String? = null,
    @SerialName("challenge_progress") val challengeProgress: String? = null,
    @SerialName("next_goal") val nextGoal: String,
    val daily: DailyMission? = null,
    @SerialName("brand_spots") val brandSpots: Int = 0,
    @SerialName("brand_total") val brandTotal: Int = 0,
)

/** 오늘의 차 미션 + 연속 출석 */
@Serializable
data class DailyMission(
    val date: String,
    val target: CatalogVehicle,
    val found: Boolean,
    val bonus: Int,
    val streak: Int,
    @SerialName("best_streak") val bestStreak: Int,
)

/** 아이용 차 맞히기 퀴즈 (YOLO 결과로 출제, 정답 포함 → 앱이 채점) */
@Serializable
data class QuizQuestion(
    val id: String,
    val type: String, // kind | color
    val prompt: String,
    val choices: List<String>,
    @SerialName("choice_icons") val choiceIcons: List<String>,
    val answer: String,
    val detection: Detection,
)

@Serializable
data class QuizResponse(val found: Boolean, val question: QuizQuestion? = null, val detections: List<Detection> = emptyList(), val message: String = "")

/** Pleos LLM 이 코스를 고를 수 있도록 서버가 주는 후보·프롬프트 */
@Serializable
data class CourseCandidates(val prompt: String, val source: String = "")

@Serializable
data class CourseBuildRequest(
    val request: CourseRequest,
    val title: String,
    val summary: String,
    @SerialName("stop_ids") val stopIds: List<String>,
    val reasons: List<String> = emptyList(),
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

/** 쇼룸 꾸미기 아이템 (slot: floor | light | pedestal | sign | prop) */
@Serializable
data class DecorItem(
    val id: String,
    val slot: String,
    val key: String, // prop=이모지, light=색(#RRGGBB), sign=문구, floor/pedestal=스타일 키
    val name: String,
    val unlocked: Boolean = false,
    @SerialName("unlock_hint") val unlockHint: String = "",
    val legendary: Boolean = false, // 브랜드 체험 스팟 전설 아이템
)

@Serializable
data class PlacedProp(
    @SerialName("item_id") val itemId: String,
    val x: Float = 0.5f,
    val y: Float = 0.8f,
    val scale: Float = 1f,
)

@Serializable
data class PlacedPropOut(
    @SerialName("item_id") val itemId: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val item: DecorItem,
)

@Serializable
data class DecorCatalog(val items: List<DecorItem>, val unlocked: Int, val total: Int)

/** 쇼룸(차고): 대표 차량 + 여행으로 얻은 아이템으로 꾸민 무대 */
@Serializable
data class Showroom(
    val car: VehicleCard? = null,
    val background: Region? = null,
    val floor: DecorItem? = null,
    val light: DecorItem? = null,
    val pedestal: DecorItem? = null,
    val sign: DecorItem? = null,
    @SerialName("sign_text") val signText: String = "MY GARAGE",
    val props: List<PlacedPropOut> = emptyList(),
    @SerialName("background_mastered") val backgroundMastered: Boolean = false,
    val titles: List<String> = emptyList(),
    val badges: List<LimitedBadge> = emptyList(),
) {
    fun toUpdate() = ShowroomUpdate(
        cardId = car?.id,
        backgroundRegionId = background?.id,
        floorId = floor?.id ?: "floor-concrete",
        lightId = light?.id ?: "light-white",
        pedestalId = pedestal?.id ?: "pedestal-basic",
        signId = sign?.id ?: "sign-garage",
        props = props.map { PlacedProp(it.itemId, it.x, it.y, it.scale) },
    )
}

@Serializable
data class ShowroomUpdate(
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("background_region_id") val backgroundRegionId: String? = null,
    @SerialName("floor_id") val floorId: String = "floor-concrete",
    @SerialName("light_id") val lightId: String = "light-white",
    @SerialName("pedestal_id") val pedestalId: String = "pedestal-basic",
    @SerialName("sign_id") val signId: String = "sign-garage",
    val props: List<PlacedProp> = emptyList(),
)

const val MAX_PROPS = 8

/** 주차 시 주행 리포트 요청 (거리·시간: Pleos Odometer 또는 위치 추정) */
@Serializable
data class TripReportRequest(
    @SerialName("started_at") val startedAt: String,
    @SerialName("distance_km") val distanceKm: Float,
    @SerialName("drive_minutes") val driveMinutes: Int,
    val source: String,
)

/** 주행 리포트: 이번 드라이브에서 모은 것 */
@Serializable
data class TripReport(
    @SerialName("distance_km") val distanceKm: Float,
    @SerialName("drive_minutes") val driveMinutes: Int,
    @SerialName("new_cards") val newCards: List<String> = emptyList(),
    @SerialName("new_legendary") val newLegendary: Int = 0,
    val stamps: List<String> = emptyList(),
    val points: Int = 0,
    @SerialName("daily_found") val dailyFound: Boolean = false,
    val streak: Int = 0,
    @SerialName("total_km") val totalKm: Float = 0f,
    val trips: Int = 0,
    val headline: String,
    val tts: String,
    @SerialName("next_goal") val nextGoal: String = "",
)

/** 퀴즈 별 + 쇼룸 스티커 진행 */
@Serializable
data class QuizStars(
    val stars: Int,
    @SerialName("unlocked_sticker") val unlockedSticker: String? = null,
    @SerialName("next_sticker") val nextSticker: String? = null,
    @SerialName("stars_to_next") val starsToNext: Int = 0,
    @SerialName("stickers_owned") val stickersOwned: Int = 0,
    @SerialName("stickers_total") val stickersTotal: Int = 0,
)

@Serializable
data class QuizStarRequest(@SerialName("question_id") val questionId: String)
