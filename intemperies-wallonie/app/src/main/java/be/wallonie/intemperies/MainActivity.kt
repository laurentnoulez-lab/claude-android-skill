package be.wallonie.intemperies

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import be.wallonie.intemperies.ui.calendar.CalendarRoute
import be.wallonie.intemperies.ui.calendar.CalendarViewModel
import be.wallonie.intemperies.ui.home.HomeRoute
import be.wallonie.intemperies.ui.home.HomeViewModel
import be.wallonie.intemperies.ui.legal.RegulatoryRoute
import be.wallonie.intemperies.ui.settings.CommunePickerRoute
import be.wallonie.intemperies.ui.settings.SettingsRoute
import be.wallonie.intemperies.ui.settings.SettingsViewModel
import be.wallonie.intemperies.ui.theme.IntemperiesTheme
import be.wallonie.intemperies.work.SyncWorker
import kotlinx.serialization.Serializable

@Serializable data object HomeDest
@Serializable data class CalendarDest(val nis: String)
@Serializable data object SettingsDest
@Serializable data object PickerDest
@Serializable data object RegulatoryDest

class MainActivity : ComponentActivity() {
    private var pendingNis by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingNis = intent?.getStringExtra(EXTRA_NIS)
        val container = (application as IntemperiesApp).container
        if (savedInstanceState == null) SyncWorker.syncNow(this)
        setContent {
            IntemperiesTheme { AppNav(container) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingNis = intent.getStringExtra(EXTRA_NIS)
    }

    @Composable
    private fun AppNav(container: AppContainer) {
        val nav = rememberNavController()
        val requestSync: (Boolean) -> Unit = { deep -> SyncWorker.syncNow(applicationContext, deep) }
        LaunchedEffect(pendingNis) {
            pendingNis?.let {
                nav.navigate(CalendarDest(it)) { launchSingleTop = true }
                pendingNis = null
            }
        }
        NavHost(navController = nav, startDestination = HomeDest) {
            composable<HomeDest> {
                val vm = viewModel {
                    HomeViewModel(container.weatherRepository, container.settingsRepository, container.exporter)
                }
                HomeRoute(
                    viewModel = vm,
                    onOpenCommune = { nav.navigate(CalendarDest(it)) },
                    onOpenSettings = { nav.navigate(SettingsDest) },
                    onPickCommunes = { nav.navigate(PickerDest) },
                )
            }
            composable<CalendarDest> { entry ->
                val nis = entry.toRoute<CalendarDest>().nis
                val vm = viewModel(key = "cal_$nis") {
                    CalendarViewModel(
                        nis, container.weatherRepository, container.settingsRepository, container.catalogRepository,
                        container.exporter, onStationsChanged = { requestSync(false) },
                    )
                }
                CalendarRoute(vm, onBack = { nav.popBackStack() }, onOpenRegulatory = { nav.navigate(RegulatoryDest) })
            }
            composable<SettingsDest> {
                val vm = viewModel { SettingsViewModel(container.settingsRepository, container.catalogRepository, requestSync) }
                SettingsRoute(
                    vm,
                    onBack = { nav.popBackStack() },
                    onPickCommunes = { nav.navigate(PickerDest) },
                    onOpenRegulatory = { nav.navigate(RegulatoryDest) },
                )
            }
            composable<RegulatoryDest> { RegulatoryRoute(onBack = { nav.popBackStack() }) }
            composable<PickerDest> {
                val vm = viewModel { SettingsViewModel(container.settingsRepository, container.catalogRepository, requestSync) }
                CommunePickerRoute(vm, onBack = { nav.popBackStack() })
            }
        }
    }

    companion object {
        const val EXTRA_NIS = "nis"
    }
}
