package be.wallonie.intemperies

import android.app.Application
import be.wallonie.intemperies.work.SyncWorker

class IntemperiesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedulePeriodic(this)
    }
}
