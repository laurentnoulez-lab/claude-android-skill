package be.meuse.engis.data

import android.content.Context
import be.meuse.engis.domain.FlowThresholds

/** Réglages de l'utilisateur, partagés par l'application et le widget. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun thresholds(): FlowThresholds = FlowThresholds(
        lowPct = prefs.getInt(KEY_LOW, FlowThresholds.DEFAULT.lowPct),
        highPct = prefs.getInt(KEY_HIGH, FlowThresholds.DEFAULT.highPct),
    )

    fun save(t: FlowThresholds) {
        prefs.edit().putInt(KEY_LOW, t.lowPct).putInt(KEY_HIGH, t.highPct).apply()
    }

    private companion object {
        const val KEY_LOW = "seuil_bas_pct"
        const val KEY_HIGH = "seuil_haut_pct"
    }
}
