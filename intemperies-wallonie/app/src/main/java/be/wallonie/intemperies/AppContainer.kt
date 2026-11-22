package be.wallonie.intemperies

import android.content.Context
import be.wallonie.intemperies.data.CatalogRepository
import be.wallonie.intemperies.data.SettingsRepository
import be.wallonie.intemperies.data.WeatherRepository
import be.wallonie.intemperies.data.db.AppDatabase
import be.wallonie.intemperies.data.remote.KiwisClient
import be.wallonie.intemperies.export.CalendarExporter
import be.wallonie.intemperies.pdf.CalendarPdfExporter

/** Injection de dépendances manuelle (application mono-module, pas besoin de Hilt). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val kiwisClient: KiwisClient by lazy { KiwisClient() }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository(appContext, database.stationDao(), kiwisClient, settingsRepository)
    }
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(database.sampleDao(), kiwisClient, catalogRepository, settingsRepository)
    }
    val exporter: CalendarExporter by lazy { CalendarExporter(appContext, CalendarPdfExporter(appContext)) }
}
