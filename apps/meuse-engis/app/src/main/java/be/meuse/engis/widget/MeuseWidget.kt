package be.meuse.engis.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import be.meuse.engis.MainActivity
import be.meuse.engis.R
import be.meuse.engis.data.MeuseRepository
import be.meuse.engis.domain.CarnassierRules
import be.meuse.engis.domain.MeuseSnapshot
import be.meuse.engis.domain.Trend
import be.meuse.engis.ui.FisherMood
import be.meuse.engis.ui.short
import java.time.ZonedDateTime
import be.meuse.engis.ui.BRUSSELS
import be.meuse.engis.ui.HOUR
import be.meuse.engis.ui.argb
import be.meuse.engis.ui.monthName
import java.time.Duration
import kotlin.math.roundToInt

private val Bg = ColorProvider(day = Color(0xFFFCFCFB), night = Color(0xFF1A1A19))
private val Ink = ColorProvider(day = Color(0xFF0B0B0B), night = Color(0xFFF0EFEC))
private val Muted = ColorProvider(day = Color(0xFF52514E), night = Color(0xFFC3C2B7))
private val Green = ColorProvider(day = Color(0xFF0CA30C), night = Color(0xFF0CA30C))

class MeuseWidget(
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(BRUSSELS) },
) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = MeuseRepository(context).cachedOrRefresh(maxAge = Duration.ofMinutes(15))
        val now = clock()
        scheduleNextRegulationUpdate(context, CarnassierRules.nextChange(now))
        provideContent { WidgetContent(snapshot, now) }
    }
}

class MeuseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeuseWidget()
}

/** Bouton ⟳ du widget : nouvelle requête au SPW puis redessin. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runCatching { MeuseRepository(context).refresh() }
        MeuseWidget().update(context, glanceId)
    }
}

@Composable
private fun WidgetContent(s: MeuseSnapshot?, now: ZonedDateTime) {
    val size = LocalSize.current
    val context = LocalContext.current
    val compact = size.height < 110.dp
    val status = CarnassierRules.status(now)
    val mood = FisherMood.of(status, s?.flowClass)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Bg)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (!compact) Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Meuse · Engis",
                style = TextStyle(color = Muted, fontSize = 12.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Actualiser",
                modifier = GlanceModifier.size(22.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }

        val flow = s?.amayFlow
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                if (flow == null) {
                    Text("Pas encore de données — touchez ⟳", style = TextStyle(color = Ink, fontSize = 13.sp))
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            flow.point.value.roundToInt().toString(),
                            style = TextStyle(
                                color = Ink,
                                fontSize = if (compact) 22.sp else 28.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(" m³/s ", style = TextStyle(color = Ink, fontSize = if (compact) 11.sp else 14.sp))
                        Text(
                            when (flow.trend) {
                                Trend.HAUSSE -> "↑"
                                Trend.BAISSE -> "↓"
                                Trend.STABLE -> "→"
                                Trend.INCONNUE -> ""
                            },
                            style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                    s.flowClass?.let { fc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                GlanceModifier
                                    .size(10.dp)
                                    .cornerRadius(5.dp)
                                    .background(Color(fc.argb())),
                            ) {}
                            Spacer(GlanceModifier.width(6.dp))
                            Text(
                                if (compact) fc.shortLabel else fc.label,
                                style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (mood.drawable != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        provider = ImageProvider(mood.drawable),
                        contentDescription = if (mood == FisherMood.FISH) "Fish!" else "Pêche interdite en ce moment",
                        modifier = GlanceModifier.size(if (compact) 36.dp else 52.dp),
                    )
                    Text(
                        mood.label.orEmpty(),
                        style = TextStyle(
                            color = if (mood == FisherMood.FISH) Green else Muted,
                            fontSize = if (compact) 12.sp else 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            } else if (compact) {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Actualiser",
                    modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
        }

        if (!compact) {
            Text(status.short(now), style = TextStyle(color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium))
        }

        // Graphique si le widget est assez haut
        if (s != null && size.height >= 170.dp) {
            Spacer(GlanceModifier.height(4.dp))
            val density = context.resources.displayMetrics.density
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val chartW = ((size.width.value - 24) * density).roundToInt().coerceIn(1, 900)
            val chartH = ((size.height.value - 150) * density).roundToInt().coerceIn(1, 400)
            Image(
                provider = ImageProvider(MiniChart.render(s.bars, s.monthNormal, chartW, chartH, density, dark)),
                contentDescription = "Débit moyen des 7 derniers jours et d'aujourd'hui",
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            )
            s.monthNormal?.let {
                val today = s.bars.last().date
                Text(
                    "- - moyenne habituelle de ${monthName(today)} : ${it.roundToInt()} m³/s",
                    style = TextStyle(color = Muted, fontSize = 10.sp),
                )
            }
        }

        if (!compact && flow != null) Text(
            "Amay · mesure de ${flow.point.time.atZoneSameInstant(BRUSSELS).format(HOUR)}",
            style = TextStyle(color = Muted, fontSize = 10.sp),
        )
    }
}

/**
 * Redessine le widget au prochain changement de statut réglementaire (ouverture / fermeture de la pêche),
 * sans attendre la mise à jour périodique de 30 min. Alarme non réveillante, fenêtre de 5 min, aucune
 * permission requise : elle est délivrée au plus tard au réveil de l'écran.
 */
private fun scheduleNextRegulationUpdate(context: Context, at: ZonedDateTime) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val manager = android.appwidget.AppWidgetManager.getInstance(context)
    val ids = manager.getAppWidgetIds(android.content.ComponentName(context, MeuseWidgetReceiver::class.java))
    val intent = android.content.Intent(context, MeuseWidgetReceiver::class.java)
        .setAction(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
        .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
    val pending = android.app.PendingIntent.getBroadcast(
        context, 42, intent,
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    am.setWindow(android.app.AlarmManager.RTC, at.toInstant().toEpochMilli(), 5 * 60_000L, pending)
}
