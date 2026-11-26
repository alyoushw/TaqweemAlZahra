package com.alzahra.taqweem

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Qibla screen. Two modes:
 *  - البوصلة: phone compass, three selectable looks.
 *  - الخريطة: satellite map with the user's GPS position and the great-circle line to the Kaaba,
 *    so the direction can be checked against the building around you without trusting the compass.
 */
object Qibla {
    /** Kaaba coordinates (same values Google's Qibla Finder uses). */
    const val KAABA_LAT = 21.4224779
    const val KAABA_LON = 39.8251832
    private const val AHSA_LAT = 25.383
    private const val AHSA_LON = 49.586

    /** Initial great-circle bearing from a point to the Kaaba, degrees from true north. */
    fun bearing(lat: Double, lon: Double): Double {
        val p1 = Math.toRadians(lat); val p2 = Math.toRadians(KAABA_LAT)
        val dl = Math.toRadians(KAABA_LON - lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Haversine distance to the Kaaba in km. */
    fun distanceKm(lat: Double, lon: Double): Double {
        val p1 = Math.toRadians(lat); val p2 = Math.toRadians(KAABA_LAT)
        val dp = p2 - p1; val dl = Math.toRadians(KAABA_LON - lon)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 6371.0088 * 2 * asin(sqrt(a))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("zahra_prefs", Context.MODE_PRIVATE)
    fun style(ctx: Context) = prefs(ctx).getInt("qibla_style", 0)
    fun setStyle(ctx: Context, s: Int) = prefs(ctx).edit().putInt("qibla_style", s).apply()

    /** How the satellite map behaves: 0 turned by hand, 1 turns with the phone, 2 turns with the phone + building correction. */
    val MAP_MODES = listOf(
        "يدوي" to "تلف الخريطة وتحركها بإصبعيك بنفسك",
        "تتبّع الاتجاه" to "الخريطة تلف مع الجوال، وسهم يقول لك لف يمين أو يسار، والجوال يهتز لما تكون على القبلة",
        "تتبّع + تصحيح بالمبنى" to "مثل التتبّع، ومعه زر «📐 صحّح بالمبنى»: تطابق جدار بيتك مرة وحدة فيصحّح التطبيق انحراف البوصلة",
    )
    fun mapMode(ctx: Context) = prefs(ctx).getInt("qibla_map_mode", 0)
    fun setMapMode(ctx: Context, m: Int) = prefs(ctx).edit().putInt("qibla_map_mode", m).apply()
    /** Degrees added to the phone's compass, measured against the satellite picture of the user's building. */
    fun calib(ctx: Context) = prefs(ctx).getFloat("qibla_calib", 0f)
    fun setCalib(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("qibla_calib", v).apply()
    /** The correction belongs to the building it was measured in: remember where, and use it only there. */
    fun setCalibAt(ctx: Context, v: Float, lat: Double, lon: Double) =
        prefs(ctx).edit().putFloat("qibla_calib", v).putFloat("qibla_calib_lat", lat.toFloat()).putFloat("qibla_calib_lon", lon.toFloat()).apply()
    fun calibHere(ctx: Context, lat: Double, lon: Double): Float {
        val c = calib(ctx)
        if (c == 0f) return 0f
        val p = prefs(ctx)
        if (!p.contains("qibla_calib_lat")) return c
        val d = FloatArray(1)
        Location.distanceBetween(lat, lon, p.getFloat("qibla_calib_lat", 0f).toDouble(), p.getFloat("qibla_calib_lon", 0f).toDouble(), d)
        return if (d[0] < 1000f) c else 0f
    }

    fun chooseMapMode(activity: Activity, onChanged: () -> Unit = {}) {
        Dialogs.sheet(activity, "🗺", "نمط خريطة القبلة", "اختر طريقة استخدام خريطة الأقمار الصناعية") { col, dialog ->
            val ui = Ui(activity)
            MAP_MODES.forEachIndexed { i, (name, desc) ->
                val sel = mapMode(activity) == i
                col.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                    background = ui.rounded(if (sel) Colors.SELECTED else Colors.CARD, 14, if (sel) ui.fill else Colors.STROKE, if (sel) 2 else 1)
                    addView(ui.text((if (sel) "◉  " else "○  ") + name, 16f, Colors.TEXT, bold = true))
                    addView(ui.text(desc, 13f, Colors.MUTED).apply { setPadding(0, ui.dp(4), 0, 0) })
                    setOnClickListener { setMapMode(activity, i); dialog.dismiss(); onChanged() }
                }, ui.match(0, 4, 0, 4))
            }
            if (calib(activity) != 0f) col.addView(ui.outlineButton("مسح تصحيح البوصلة (${calib(activity).roundToInt()}°)") {
                setCalib(activity, 0f); dialog.dismiss(); Toast.makeText(activity, "تم مسح التصحيح", Toast.LENGTH_SHORT).show()
            }, ui.match(0, 8, 0, 0))
        }
    }

    private var permissionCallback: (() -> Unit)? = null
    fun onPermissionResult() { permissionCallback?.invoke(); permissionCallback = null }

    private fun hasLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled", "MissingPermission")
    fun show(activity: Activity) {
        val ui = Ui(activity)
        val dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(ui.theme.primary)
        }
        // Header
        val head = ui.row().apply { setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(10)) }
        val ht = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(ui.text("اتجاه القبلة", 21f, ui.theme.accent, bold = true))
        val sub = ui.text("", 13.5f, Color.parseColor("#E6FFFFFF"))
        ht.addView(sub)
        head.addView(ht, ui.weight())
        head.addView(ui.text("✕", 24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        root.addView(head)

        // Mode tabs
        val tabs = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.rounded(ui.theme.primaryDark, 14)
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }
        val tabViews = listOf("🧭 البوصلة", "🛰 الخريطة").map { label ->
            ui.text(label, 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(10)) }
        }
        tabViews.forEach { tabs.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        root.addView(tabs, ui.match(14, 0, 14, 12))

        val body = FrameLayout(activity)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- state shared by both modes ----
        // Start from the place the user set (📍), not Ahsa; live GPS refines it when it arrives.
        val saved = LocationTimes.hasPlace(activity)
        var lat = if (saved) LocationTimes.lat(activity) else AHSA_LAT
        var lon = if (saved) LocationTimes.lon(activity) else AHSA_LON
        var fromGps = false
        var qibla = bearing(lat, lon)
        var heading = Float.NaN

        // ---- compass mode ----
        val compassBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Colors.BG)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        val styles = ui.row()
        val styleViews = listOf("كلاسيكي", "داكن", "سهم كبير").mapIndexed { i, s ->
            ui.text(s, 14f, Colors.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9)) }
        }
        styleViews.forEach { styles.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) }) }
        val compass = CompassView(activity, ui.theme.primary, ui.theme.accent)
        val big = ui.text("", 40f, ui.ink, bold = true).apply { gravity = Gravity.CENTER }
        val hint = ui.text("", 18f, Color.parseColor("#E65100"), bold = true).apply { gravity = Gravity.CENTER }
        val note = ui.text("البوصلة تتأثر بالحديد والأجهزة القريبة. للتأكد استخدم «الخريطة» وقارن الخط بالمبنى حولك، أو حرّك الجوال على شكل الرقم 8 لمعايرة البوصلة.", 12.5f, Colors.MUTED).apply { gravity = Gravity.CENTER }
        // Portrait: everything in one column. Landscape: the compass on one side, the controls beside it.
        val side = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val sideScroll = android.widget.ScrollView(activity).apply { isFillViewport = true }
        var landLayout: Boolean? = null
        fun arrangeCompass(land: Boolean) {
            if (landLayout == land) return
            landLayout = land
            listOf(styles, compass, big, hint, note, side, sideScroll).forEach { (it.parent as? ViewGroup)?.removeView(it) }
            if (land) {
                compassBox.orientation = LinearLayout.HORIZONTAL
                compassBox.layoutDirection = View.LAYOUT_DIRECTION_RTL
                side.addView(styles, ui.match(0, 0, 0, 10)); side.addView(big, ui.match()); side.addView(hint, ui.match(0, 2, 0, 8)); side.addView(note)
                sideScroll.addView(side)
                compassBox.addView(sideScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                compassBox.addView(compass, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            } else {
                compassBox.orientation = LinearLayout.VERTICAL
                compassBox.addView(styles, ui.match(0, 0, 0, 10))
                compassBox.addView(compass, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                compassBox.addView(big, ui.match()); compassBox.addView(hint, ui.match(0, 2, 0, 8)); compassBox.addView(note)
            }
        }
        arrangeCompass(activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        compassBox.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val land = v.width > v.height * 1.15f
            if (landLayout != land) v.post { arrangeCompass(land) }
        }

        fun paintStyles() {
            val s = style(activity)
            styleViews.forEachIndexed { i, v ->
                v.background = if (i == s) ui.rounded(ui.fill, 12) else ui.rounded(Colors.CARD, 12, Colors.STROKE, 1)
                v.setTextColor(if (i == s) ui.onFill else Colors.TEXT)
            }
            compass.style = s
            compassBox.setBackgroundColor(when (s) { 1 -> Color.parseColor("#121A20"); 2 -> ui.theme.primaryDark; else -> Colors.BG })
            big.setTextColor(when (s) { 0 -> ui.theme.primary; else -> ui.theme.accent })
            compass.invalidate()
        }
        styleViews.forEachIndexed { i, v -> v.setOnClickListener { setStyle(activity, i); paintStyles() } }
        paintStyles()

        // ---- map mode ----
        val mapBox = FrameLayout(activity)
        val web = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.parseColor("#1B1B1B"))
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/qibla_map.html")
        }
        mapBox.addView(web)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.rounded(Color.parseColor("#F2151515"), 24)
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16))
        }
        val dist = ui.text("", 28f, Color.WHITE, bold = true)
        val info = ui.text("", 13.5f, Color.parseColor("#BFFFFFFF"))
        panel.addView(dist); panel.addView(info)
        val btns = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        fun chip(label: String, bg: Int, fg: Int, js: String?, action: (() -> Unit)? = null) = btns.addView(ui.text(label, 14f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9))
            background = ui.rounded(bg, 18)
            setOnClickListener { if (js != null) web.evaluateJavascript(js, null); action?.invoke() }
        }, ui.wrap(0, 0, 6, 0))
        val mode = mapMode(activity)
        var tracking = mode >= 1
        if (mode >= 1) {
            lateinit var trackChip: android.widget.TextView
            chip("🧭 تتبّع الاتجاه", Color.parseColor("#4FC3D4"), Color.BLACK, null) {
                tracking = !tracking
                web.evaluateJavascript("setTracking($tracking)", null)
                trackChip.background = ui.rounded(if (tracking) Color.parseColor("#4FC3D4") else Color.parseColor("#2A2A2A"), 18)
                trackChip.setTextColor(if (tracking) Color.BLACK else Color.WHITE)
            }
            trackChip = btns.getChildAt(btns.childCount - 1) as android.widget.TextView
        }
        if (mode == 2) chip("📐 صحّح بالمبنى", Color.parseColor("#E9C46A"), Color.parseColor("#2A1A00"), "startCalib()")
        chip("◎ موقعي", Color.parseColor(if (mode >= 1) "#2A2A2A" else "#4FC3D4"), if (mode >= 1) Color.WHITE else Color.BLACK, "goMe()")
        chip("🕋 الكعبة", Color.parseColor("#2A2A2A"), Color.WHITE, "goKaaba()")
        chip("↔ الكل", Color.parseColor("#2A2A2A"), Color.WHITE, "overview()")
        chip("خرائط قوقل ↗", Color.parseColor("#2A2A2A"), Color.WHITE, null) {
            val uri = Uri.parse("geo:$KAABA_LAT,$KAABA_LON?q=$KAABA_LAT,$KAABA_LON(الكعبة المشرفة)")
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")) }
                .onFailure { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$KAABA_LAT,$KAABA_LON"))) }
        }
        panel.addView(android.widget.HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(btns) })
        val mapHelp = ui.text("الخط الأصفر هو اتجاه القبلة من مكان وقوفك. قرّب الخريطة حتى ترى المبنى، وقارن الخط بالجدران أو الشارع لتعرف الاتجاه بدقة بدون الاعتماد على البوصلة.", 12f, Color.parseColor("#B3FFFFFF")).apply { setPadding(0, ui.dp(10), 0, 0) }
        panel.addView(mapHelp)
        // Sideways there is little height: the info card moves to one side (map visible beside it) and the
        // help text hides; upright it goes back across the bottom.
        var mapLand: Boolean? = null
        mapBox.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val land = v.width > v.height * 1.15f
            if (land == mapLand || v.width == 0) return@addOnLayoutChangeListener
            mapLand = land
            v.post {
                mapHelp.visibility = if (land) View.GONE else View.VISIBLE
                sub.visibility = if (land) View.GONE else View.VISIBLE
                panel.layoutParams = if (land)
                    FrameLayout.LayoutParams((v.width * 0.42f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(10)) }
                else FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(10)) }
            }
        }
        mapBox.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { setMargins(ui.dp(10), 0, ui.dp(10), ui.dp(10)) })

        fun refreshTexts() {
            val km = distanceKm(lat, lon)
            val where = if (fromGps) "موقعك الحالي" else if (saved) "📍 ${LocationTimes.placeLabel(activity)}" else "الأحساء (افتراضي)"
            sub.text = "$where • ${"%,.0f".format(km)} كم إلى مكة"
            dist.text = "${"%,.1f".format(km)} كم"
            info.text = "اتجاه القبلة ${qibla.roundToInt()}° من الشمال • " + if (fromGps) "حسب موقعك بالـ GPS" else if (saved) "حسب موقعك المحدد" else "بانتظار تحديد موقعك…"
            compass.qibla = qibla.toFloat()
        }
        refreshTexts()

        fun select(mode: Int) {
            tabViews.forEachIndexed { i, v ->
                v.background = if (i == mode) ui.rounded(ui.theme.accent, 11) else null
                v.setTextColor(if (i == mode) Color.parseColor("#2A1A00") else Color.WHITE)
            }
            body.removeAllViews()
            body.addView(if (mode == 0) compassBox else mapBox)
        }
        tabViews.forEachIndexed { i, v -> v.setOnClickListener { select(i) } }
        select(0)

        // ---- sensors ----
        val sm = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        var aligned = false
        var declination = 0f
        fun updateDeclination() {
            declination = runCatching {
                android.hardware.GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
            }.getOrDefault(0f)
        }
        updateDeclination()
        var mapAligned = false
        var rawHeading = Float.NaN
        val vibrator = activity.getSystemService(android.os.Vibrator::class.java)
        // Building correction saved by «📐 صحّح بالمبنى»: the map's turn shows the real heading of the phone's top.
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface fun calibrated(mapBearing: Float) {
                activity.runOnUiThread {
                    if (rawHeading.isNaN()) return@runOnUiThread
                    var c = ((-mapBearing) - rawHeading - declination) % 360f
                    if (c > 180) c -= 360f
                    if (c < -180) c += 360f
                    setCalibAt(activity, c, lat, lon)
                    Toast.makeText(activity, "تم تصحيح البوصلة ${c.roundToInt()}°", Toast.LENGTH_SHORT).show()
                }
            }
        }, "Q")
        val sensorListener = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val ori = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                // Heading of the top of the screen: add the display rotation when the phone is held sideways.
                @Suppress("DEPRECATION") val turn = activity.windowManager.defaultDisplay.rotation * 90
                val raw = ((Math.toDegrees(ori[0].toDouble()) + turn + 360) % 360).toFloat()
                rawHeading = if (rawHeading.isNaN()) raw else {
                    var d = raw - rawHeading
                    if (d > 180) d -= 360f
                    if (d < -180) d += 360f
                    (rawHeading + d * 0.2f + 360f) % 360f
                }
                // The phone measures magnetic north; the qibla bearing is from true north: add the local declination
                // (about 3° in the Gulf). Then the building correction, if it was made here.
                val az = ((raw + declination + calibHere(activity, lat, lon)) % 360f + 360f) % 360f
                heading = if (heading.isNaN()) az else {
                    var d = az - heading
                    if (d > 180) d -= 360f
                    if (d < -180) d += 360f
                    (heading + d * 0.2f + 360f) % 360f
                }
                compass.heading = heading
                compass.invalidate()
                var diff = (qibla - heading).toFloat()
                if (diff > 180) diff -= 360f
                if (diff < -180) diff += 360f
                val ok = abs(diff) <= 3
                if (ok && !aligned) compass.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                aligned = ok
                compass.aligned = ok
                big.text = "${qibla.roundToInt()}°"
                hint.text = when {
                    ok -> "✓ أنت متجه نحو القبلة"
                    diff > 0 -> "↷ انعطف يميناً ${diff.roundToInt()}°"
                    else -> "↶ انعطف يساراً ${(-diff).roundToInt()}°"
                }
                hint.setTextColor(if (ok) Color.parseColor("#2E9E4F") else Color.parseColor("#E65100"))
                web.evaluateJavascript("heading(${heading})", null)
                if (tracking) {
                    web.evaluateJavascript("track($heading,$diff,$ok)", null)
                    if (ok && !mapAligned && body.getChildAt(0) === mapBox) runCatching {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(90, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    mapAligned = ok
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
        else { big.text = "${qibla.roundToInt()}°"; hint.text = "جهازك بدون بوصلة — استخدم الخريطة" }

        // ---- location ----
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var firstFix = true
        var pageReady = false
        var lastFix: Location? = null
        fun pushToMap() {
            val l = lastFix ?: return
            if (!pageReady) return
            web.evaluateJavascript("setMe(${l.latitude},${l.longitude},${l.accuracy.coerceAtLeast(3f)},$firstFix)", null)
            firstFix = false
        }
        val locListener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                lat = l.latitude; lon = l.longitude; fromGps = true
                lastFix = l
                qibla = bearing(lat, lon)
                updateDeclination()
                refreshTexts()
                pushToMap()
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        fun startLocation() {
            if (!hasLocation(activity)) return
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { p ->
                runCatching { lm.getLastKnownLocation(p)?.let { if (!fromGps) locListener.onLocationChanged(it) } }
                runCatching { lm.requestLocationUpdates(p, 1000L, 0f, locListener, Looper.getMainLooper()) }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageReady = true; pushToMap()
                if (tracking) view.evaluateJavascript("setTracking(true)", null)
            }
        }
        if (hasLocation(activity)) startLocation()
        else {
            permissionCallback = { startLocation() }
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 21)
        }

        dialog.setOnDismissListener {
            sm.unregisterListener(sensorListener)
            runCatching { lm.removeUpdates(locListener) }
            web.destroy()
        }
        dialog.setContentView(root)
        Immersive.padBottom(root)
        dialog.showImmersive()
    }
}

