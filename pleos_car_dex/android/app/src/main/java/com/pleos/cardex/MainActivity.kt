package com.pleos.cardex

import android.os.Bundle
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pleos.cardex.ui.CarDexViewModel
import com.pleos.cardex.ui.screens.CollectionScreen
import com.pleos.cardex.ui.screens.DrivingScreen
import com.pleos.cardex.ui.screens.OnboardingScreen
import com.pleos.cardex.ui.screens.SettingsScreen
import com.pleos.cardex.ui.components.CelebrationOverlay
import com.pleos.cardex.ui.components.TripReportCard
import com.pleos.cardex.data.CarDexApi
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.pleos.cardex.ui.screens.ScanScreen
import com.pleos.cardex.ui.screens.RankingScreen
import com.pleos.cardex.ui.screens.ShowroomScreen
import com.pleos.cardex.ui.screens.TravelScreen
import com.pleos.cardex.ui.theme.CarDexTheme

internal enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "홈", Icons.Filled.Home), // 메인: 내 쇼룸 + 내비 검색·추천
    Collection("collection", "도감", Icons.Filled.CollectionsBookmark), // 메인: 주행 중 마주친 차량 카드 수집
    Scan("scan", "수집", Icons.Filled.CameraAlt), // YOLOv8 인식 -> 차량 정보 카드 (영상 입력 경로는 차량 영상 API 공개 시 연결)
    Quiz("quiz", "퀴즈", Icons.Filled.Quiz), // 아이용 차 맞히기
    Showroom("showroom", "쇼룸", Icons.Filled.DirectionsCar),
    Travel("travel", "여행", Icons.Filled.Map), // 희귀 차량을 만나러 새 지역으로
    Ranking("ranking", "랭킹", Icons.Filled.EmojiEvents),
    Settings("settings", "설정", Icons.Filled.Tune),
}

class MainActivity : ComponentActivity() {
    private val vm: CarDexViewModel by viewModels()

