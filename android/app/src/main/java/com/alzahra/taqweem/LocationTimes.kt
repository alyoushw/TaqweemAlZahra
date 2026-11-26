package com.alzahra.taqweem

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Optional prayer times for the user's own location, calculated on the phone (no internet), in place of
 * the printed Ahsa times. Dates, occasions and colours still come from the Zahra calendar.
 *
 * Same astronomy as the well-known PrayTimes method. The angles were fitted to the printed calendar:
 * for Ahsa they reproduce its times within about a minute (fajr 18.1°, maghrib 3.65° below the horizon,
 * midnight halfway from sunset to fajr, imsak 8 minutes before fajr as in the calendar).
 */
object LocationTimes {
    private const val FAJR_ANGLE = 18.1
    private const val MAGHRIB_ANGLE = 3.65
    private const val IMSAK_BEFORE_FAJR = 8

    private fun sp(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    fun on(ctx: Context) = sp(ctx).getBoolean("loc_times", false) && hasPlace(ctx)
    fun hasPlace(ctx: Context) = sp(ctx).contains("loc_lat")
    fun lat(ctx: Context) = sp(ctx).getFloat("loc_lat", 25.3833f).toDouble()
    fun lon(ctx: Context) = sp(ctx).getFloat("loc_lon", 49.5867f).toDouble()
    fun placeName(ctx: Context) = sp(ctx).getString("loc_name", null)

    fun setOn(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("loc_times", on).apply()
        CalendarData.invalidate()
    }

    fun setPlace(ctx: Context, lat: Double, lon: Double, name: String?) {
        sp(ctx).edit().putFloat("loc_lat", lat.toFloat()).putFloat("loc_lon", lon.toFloat()).putString("loc_name", name).apply()
        CalendarData.invalidate()
    }

    /** Short description for the settings screen. */
    fun placeLabel(ctx: Context): String =
        placeName(ctx) ?: "%.3f° ، %.3f°".format(java.util.Locale.US, lat(ctx), lon(ctx))

    // ---------- finding the place ----------

    private fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Find where the phone is and save it (with the city name when the phone can look it up).
     * [done] gets true when a place was saved. Asks for the location permission first if needed.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun locate(activity: android.app.Activity, done: (Boolean) -> Unit) {
        if (!hasPermission(activity)) {
            pendingLocate = { ok -> if (ok) locate(activity, done) else done(false) }
            activity.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_CODE)
            return
        }
        val lm = activity.getSystemService(android.location.LocationManager::class.java)
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        fun save(l: android.location.Location) {
            Thread {
                val name = runCatching {
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(activity, java.util.Locale("ar")).getFromLocation(l.latitude, l.longitude, 1)
                        ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
                }.getOrNull()
                activity.runOnUiThread { setPlace(activity, l.latitude, l.longitude, name); done(true) }
            }.start()
        }
        // A recent fix is good enough (prayer times barely change within a few km); otherwise ask for a fresh one.
        if (last != null && System.currentTimeMillis() - last.time < 30 * 60_000L) { save(last); return }
        var finished = false
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: android.location.Location) {
                if (finished) return
                finished = true; runCatching { lm.removeUpdates(this) }; save(l)
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        providers.forEach { p -> runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, android.os.Looper.getMainLooper()) } }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (finished) return@postDelayed
            finished = true; runCatching { lm.removeUpdates(listener) }
            if (last != null) save(last) else done(false)
        }, 25_000L)
    }

    const val PERMISSION_CODE = 22
    private var pendingLocate: ((Boolean) -> Unit)? = null
    fun onPermissionResult(granted: Boolean) { pendingLocate?.invoke(granted); pendingLocate = null }

    /** When the user travels (moved more than ~5 km), follow quietly with the last known position. */
    @android.annotation.SuppressLint("MissingPermission")
    fun followQuietly(ctx: Context) {
        if (!on(ctx) || !hasPermission(ctx)) return
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        val last = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return
        val d = FloatArray(1)
        android.location.Location.distanceBetween(lat(ctx), lon(ctx), last.latitude, last.longitude, d)
        if (d[0] < 5000f) return
        Thread {
            val name = runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(ctx, java.util.Locale("ar")).getFromLocation(last.latitude, last.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
            setPlace(ctx, last.latitude, last.longitude, name)
            AlarmScheduler.scheduleNext(ctx)
            ZahraWidget.updateAll(ctx)
        }.start()
    }

    // ---------- astronomy ----------

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Sun's declination and the equation of time (hours) for a Julian day. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = (357.529 + 0.98560028 * d) % 360
        val q = (280.459 + 0.98564736 * d) % 360
        val l = (q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g))) % 360
        val e = 23.439 - 0.00000036 * d
        var ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15
        ra = ((ra % 24) + 24) % 24
        var eqt = q / 15 - ra
        eqt = ((eqt + 12) % 24 + 24) % 24 - 12
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return decl to eqt
    }

    /** Times (hours, local clock) for [date]; [tz] is the phone's UTC offset in hours on that date. */
    private fun raw(date: LocalDate, lat: Double, lon: Double, tz: Double): DoubleArray {
        val jd = date.toEpochDay() + 2440587.5 - lon / (15 * 24)
        fun noon(t: Double) = 12 - sun(jd + t).second
        fun at(angle: Double, t: Double, before: Boolean): Double {
            val decl = sun(jd + t).first
            val v = (-sin(rad(angle)) - sin(rad(decl)) * sin(rad(lat))) / (cos(rad(decl)) * cos(rad(lat)))
            val h = deg(acos(v.coerceIn(-1.0, 1.0))) / 15
            return noon(t) + if (before) -h else h
        }
        var f = 5.0; var sr = 6.0; var d = 12.0; var ss = 18.0; var m = 18.0
        repeat(2) { // refine with the times found
            f = at(FAJR_ANGLE, f / 24, true); sr = at(0.833, sr / 24, true); d = noon(d / 24)
            ss = at(0.833, ss / 24, false); m = at(MAGHRIB_ANGLE, m / 24, false)
        }
        val adj = tz - lon / 15
        f += adj; sr += adj; d += adj; ss += adj; m += adj
        val midnight = ss + ((f + 24) - ss) / 2
        return doubleArrayOf(f, sr, d + 0.5 / 60, m, midnight)
    }

    /** "4:57", "11:29", "5:05" — same 12-hour style as the printed calendar. */
    private fun fmt(hours: Double): String {
        var mins = (hours * 60).roundToInt()
        mins = ((mins % 1440) + 1440) % 1440
        val h = mins / 60; val m = mins % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${"%02d".format(java.util.Locale.US, m)}"
    }

    fun times(ctx: Context, date: LocalDate): DayTimes {
        val tz = ZoneId.systemDefault().rules.getOffset(date.atTime(LocalTime.NOON)).totalSeconds / 3600.0
        val r = raw(date, lat(ctx), lon(ctx), tz)
        return DayTimes(
            imsak = fmt(r[0] - IMSAK_BEFORE_FAJR / 60.0), fajr = fmt(r[0]), sunrise = fmt(r[1]), dhuhr = fmt(r[2]),
            maghrib = fmt(r[3]), midnight = fmt(r[4]), qibla = "", sahur = "",
        )
    }
}