/** Compass drawing in three looks: 0 classic gold dial, 1 dark ring, 2 big arrow. */
class CompassView(ctx: Context, private val primary: Int, private val gold: Int) : View(ctx) {
    var heading = 0f
    var qibla = 248f
    var aligned = false
    var style = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val green = Color.parseColor("#2ECC71")

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 16f
        val h = if (heading.isNaN()) 0f else heading
        when (style) {
            1 -> drawDark(c, cx, cy, r, h)
            2 -> drawArrow(c, cx, cy, r, h)
            else -> drawClassic(c, cx, cy, r, h)
        }
    }

    private fun kaaba(c: Canvas, x: Float, y: Float, s: Float, ring: Int) = KaabaIcon.draw(c, x, y, s * 1.25f, ring, gold)

    private fun drawClassic(c: Canvas, cx: Float, cy: Float, r: Float, h: Float) {
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#FFFCF5")
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.04f; p.color = if (aligned) green else gold
        c.drawCircle(cx, cy, r, p)
        c.save(); c.rotate(-h, cx, cy)
        for (i in 0 until 72) {
            val len = if (i % 18 == 0) r * 0.12f else if (i % 6 == 0) r * 0.08f else r * 0.04f
            p.strokeWidth = if (i % 6 == 0) 4f else 2f
            p.color = Color.parseColor("#B9A986")
            c.save(); c.rotate(i * 5f, cx, cy)
            c.drawLine(cx, cy - r + 8, cx, cy - r + 8 + len, p)
            c.restore()
        }
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER; p.textSize = r * 0.13f
        listOf("ش" to 0f, "ق" to 90f, "ج" to 180f, "غ" to 270f).forEach { (t, a) ->
            p.color = if (t == "ش") Colors.BAD else Color.parseColor("#8A7A5A")
            val ra = Math.toRadians((a - 90).toDouble())
            c.drawText(t, cx + (r * 0.72f) * cos(ra).toFloat(), cy + (r * 0.72f) * sin(ra).toFloat() + p.textSize / 3, p)
        }
        // Needle to the Kaaba
        c.rotate(qibla, cx, cy)
        path.reset(); path.moveTo(cx, cy - r * 0.78f); path.lineTo(cx + r * 0.1f, cy); path.lineTo(cx, cy - r * 0.08f); path.lineTo(cx - r * 0.1f, cy); path.close()
        p.color = if (aligned) green else primary; c.drawPath(path, p)
        path.reset(); path.moveTo(cx, cy + r * 0.35f); path.lineTo(cx + r * 0.07f, cy); path.lineTo(cx - r * 0.07f, cy); path.close()
        p.color = Color.parseColor("#D9C9A6"); c.drawPath(path, p)
        c.restore()
        val ka = Math.toRadians((qibla - h - 90).toDouble())
        kaaba(c, cx + r * 0.86f * cos(ka).toFloat(), cy + r * 0.86f * sin(ka).toFloat(), r * 0.11f, gold)
        p.color = primary; c.drawCircle(cx, cy, r * 0.05f, p)
    }

    private fun drawDark(c: Canvas, cx: Float, cy: Float, r: Float, h: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.12f; p.color = Color.parseColor("#2A3640")
        c.drawCircle(cx, cy, r * 0.9f, p)
        var diff = qibla - h
        if (diff > 180) diff -= 360f
        if (diff < -180) diff += 360f
        // Arc from the top (where the phone points) to the Qibla
        p.color = if (aligned) green else gold; p.strokeCap = Paint.Cap.ROUND
        val oval = RectF(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f)
        c.drawArc(oval, -90f, diff, false, p)
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#18232B")
        c.drawCircle(cx, cy, r * 0.74f, p)
        p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = r * 0.32f; p.isFakeBoldText = true
        c.drawText("${abs(diff).roundToInt()}°", cx, cy + r * 0.1f, p)
        p.isFakeBoldText = false; p.textSize = r * 0.1f; p.color = Color.parseColor("#9FB0BD")
        c.drawText(if (aligned) "متجه للقبلة" else if (diff > 0) "إلى اليمين" else "إلى اليسار", cx, cy + r * 0.3f, p)
        // Top marker = phone direction
        path.reset(); path.moveTo(cx, cy - r * 1.02f); path.lineTo(cx + r * 0.06f, cy - r * 0.8f); path.lineTo(cx - r * 0.06f, cy - r * 0.8f); path.close()
        p.color = Color.WHITE; c.drawPath(path, p)
        val a = Math.toRadians((diff - 90).toDouble())
        kaaba(c, cx + r * 0.9f * cos(a).toFloat(), cy + r * 0.9f * sin(a).toFloat(), r * 0.1f, if (aligned) green else gold)
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, r: Float, h: Float) {
        c.save(); c.rotate(qibla - h, cx, cy)
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r * 0.5f, cy - r * 0.15f); path.lineTo(cx + r * 0.18f, cy - r * 0.15f)
        path.lineTo(cx + r * 0.18f, cy + r * 0.85f); path.lineTo(cx - r * 0.18f, cy + r * 0.85f)
        path.lineTo(cx - r * 0.18f, cy - r * 0.15f); path.lineTo(cx - r * 0.5f, cy - r * 0.15f); path.close()
        p.style = Paint.Style.FILL; p.color = if (aligned) green else Color.parseColor("#F3D77A")
        c.drawPath(path, p)
        p.color = Color.parseColor("#111111")
        c.drawRoundRect(RectF(cx - r * 0.13f, cy + r * 0.05f, cx + r * 0.13f, cy + r * 0.32f), 6f, 6f, p)
        p.color = gold; c.drawRect(cx - r * 0.13f, cy + r * 0.11f, cx + r * 0.13f, cy + r * 0.15f, p)
        c.restore()
    }
}