    // Pleos 런타임 권한: FusedLocation(공식 샘플과 동일) + NaviHelper 경로 요청
    private val pleosPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            vm.startLocation(result[PLEOS_LOCATION_PERMISSION] == true)
            vm.startGear(result[PLEOS_GEAR_PERMISSION] == true)
            vm.loadNaviPlaces()
        }

    private fun requestPleosPermissions() =
        pleosPermissions.launch(arrayOf(PLEOS_LOCATION_PERMISSION, PLEOS_GEAR_PERMISSION, *PLEOS_NAVI_PERMISSIONS))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.state.value.onboardingDone) requestPleosPermissions() // 첫 실행이면 안내 화면을 본 뒤 요청
        setContent {
            CarDexTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val current = nav.currentBackStackEntryAsState().value?.destination?.route
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // 시작 화면(그림 한 장): 최소 1.2초, 첫 데이터가 오면 최대 2.5초 안에 넘어감
                var showSplash by rememberSaveable { mutableStateOf(true) }
                val splashProgress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    if (!showSplash) return@LaunchedEffect
                    splashProgress.animateTo(0.7f, tween(1_200))
                    withTimeoutOrNull(1_300) { snapshotFlow { vm.state.value.home }.first { it != null } }
                    splashProgress.animateTo(1f, tween(200))
                    showSplash = false
                }

                Surface(Modifier.fillMaxSize()) {
                    // 차량 가로형 디스플레이: 좌측 내비게이션 레일
                    if (showSplash) {
                        com.pleos.cardex.ui.screens.SplashScreen(progress = splashProgress.value)
                    } else if (!state.onboardingDone) {
                        OnboardingScreen(onFinish = { vm.finishOnboarding(); requestPleosPermissions() })
                    } else if (state.isDriving) {
                        Box(Modifier.fillMaxSize()) {
                            DrivingScreen(
                                state = state,
                                onResumeNavigation = vm::resumeNavigation,
                                onToggleVoice = vm::toggleVoice,
                                onListen = vm::listenVoice,
                                onExitSimulation = if (state.drivingSource == "시연 전환") ({ vm.simulateDriving(null) }) else null,
                            )
                        }
                    } else ModalNavigationDrawer(
                        drawerState = drawer,
                        drawerContent = {
                            AppDrawer(current) { d ->
                                scope.launch { drawer.close() }
                                nav.navigate(d.route) { launchSingleTop = true; popUpTo(Dest.Home.route) }
                            }
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                        // 메뉴 화면 공통: 상단 ☰ · 제목 · 홈
                        fun androidx.navigation.NavGraphBuilder.screen(d: Dest, content: @androidx.compose.runtime.Composable () -> Unit) =
                            composable(d.route) {
                                SubScreen(d.label, onMenu = { scope.launch { drawer.open() } }, onHome = { nav.popBackStack(Dest.Home.route, inclusive = false) }) { content() }
                            }
                        NavHost(nav, startDestination = Dest.Home.route) {
                            composable(Dest.Home.route) {
                                com.pleos.cardex.ui.screens.HomeScreen(
                                    state = state,
                                    onMenu = { scope.launch { drawer.open() } },
                                    onSearch = vm::searchPlace,
                                    onPlace = vm::goToPlace,
                                    onVoice = vm::listenVoice,
                                    onOpen = { route -> nav.navigate(route) { launchSingleTop = true; popUpTo(Dest.Home.route) } },
                                )
                            }
                            screen(Dest.Scan) {
                                ScanScreen(state, vm.videoPositionMs, vm::scanOnce) { vm.toggleAutoScan() }
                            }
                            screen(Dest.Collection) {
                                CollectionScreen(
                                    entries = state.collection,
                                    synergy = state.synergy,
                                    featuredId = state.showroom.car?.id,
                                    onRefresh = vm::refreshCollection,
                                    onFeature = vm::feature,
                                    onDelete = vm::deleteEntry,
                                    onColor = vm::setCardColor,
                                    catalog = state.catalog,
                                    home = state.home,
                                )
                            }
                            screen(Dest.Quiz) {
                                com.pleos.cardex.ui.screens.QuizScreen(state, vm::nextQuiz, vm::answerQuiz, vm::listenVoice)
                            }
                            screen(Dest.Showroom) {
                                ShowroomScreen(
                                    showroom = state.showroom,
                                    collection = state.collection,
                                    stampBook = state.stampBook,
                                    decor = state.decor,
                                    onRefresh = { vm.loadStamps(); vm.loadShowroom() },
                                    onFeature = vm::feature,
                                    onBackground = vm::setBackground,
                                    onEquip = vm::equip,
                                    onAddProp = vm::addProp,
                                    onMoveProp = vm::moveProp,
                                    onResizeProp = vm::resizeProp,
                                    onRemoveProp = vm::removeProp,
                                )
                            }
                            screen(Dest.Travel) {
                                TravelScreen(
                                    course = state.course,
                                    loading = state.courseLoading,
                                    landmarks = state.landmarks,
                                    challenge = state.challenge,
                                    courseBy = state.courseBy,
                                    onRecommend = vm::recommendCourse,
                                    eco = state.ecoCourse,
                                    onEco = vm::setEcoCourse,
                                    onStartChallenge = vm::startChallengeAndNavigate,
                                    onResumeNavigation = vm::resumeNavigation,
                                    onAbandon = vm::abandonChallenge,
                                    home = state.home,
                                )
                            }
                            screen(Dest.Ranking) {
                                RankingScreen(
                                    weekly = state.weekly,
                                    masters = state.masters,
                                    currentRegion = state.masters?.masters?.map { it.region }?.let { regions ->
                                        state.driving?.location?.let { loc -> regions.minByOrNull { r -> (r.lat - loc.lat) * (r.lat - loc.lat) + (r.lon - loc.lon) * (r.lon - loc.lon) } }
                                    },
                                    onLoad = { vm.loadRankings(it) },
                                )
                            }
                            screen(Dest.Settings) {
                                SettingsScreen(
                                    keywords = state.keywords,
                                    voiceOn = state.voiceOn,
                                    fields = state.fields,
                                    aiStatus = state.aiStatus,
                                    voiceStatus = state.voiceStatus,
                                    catalog = state.catalog,
                                    myCarId = state.myCarId,
                                    onMyCar = vm::saveMyCar,
                                    showDemoTools = BuildConfig.DEBUG,
                                    onSaveKeywords = vm::saveKeywords,
                                    onToggleVoice = vm::toggleVoice,
                                    onSeedDemo = vm::seedDemo,
                                    onResetDemo = vm::resetDemo,
                                    onSimulateDriving = { vm.simulateDriving(true) },
                                )
                            }
                        }
                        // 서버 연결 끊김 배너
                        if (state.serverDown) {
                            Surface(
                                color = Color(0xFF5C1E1E), shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            ) {
                                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚠️ 서버에 연결할 수 없어요 (${CarDexApi.baseUrl})", color = Color.White, fontSize = 18.sp)
                                    TextButton(onClick = vm::refreshAll) { Text("다시 시도", fontSize = 18.sp) }
                                }
                            }
                        }
                        // 화면별 오류는 하단에 잠깐 (서버 끊김 배너와 겹치지 않게)
                        state.error?.takeIf { !state.serverDown }?.let { msg ->
                            LaunchedEffect(msg) { delay(5_000); vm.clearError() }
                            Surface(color = Color(0xFF3A2A12), shape = RoundedCornerShape(16.dp), modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                                Text("⚠️ $msg", color = Color.White, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                            }
                        }
                        // 전역 알림 배너: 스탬프 획득, 길안내 요청 등 (4초 후 사라짐)
                        state.notice?.let { msg -> // 4초 후 ViewModel 이 자동으로 지움
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
                    // 큰 성취 축하 (주행 중엔 음성만, 화면 연출은 정차 후)
                    if (!state.isDriving) state.celebration?.let { CelebrationOverlay(it, vm::dismissCelebration) }
                    // 주차 후 주행 리포트 (축하 연출이 끝난 뒤)
                    if (!state.isDriving && state.celebration == null) state.tripReport?.let { TripReportCard(it, vm::dismissTripReport) }
                }
            }
        }
    }
}

