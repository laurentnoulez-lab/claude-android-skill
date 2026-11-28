package io.quarantaine.android

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import io.quarantaine.platform.Host

/** Services Android : sauvegarde (SharedPreferences), vibrations, sortie. */
class AndroidHost(private val activity: Activity) : Host {
    private val prefs = activity.getSharedPreferences("io_quarantaine", Context.MODE_PRIVATE)
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
    } catch (e: Exception) { null }

    override fun load(key: String): String? = prefs.getString(key, null)
    override fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    override fun vibrate(ms: Int, strength: Float) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val amp = if (v.hasAmplitudeControl()) (strength * 255f).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(5L, 1000L), amp))
        } catch (e: Exception) { /* vibreur indisponible */ }
    }

    override fun quit() { activity.runOnUiThread { activity.finish() } }
    override fun log(msg: String) { Log.i("IOQuarantaine", msg) }
}
