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
import be.meuse.engis.domain.MeuseSnapshot
import be.meuse.engis.ui.BRUSSELS
import be.meuse.engis.ui.HOUR
import be.meuse.engis.ui.argb
import be.meuse.engis.ui.monthName
import java.time.Duration
import kotlin.math.roundToInt

private val Bg = ColorProvider(day = Color(0xFFFCFCFB), night = Color(0xFF1A1A19))
private val Ink = ColorProvider(day = Color(0xFF0B0B0B), night = Color(0xFFF0EFEC))
private val Muted = ColorProvider(day = Color(0xFF52514E), night = Color(0xFFC3C2B7))

class MeuseWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = MeuseRepository(context).cachedOrRefresh(maxAge = Duration.ofMinutes(15))
        provideContent { WidgetContent(snapshot) }
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
private fun WidgetContent(s: MeuseSnapshot?) {
    val size = LocalSize.current
    val context = LocalContext.current
    val compact = size.height < 110.dp
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
        if (s == null || flow == null) {
            Text("Pas encore de données — touchez ⟳", style = TextStyle(color = Ink, fontSize = 13.sp))
            return@Column
        }

        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                flow.point.value.roundToInt().toString(),
                style = TextStyle(color = Ink, fontSize = if (compact) 24.sp else 28.sp, fontWeight = FontWeight.Bold),
            )
            Text(" m³/s ", style = TextStyle(color = Ink, fontSize = 14.sp))
            Text(
                when (flow.trend) {
                    be.meuse.engis.domain.Trend.HAUSSE -> "↑"
                    be.meuse.engis.domain.Trend.BAISSE -> "↓"
                    be.meuse.engis.domain.Trend.STABLE -> "→"
                    be.meuse.engis.domain.Trend.INCONNUE -> ""
                },
                style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight(),
            )
            if (compact) {
                Image(
                    provider = ImageProvider(R.drawable.ic_refresh),
                    contentDescription = "Actualiser",
                    modifier = GlanceModifier.size(20.dp).clickable(actionRunCallback<RefreshAction>()),
                )
            }
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
                Text(fc.label, style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }

        // Graphique si le widget est assez haut
        if (size.height >= 130.dp) {
            Spacer(GlanceModifier.height(6.dp))
            val density = context.resources.displayMetrics.density
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val chartW = ((size.width.value - 24) * density).roundToInt().coerceIn(1, 900)
            val chartH = ((size.height.value - 118) * density).roundToInt().coerceIn(1, 400)
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

        if (!compact) Text(
            "Amay · mesure de ${flow.point.time.atZoneSameInstant(BRUSSELS).format(HOUR)}",
            style = TextStyle(color = Muted, fontSize = 10.sp),
        )
    }
}
