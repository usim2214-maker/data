package com.signalassist.app.nav

import android.content.Context
import com.signalassist.core.NavigationState

/** 내비 안내 상태 공급원 */
interface NavigationDataSource {
    val name: String
    fun start()
    fun current(nowMs: Long): NavigationState?
    fun stop()
}

/**
 * Pleos NaviHelper 어댑터.
 *
 * 사용할 API (권한 그룹 NAVI_ROUTE):
 *  - getRouteStateInfo  : 경로 안내 중인지 -> NavigationState.guiding
 *  - getTBTInfo         : 다음 안내 지점(회전 종류·남은 거리·도로명) -> nextManeuver / distanceToManeuverM / roadName
 *  - getCurrentLocationInfo : 현재 위치 (교차로 진입 판단 고도화 시)
 *
 * 쓰임새:
 *  - 좌회전 화살표 신호가 '내 경로'에 해당하는지 판단 (색각이상)
 *  - 300m/100m 앞 안내를 큰 카드로 표시 (청각장애: 내비 음성을 못 들음)
 *  - 안내 지점 근처의 방향지시등은 정상으로 보고 '깜빡함' 알림을 보류
 *
 * TODO(Pleos): NaviHelper AAR 추가 후 위 API 를 호출해 [latest] 를 갱신한다.
 *  (NaviHelper 3.0.5 에서 좌표 순서·DestinationInfo 필드명이 바뀌었다고 하므로 최신 문서 기준으로 구현)
 *  연결 전에는 null 을 돌려주며, 이 경우 경로 관련 기능만 꺼지고 나머지는 정상 동작한다.
 */
class NaviHelperNavigationSource(@Suppress("unused") private val context: Context) : NavigationDataSource {
    override val name = "Pleos NaviHelper"

    @Volatile private var latest: NavigationState? = null

    override fun start() {
        // TODO(Pleos): NaviHelper 초기화 및 TBT 정보 주기 조회/구독
    }

    override fun current(nowMs: Long): NavigationState? = latest

    override fun stop() {
        latest = null
    }
}
