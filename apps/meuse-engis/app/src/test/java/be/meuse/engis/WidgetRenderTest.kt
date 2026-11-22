package be.meuse.engis

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import be.meuse.engis.widget.MeuseWidget
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Rendu du widget (cache pré-rempli avec la réponse réelle du 22/11/2026) dans app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xxhdpi")
class WidgetRenderTest {

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private fun render(name: String, w: Int, h: Int, now: String = "2026-11-22T10:00:00+01:00[Europe/Brussels]"): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("meuse_cache", Context.MODE_PRIVATE).edit()
            .putString("json", javaClass.getResource("/kiwis_2026-11-22.json")!!.readText())
            .putLong("time", System.currentTimeMillis())
            .commit()
        val rv = MeuseWidget(clock = { java.time.ZonedDateTime.parse(now) }).compose(context, size = DpSize(w.dp, h.dp))
        val density = context.resources.displayMetrics.density
        val parent = FrameLayout(context)
        val view = rv.apply(context, parent)
        val wPx = (w * density).toInt()
        val hPx = (h * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(hPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, wPx, hPx)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF5A6B7C.toInt()) // fond d'écran fictif
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }

    @Test fun grand() { render("widget_3x2", 250, 200) }

    @Test fun petit() { render("widget_2x1", 150, 80) }

    @Test fun nuit() { render("widget_3x2_nuit", 250, 200, "2026-11-22T19:30:00+01:00[Europe/Brussels]") }

    @Test fun fermeture() { render("widget_2x1_fermeture", 150, 80, "2026-03-15T12:00:00+01:00[Europe/Brussels]") }
}
