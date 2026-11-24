package be.wallonie.intemperies.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import be.wallonie.intemperies.IntemperiesApp
import be.wallonie.intemperies.MainActivity
import be.wallonie.intemperies.data.WeatherRepository
import be.wallonie.intemperies.model.DayStatus
import be.wallonie.intemperies.model.MonthReport
import be.wallonie.intemperies.ui.Fmt
import be.wallonie.intemperies.ui.theme.StatusColors
import java.time.LocalDate
import java.time.YearMonth

/** Widget « une commune » : statut du jour, 7 derniers jours, bilan du mois. */
class IntemperieWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as IntemperiesApp).container.weatherRepository
        provideContent {
            // Lecture de la commune dans l'état du widget + flux Room : le widget suit les synchronisations.
            val nis = currentState<Preferences>()[NIS_KEY]
            val today = LocalDate.now(WeatherRepository.BRUSSELS)
            val month = YearMonth.from(today)
            val flow = remember(nis, month) {
                if (nis == null) {
                    flowOf(null)
                } else {
                    combine(repo.observeMonth(nis, month), repo.observeMonth(nis, month.minusMonths(1))) { a, b -> a to b }
                }
            }
            val data by flow.collectAsState(initial = null)
            GlanceTheme {
                Content(data?.first, data?.second, today, nis)
            }
        }
    }

    @Composable
    private fun Content(report: MonthReport?, previous: MonthReport?, today: LocalDate, nis: String?) {
        val open = Intent().setClassName("be.wallonie.intemperies", MainActivity::class.java.name)
            .putExtra(MainActivity.EXTRA_NIS, nis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Column(
            GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity(open)),
        ) {
            if (report == null) {
                Text("Intempéries", style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                Text(if (nis == null) "Appuyez pour configurer" else "Chargement…", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                return@Column
            }
            val days = (previous?.days.orEmpty() + report.days).associateBy { it.date }
            val todayReport = days[today]
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    report.commune.name,
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                )
                todayReport?.let { StatusPill(it.status) }
            }
            todayReport?.let {
                Text(
                    "Auj. ${Fmt.hours(it.rainHours)} · ${Fmt.mm(it.windowRainMm)}" + (it.meanTemperatureC?.let { t -> " · ${Fmt.temp(t)}" } ?: ""),
                    style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                for (offset in 6 downTo 0) {
                    val date = today.minusDays(offset.toLong())
                    val d = days[date]
                    Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            GlanceModifier.size(22.dp).cornerRadius(5.dp)
                                .background(ColorProvider(d?.let { colorOf(it.status) } ?: PENDING)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(date.dayOfMonth.toString(), style = TextStyle(fontSize = 10.sp, color = ColorProvider(StatusColors.OnStatus)))
                        }
                        Text(Fmt.dow(date).take(2), style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                "${Fmt.month(report.month)} : ${report.count(DayStatus.INTEMPERIE)} intempérie(s), ${report.count(DayStatus.WARNING)} jour(s) de pluie",
                style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurface),
                maxLines = 2,
            )
            Text(
                "SPW · mesures jusqu'à " + (report.lastRainDataEpochSec?.let { Fmt.dateTime(Fmt.rainDataEnd(it)).substringAfter("à ") + " le " + Fmt.dateTime(it).take(5) } ?: "—") +
                    if (Fmt.isStale(report.lastRainDataEpochSec)) " ⚠" else "",
                style = TextStyle(fontSize = 9.sp, color = GlanceTheme.colors.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }

    @Composable
    private fun StatusPill(status: DayStatus) {
        Box(
            GlanceModifier.background(ColorProvider(colorOf(status))).cornerRadius(10.dp).padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                when (status) {
                    DayStatus.INTEMPERIE -> "Intempérie"
                    DayStatus.WARNING -> "Pluie"
                    DayStatus.DRY -> "Sec"
                    DayStatus.NO_DATA -> "N/D"
                    DayStatus.IN_PROGRESS -> "En cours"
                    DayStatus.FUTURE -> "—"
                },
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ColorProvider(StatusColors.OnStatus)),
            )
        }
    }

    private fun colorOf(s: DayStatus): Color = when (s) {
        DayStatus.IN_PROGRESS, DayStatus.FUTURE -> PENDING
        else -> StatusColors.of(s)
    }

    companion object {
        val NIS_KEY = stringPreferencesKey("nis")
        private val PENDING = Color(0xFFE3E7EC)

        suspend fun refreshAll(context: Context) {
            runCatching { IntemperieWidget().updateAll(context) }
        }

        suspend fun glanceIdOf(context: Context, appWidgetId: Int): GlanceId =
            GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

        fun nisOf(prefs: Preferences): String? = prefs[NIS_KEY]
    }
}

class IntemperieWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = IntemperieWidget()
}