/** The Kaaba drawn as a small 3D cube: black walls, gold kiswa band. Used on the compass and the header button. */
object KaabaIcon {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** @param s half the icon's height; @param outline edge colour (green when facing the qibla). */
    fun draw(c: Canvas, x: Float, y: Float, s: Float, outline: Int, gold: Int) {
        val k = s / 12f
        fun poly(vararg pts: Float) {
            path.reset(); path.moveTo(x + pts[0] * k, y + pts[1] * k)
            var i = 2
            while (i < pts.size) { path.lineTo(x + pts[i] * k, y + pts[i + 1] * k); i += 2 }
            path.close()
        }
        p.style = Paint.Style.FILL
        poly(0f, -11f, 12f, -6f, 12f, 8f, 0f, 12f, -12f, 8f, -12f, -6f); p.color = Color.parseColor("#141414"); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * k; p.strokeJoin = Paint.Join.ROUND; p.color = outline; c.drawPath(path, p)
        p.style = Paint.Style.FILL
        poly(0f, -11f, 12f, -6f, 0f, -2f, -12f, -6f); p.color = Color.parseColor("#2C2C2C"); c.drawPath(path, p)
        poly(-12f, -2.5f, 0f, 1.5f, 12f, -2.5f, 12f, 0.5f, 0f, 4.5f, -12f, 0.5f); p.color = gold; c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.8f * k; p.color = Color.parseColor("#55FFFFFF")
        c.drawLine(x, y - 2f * k, x, y + 12f * k, p)
        c.drawLine(x - 12f * k, y - 6f * k, x, y - 2f * k, p); c.drawLine(x, y - 2f * k, x + 12f * k, y - 6f * k, p)
    }
}

/** Header button showing the Kaaba. */
class KaabaButton(ctx: Context, private val gold: Int) : View(ctx) {
    override fun onDraw(c: Canvas) = KaabaIcon.draw(c, width / 2f, height / 2f, min(width, height) * 0.3f, Color.parseColor("#66FFFFFF"), gold)
}