private const val PLEOS_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
private const val PLEOS_GEAR_PERMISSION = "pleos.car.permission.CAR_DRIVE" // Vehicle SDK 기어 상태
private val PLEOS_NAVI_PERMISSIONS = arrayOf(
    "pleos.car.permission.NAVI_ROUTE",
    "pleos.car.permission.NAVI_ROUTE_SEARCH",
    "pleos.car.permission.TTS_SERVICE", // 음성 안내
    "pleos.car.permission.LLM_SERVICE", // AI 코스 추천
    "pleos.car.permission.STT_SERVICE", // 음성 명령
    "android.permission.RECORD_AUDIO",
)

/** ☰ 메뉴: 홈 외의 모든 기능 */
@androidx.compose.runtime.Composable
internal fun AppDrawer(current: String?, onSelect: (Dest) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Color(0xFF111827)) {
        Text("CarDex", color = Color.White, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, modifier = Modifier.padding(24.dp))
        val sub = mapOf(
            Dest.Home to "내 쇼룸 · 길찾기", Dest.Collection to "모은 차 카드 · 28종", Dest.Scan to "지나가는 차 인식",
            Dest.Quiz to "아이와 차 맞히기", Dest.Showroom to "대표 차·배경 꾸미기", Dest.Travel to "AI 코스 · 스탬프",
            Dest.Ranking to "주간 랭킹 · 지역 마스터", Dest.Settings to "카드 정보 · 내 차",
        )
        Dest.entries.forEach { d ->
            NavigationDrawerItem(
                label = {
                    androidx.compose.foundation.layout.Column {
                        Text(d.label, fontSize = 20.sp)
                        Text(sub[d].orEmpty(), fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                },
                icon = { Icon(d.icon, d.label) },
                selected = current == d.route,
                onClick = { onSelect(d) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

/** 메뉴 화면 틀: 상단 바(☰ · 제목 · 홈) + 내용 */
@androidx.compose.runtime.Composable
private fun SubScreen(title: String, onMenu: () -> Unit, onHome: () -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "메뉴") }
            Text(title, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onHome) { Icon(Icons.Filled.Home, "홈") }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
