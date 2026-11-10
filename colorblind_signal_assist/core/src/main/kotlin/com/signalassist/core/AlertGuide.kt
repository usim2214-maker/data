package com.signalassist.core

/** 알림 배우기 한 항목 */
data class Lesson(val type: AlertType, val titleKo: String, val explanationKo: String)

/**
 * 알림 배우기: 소리 패턴은 운전자에게 새로운 '언어'라서 미리 익혀야 실제로 쓸 수 있다.
 * 프로필에 해당하는 알림만, 소리(또는 화면) 의미를 한 문장으로 설명한다.
 */
object AlertGuide {
    private val signal = listOf(
        Lesson(AlertType.GREEN_GO, "진행", "두 음이 올라가면 초록불입니다."),
        Lesson(AlertType.YELLOW_CAUTION, "주의", "한 음이 길게 울리면 황색 신호입니다."),
        Lesson(AlertType.RED_STOP, "정지", "세 음이 내려가면 빨간불입니다."),
        Lesson(AlertType.RED_URGENT, "긴급", "빠르게 반복되는 소리는 빨간불인데 감속이 필요하다는 뜻입니다."),
        Lesson(AlertType.LEFT_ARROW_FOR_ROUTE, "좌회전", "짧은 차임과 함께 좌회전 신호와 내 경로를 알려드립니다."),
        Lesson(AlertType.SIGNAL_LOST, "확인 필요", "신호등을 놓치면 침묵하지 않고 직접 확인하라고 알려드립니다."),
    )
    private val vehicle = listOf(
        Lesson(AlertType.SEATBELT_UNBUCKLED, "차량 경고", "같은 음이 두 번 울리면 차량 상태 경고입니다."),
        Lesson(AlertType.TURN_SIGNAL_LEFT_ON, "방향지시등", "방향지시등이 오래 켜져 있으면 알려드립니다."),
        Lesson(AlertType.REAR_SEAT_CHECK, "뒷좌석", "주차할 때 뒷좌석에 사람이 있었다면 알려드립니다."),
    )
    private val hearing = listOf(
        Lesson(AlertType.SIREN_NEARBY, "사이렌", "긴급차량 사이렌이 들리면 화면 전체가 깜빡입니다."),
        Lesson(AlertType.NAV_MANEUVER_SOON, "내비 안내", "내비 음성 대신 300m·100m 앞에서 큰 카드로 보여드립니다."),
    )

    fun lessonsFor(profile: DriverProfile): List<Lesson> = buildList {
        if (profile.has(DriverNeed.COLOR_VISION)) addAll(signal)
        else if (profile.has(DriverNeed.ATTENTION)) addAll(signal.filter { it.type == AlertType.RED_URGENT })
        if (VehicleFeature.WARNINGS in profile.vehicleFeatures || VehicleFeature.TURN_SIGNAL in profile.vehicleFeatures) addAll(vehicle)
        else add(vehicle.last())
        if (profile.has(DriverNeed.HEARING)) addAll(hearing)
    }

    /** 배우기용 전달 방식: 중재와 상관없이 프로필이 쓸 수 있는 감각을 모두 사용 */
    fun lessonPlan(profile: DriverProfile, type: AlertType): OutputPlan =
        profile.outputFor(Alert(type, 0)).let { it.copy(visualFlash = it.visualFlash || profile.has(DriverNeed.HEARING)) }
}

/**
 * 다시 듣기: 방금 놓친 알림(깜빡임을 못 봤거나, 음성을 못 들었거나, 중재로 화면에만 떴던 것)을 다시 전달한다.
 */
class AlertHistory(private val capacity: Int = 10) {
    private val items = ArrayDeque<Delivery>()

    fun record(deliveries: List<Delivery>) {
        for (d in deliveries) {
            items.addLast(d)
            while (items.size > capacity) items.removeFirst()
        }
    }

    val recent: List<Delivery> get() = items.toList().asReversed()

    /** 가장 최근 알림을 프로필의 모든 감각으로 다시 (중재로 조용해졌던 것도 소리·음성으로) */
    fun replayLast(profile: DriverProfile, nowMs: Long): Delivery? {
        val last = items.lastOrNull() ?: return null
        val plan = profile.outputFor(last.alert).let { it.copy(visualFlash = it.visualFlash || profile.has(DriverNeed.HEARING)) }
        return Delivery(last.alert.copy(timestampMs = nowMs), plan)
    }
}
