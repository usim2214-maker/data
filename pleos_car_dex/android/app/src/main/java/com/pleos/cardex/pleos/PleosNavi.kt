package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.pleos.cardex.data.CourseStop

/**
 * Pleos NaviHelper 래퍼: AI 추천 코스를 Pleos 내비게이션 경로로 보낸다.
 *
 * 주의: NaviHelper 공식 문서(document.pleos.ai)를 이 환경에서 열 수 없어,
 * 공개된 Pleos 앱들의 사용 코드를 기준으로 작성했다. 첫 빌드 때 SDK 시그니처를 확인할 것.
 * - 경유지는 WaypointIndex.FIRST / SECOND 두 개까지만 사용 (목적지 포함 최대 3곳)
 */
class PleosNavi(context: Context) {
    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    var onError: (String) -> Unit = {}
    /** 최근 목적지·즐겨찾기(집/회사 등) — 메인 화면 추천에 사용 */
    var onPlaces: (recent: List<NaviPlace>, bookmarks: List<NaviPlace>) -> Unit = { _, _ -> }
    private var recent: List<NaviPlace> = emptyList()
    private var bookmarks: List<NaviPlace> = emptyList()

    private val listener = object : NaviHelperEventListener {
        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) {
            recent = list.take(5).map { NaviPlace(it.name ?: it.address ?: "최근 목적지", it.address.orEmpty(), it.latitude, it.longitude, "recent", it.poiId.orEmpty()) }
            onPlaces(recent, bookmarks)
        }

        override fun onBookmarkInfo(list: List<BookmarkInfo>) {
            bookmarks = list.take(5).map {
                val kind = it.category?.name?.lowercase().orEmpty()
                NaviPlace(it.name ?: it.address ?: "즐겨찾기", it.address.orEmpty(), it.latitude, it.longitude, if ("home" in kind) "home" else if ("work" in kind || "office" in kind) "work" else "bookmark", it.poiId.orEmpty())
            }
            onPlaces(recent, bookmarks)
        }

        override fun onBookMarkChanged() { loadPlaces() }

        override fun onError(errorCode: NaviErrorCode) {
            val msg = when (errorCode) {
                NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "경로가 너무 멀어요."
                NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "목적지가 너무 가까워요."
                else -> "길을 찾지 못했어요 ($errorCode)"
            }
            onError(msg)
        }
    }

    private fun withHelper(block: (NaviHelper) -> Unit) {
        val h = helper ?: runCatching { NaviHelper(appContext) }.getOrElse {
            onError("Pleos 내비를 사용할 수 없는 환경이에요."); return
        }.also { helper = it }
        if (ready) { block(h); return }
        pending = { block(h) }
        h.initialize(
            onConnected = {
                ready = true
                runCatching { h.addListener(listener) }
                pending?.invoke(); pending = null
            },
            onFailed = { t ->
                Log.w(TAG, "NaviHelper unavailable", t)
                onError("Pleos 내비 연결 실패: ${t.message}")
            },
        )
    }

    /** 코스의 마지막 장소를 목적지로, 앞의 최대 2곳을 경유지로 경로 요청 */
    fun navigate(stops: List<CourseStop>) {
        if (stops.isEmpty()) return
        val trimmed = stops.take(3)
        val dest = trimmed.last()
        val waypoints = trimmed.dropLast(1)
        withHelper { h ->
            runCatching {
                if (waypoints.isEmpty()) {
                    h.requestRoute(dest.toRouteInfo())
                    showNavigation(h)
                } else {
                    h.requestRouteWithWaypoints(
                        RouteWithWaypointsRequest(
                            destination = dest.toRouteInfo(),
                            waypoints = waypoints.mapIndexed { i, s -> s.toWaypoint(if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND) },
                        ),
                    )
                    showNavigation(h)
                }
            }.onFailure { e ->
                onError(if (e is SecurityException) "내비 권한이 없어요." else "경로 요청 실패: ${e.message}")
            }
        }
    }

    /** 경로를 보낸 뒤 Pleos 내비 화면을 앞으로 띄운다 (요청만으로 화면이 바뀌는지는 내비 앱 구현에 따라 다를 수 있어 명시적으로 요청) */
    private fun showNavigation(h: NaviHelper) {
        runCatching { h.requestNavigationScreen(NavigationScreenRequest.Foreground) }
    }

    /** 최근 목적지·즐겨찾기 요청 (결과는 onPlaces) */
    fun loadPlaces() = withHelper { h -> runCatching { h.getRecentDestinationInfo(); h.getBookmarkInfo() } }

    /** Pleos 내비의 장소 검색 화면을 검색어와 함께 연다 */
    fun searchPlace(keyword: String) = withHelper { h ->
        runCatching { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
            .onFailure { e -> onError(if (e is SecurityException) "내비 검색 권한이 없어요." else "검색을 열지 못했어요: ${e.message}") }
    }

    /** 최근 목적지·즐겨찾기로 바로 길안내 */
    fun routeTo(place: NaviPlace) = withHelper { h ->
        runCatching {
            h.requestRoute(RouteInfo(latitude = place.lat, longitude = place.lon, displayLatitude = null, displayLongitude = null,
                poiName = place.name, poiId = place.poiId, poiSubId = "0", address = place.address, routeOption = RouteOption.RECOMMENDED))
            showNavigation(h)
        }.onFailure { e -> onError("경로 요청 실패: ${e.message}") }
    }

    fun release() {
        helper?.let { h ->
            runCatching { h.removeListener(listener) }
            runCatching { h.release() }
        }
        helper = null; ready = false
    }

    private fun CourseStop.toRouteInfo() = RouteInfo(
        latitude = lat,
        longitude = lon,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = contentId,
        poiSubId = "0",
        address = "",
        routeOption = RouteOption.RECOMMENDED,
    )

    private fun CourseStop.toWaypoint(index: WaypointIndex) = RequestWaypointInfo(
        latitude = lat,
        longitude = lon,
        poiName = name,
        waypointIndex = index,
        poiId = contentId,
        address = "",
        poiSubId = "0",
    )

    private companion object { const val TAG = "PleosNavi" }
}

/** 내비 장소 (SDK 타입을 화면에 노출하지 않기 위한 앱 모델). kind: home | work | bookmark | recent */
data class NaviPlace(val name: String, val address: String, val lat: Double, val lon: Double, val kind: String, val poiId: String = "")
