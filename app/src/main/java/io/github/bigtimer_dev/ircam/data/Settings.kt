package io.github.bigtimer_dev.ircam.data

import android.content.Context
import io.github.bigtimer_dev.ircam.thermal.Pools
import java.util.Locale

enum class TempUnit(val symbol: String, val scale: Double, val offset: Double) {
    C("°C", 1.0, 0.0), F("°F", 1.8, 32.0), K("K", 1.0, 273.15);

    fun convert(celsius: Double) = celsius * scale + offset
    fun toCelsius(v: Double) = (v - offset) / scale
    fun format(celsius: Double, decimals: Int = 1): String =
        if (celsius.isNaN()) "—" else String.format(Locale.US, "%.${decimals}f", convert(celsius))
    fun formatWithUnit(celsius: Double, decimals: Int = 1) = format(celsius, decimals) + symbol
}

/** Measurement parameters sent to the temperature model (camera configuration). */
data class MeasureParams(
    val emissivity: Double = Pools.DEFAULT_EMISSIVITY,
    val reflected: Double = Pools.DEFAULT_REFLECTED,
    val ambient: Double = Pools.DEFAULT_AMBIENT,
    val humidity: Double = Pools.DEFAULT_HUMIDITY,
    val distance: Double = Pools.DEFAULT_DISTANCE,
    val tempCorrection: Double = Pools.DEFAULT_TEMP_CORRECTION,
)

/** Display preferences; persisted across launches. */
data class Settings(
    val palette: String = "Iron",
    val invertPalette: Boolean = false,
    val unit: TempUnit = TempUnit.C,
    val smooth: Boolean = true,
    val sharpen: Boolean = false,
    val showMax: Boolean = true,
    val showMin: Boolean = true,
    val showCenter: Boolean = true,
    val rotation: Int = 0,
    val mirror: Boolean = false,
    val rangeLocked: Boolean = false,
    val lockLo: Double = 20.0,
    val lockHi: Double = 40.0,
    val params: MeasureParams = MeasureParams(),
    val emissivityMaterial: String = "Camera Default",
    /** Address of the wireless P2 Pro bridge (host, host:port or IP). */
    val wirelessHost: String = "p2pro.local",
    /** Image rotation for the wireless camera (it is not attached to the phone). */
    val wirelessRotation: Int = 0,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ircam", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        val p = prefs
        fun dbl(k: String, def: Double) = p.getFloat(k, def.toFloat()).toDouble()
        return Settings(
            palette = p.getString("palette", d.palette) ?: d.palette,
            invertPalette = p.getBoolean("invert", d.invertPalette),
            unit = runCatching { TempUnit.valueOf(p.getString("unit", "C")!!) }.getOrDefault(TempUnit.C),
            smooth = p.getBoolean("smooth", d.smooth),
            sharpen = p.getBoolean("sharpen", d.sharpen),
            showMax = p.getBoolean("showMax", d.showMax),
            showMin = p.getBoolean("showMin", d.showMin),
            showCenter = p.getBoolean("showCenter", d.showCenter),
            rotation = p.getInt("rotation", 0),
            mirror = p.getBoolean("mirror", false),
            rangeLocked = p.getBoolean("rangeLocked", false),
            lockLo = dbl("lockLo", d.lockLo),
            lockHi = dbl("lockHi", d.lockHi),
            params = MeasureParams(
                emissivity = dbl("emissivity", d.params.emissivity),
                reflected = dbl("reflected", d.params.reflected),
                ambient = dbl("ambient", d.params.ambient),
                humidity = dbl("humidity", d.params.humidity),
                distance = dbl("distance", d.params.distance),
                tempCorrection = dbl("tempCorrection", d.params.tempCorrection),
            ),
            emissivityMaterial = p.getString("material", d.emissivityMaterial) ?: d.emissivityMaterial,
            wirelessHost = p.getString("wirelessHost", d.wirelessHost) ?: d.wirelessHost,
            wirelessRotation = p.getInt("wirelessRotation", 0),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("palette", s.palette)
            .putBoolean("invert", s.invertPalette)
            .putString("unit", s.unit.name)
            .putBoolean("smooth", s.smooth)
            .putBoolean("sharpen", s.sharpen)
            .putBoolean("showMax", s.showMax)
            .putBoolean("showMin", s.showMin)
            .putBoolean("showCenter", s.showCenter)
            .putInt("rotation", s.rotation)
            .putBoolean("mirror", s.mirror)
            .putBoolean("rangeLocked", s.rangeLocked)
            .putFloat("lockLo", s.lockLo.toFloat())
            .putFloat("lockHi", s.lockHi.toFloat())
            .putFloat("emissivity", s.params.emissivity.toFloat())
            .putFloat("reflected", s.params.reflected.toFloat())
            .putFloat("ambient", s.params.ambient.toFloat())
            .putFloat("humidity", s.params.humidity.toFloat())
            .putFloat("distance", s.params.distance.toFloat())
            .putFloat("tempCorrection", s.params.tempCorrection.toFloat())
            .putString("material", s.emissivityMaterial)
            .putString("wirelessHost", s.wirelessHost)
            .putInt("wirelessRotation", s.wirelessRotation)
            .apply()
    }
}
