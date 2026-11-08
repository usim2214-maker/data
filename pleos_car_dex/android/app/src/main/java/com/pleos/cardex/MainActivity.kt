package com.pleos.cardex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pleos.cardex.ui.CarDexViewModel
import com.pleos.cardex.ui.screens.CollectionScreen
import com.pleos.cardex.ui.screens.KeywordSettingsScreen
import com.pleos.cardex.ui.screens.ScanScreen
import com.pleos.cardex.ui.screens.RankingScreen
import com.pleos.cardex.ui.screens.ShowroomScreen
import com.pleos.cardex.ui.screens.TravelScreen
import com.pleos.cardex.ui.theme.CarDexTheme

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Scan("scan", "스캔", Icons.Filled.CameraAlt),
    Collection("collection", "도감", Icons.Filled.CollectionsBookmark),
    Showroom("showroom", "쇼룸", Icons.Filled.DirectionsCar),
    Travel("travel", "여행", Icons.Filled.Map),
    Ranking("ranking", "랭킹", Icons.Filled.EmojiEvents),
    Settings("settings", "키워드", Icons.Filled.Tune),
}

class MainActivity : ComponentActivity() {
    private val vm: CarDexViewModel by viewModels()

    // Pleos 런타임 권한: FusedLocation(공식 샘플과 동일) + NaviHelper 경로 요청
    private val pleosPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.startLocation(result[PLEOS_LOCATION_PERMISSION] == true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pleosPermissions.launch(arrayOf(PLEOS_LOCATION_PERMISSION, *PLEOS_NAVI_PERMISSIONS))
        setContent {
            CarDexTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val current = nav.currentBackStackEntryAsState().value?.destination?.route

                Surface(Modifier.fillMaxSize()) {
                    // 차량 가로형 디스플레이: 좌측 내비게이션 레일
                    Row {
                        NavigationRail {
                            Dest.entries.forEach { d ->
                                NavigationRailItem(
                                    selected = current == d.route,
                                    onClick = { nav.navigate(d.route) { launchSingleTop = true; popUpTo(Dest.Scan.route) } },
                                    icon = { Icon(d.icon, d.label) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                        NavHost(nav, startDestination = Dest.Scan.route) {
                            composable(Dest.Scan.route) {
                                ScanScreen(state, vm.videoPositionMs, vm::scanOnce) { vm.toggleAutoScan() }
                            }
                            composable(Dest.Collection.route) {
                                CollectionScreen(
                                    entries = state.collection,
                                    synergy = state.synergy,
                                    featuredId = state.showroom.car?.id,
                                    onRefresh = vm::refreshCollection,
                                    onFeature = vm::feature,
                                    onDelete = vm::deleteEntry,
                                )
                            }
                            composable(Dest.Showroom.route) {
                                ShowroomScreen(
                                    showroom = state.showroom,
                                    collection = state.collection,
                                    stampBook = state.stampBook,
                                    onRefresh = vm::loadStamps,
                                    onFeature = vm::feature,
                                    onBackground = vm::setBackground,
                                    onToggleSticker = vm::toggleSticker,
                                )
                            }
                            composable(Dest.Travel.route) {
                                TravelScreen(
                                    course = state.course,
                                    loading = state.courseLoading,
                                    landmarks = state.landmarks,
                                    challenge = state.challenge,
                                    onRecommend = vm::recommendCourse,
                                    onStartChallenge = vm::startChallengeAndNavigate,
                                    onResumeNavigation = vm::resumeNavigation,
                                    onAbandon = vm::abandonChallenge,
                                )
                            }
                            composable(Dest.Ranking.route) {
                                RankingScreen(
                                    weekly = state.weekly,
                                    masters = state.masters,
                                    currentRegion = state.masters?.masters?.map { it.region }?.let { regions ->
                                        state.driving?.location?.let { loc -> regions.minByOrNull { r -> (r.lat - loc.lat) * (r.lat - loc.lat) + (r.lon - loc.lon) * (r.lon - loc.lon) } }
                                    },
                                    onLoad = { vm.loadRankings(it) },
                                )
                            }
                            composable(Dest.Settings.route) { KeywordSettingsScreen(state.keywords, vm::saveKeywords) }
                        }
                        // 전역 알림 배너: 스탬프 획득, 길안내 요청 등 (4초 후 사라짐)
                        state.notice?.let { msg ->
                            LaunchedEffect(msg) { delay(4_000); vm.clearNotice() }
                            Surface(
                                color = MaterialTheme.colorScheme.secondary,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                            ) {
                                Text(msg, color = Color.Black, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}

private const val PLEOS_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
private val PLEOS_NAVI_PERMISSIONS = arrayOf("pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH")
