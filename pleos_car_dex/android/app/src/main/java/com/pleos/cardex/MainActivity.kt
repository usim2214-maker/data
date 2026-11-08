package com.pleos.cardex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
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
import com.pleos.cardex.ui.theme.CarDexTheme

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Scan("scan", "스캔", Icons.Filled.CameraAlt),
    Collection("collection", "도감", Icons.Filled.CollectionsBookmark),
    Settings("settings", "키워드", Icons.Filled.Tune),
}

class MainActivity : ComponentActivity() {
    private val vm: CarDexViewModel by viewModels()

    // Pleos FusedLocation 런타임 권한 (공식 샘플과 동일한 권한 문자열)
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.startLocation(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationPermission.launch(PLEOS_LOCATION_PERMISSION)
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
                        NavHost(nav, startDestination = Dest.Scan.route) {
                            composable(Dest.Scan.route) {
                                ScanScreen(state, vm.videoPositionMs, vm::scanOnce, { vm.toggleAutoScan() }, vm::dismissSummon)
                            }
                            composable(Dest.Collection.route) {
                                CollectionScreen(
                                    entries = state.collection,
                                    synergy = state.synergy,
                                    summonedId = state.summoned?.id,
                                    onRefresh = vm::refreshCollection,
                                    onSummon = vm::summon,
                                    onDelete = vm::deleteEntry,
                                )
                            }
                            composable(Dest.Settings.route) { KeywordSettingsScreen(state.keywords, vm::saveKeywords) }
                        }
                    }
                }
            }
        }
    }
}

private const val PLEOS_LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
