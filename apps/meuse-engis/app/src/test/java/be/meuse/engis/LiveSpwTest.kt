package be.meuse.engis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import be.meuse.engis.data.MeuseRepository
import be.meuse.engis.data.Series
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Test « de bout en bout » contre le vrai serveur du SPW (réseau requis).
 * Lancement : MEUSE_LIVE_TEST=1 ./gradlew testDebugUnitTest --tests '*LiveSpwTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveSpwTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("MEUSE_LIVE_TEST") == "1")

    @Test
    fun refresh_getsCurrentMeasurements() = runBlocking {
        val s = MeuseRepository(context).refresh()
        val flow = assertNotNull(s.amayFlow).let { s.amayFlow!! }
        val age = Duration.between(flow.point.time.toInstant(), Instant.now())
        println("Amay : ${flow.point.value} m³/s à ${flow.point.time} (il y a ${age.toMinutes()} min), ${s.flowClass}")
        println("Ivoz : ${s.ivozLevel?.point} / ${s.ivozFlow?.point} ; normale ${s.monthNormal} ; stats ${s.dayStats}")
        println("Barres : " + s.bars.joinToString { "${it.date}=${it.mean?.toInt()}(${it.hours}h)" })
        assertTrue("mesure trop ancienne : $age", age < Duration.ofHours(3))
        assertNotNull(s.ivozLevel)
        assertNotNull(s.monthNormal)
        assertNotNull(s.dayStats)
        assertEquals(8, s.bars.size)
        assertTrue(s.bars.dropLast(1).all { it.hours == 24 })
    }

    @Test
    fun refresh_repairsRenumberedSeries() = runBlocking {
        // Simule une renumérotation : deux identifiants mémorisés n'existent plus chez le SPW.
        val bogus = Series.entries.associate { it.name to it.defaultId }.toMutableMap()
        bogus[Series.AMAY_Q_1H.name] = "999999999"
        bogus[Series.IVOZ_H_1H.name] = "999999998"
        context.getSharedPreferences("meuse_cache", Context.MODE_PRIVATE).edit()
            .putString("ids", JSONObject(bogus as Map<*, *>).toString()).commit()

        val s = MeuseRepository(context).refresh()
        assertNotNull("débit retrouvé", s.amayFlow)
        assertNotNull("niveau retrouvé", s.ivozLevel)
        val saved = JSONObject(
            context.getSharedPreferences("meuse_cache", Context.MODE_PRIVATE).getString("ids", null)!!,
        )
        assertEquals(Series.AMAY_Q_1H.defaultId, saved.getString(Series.AMAY_Q_1H.name))
        assertEquals(Series.IVOZ_H_1H.defaultId, saved.getString(Series.IVOZ_H_1H.name))
        println("Réparation OK : ${saved}")
    }
}
