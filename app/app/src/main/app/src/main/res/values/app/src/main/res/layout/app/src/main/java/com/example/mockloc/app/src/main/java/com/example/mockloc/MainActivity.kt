package com.example.mockloc

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var label: TextView
    private lateinit var routeSwitch: SwitchCompat
    private lateinit var speed: EditText
    private val points = mutableListOf<GeoPoint>()
    private val drawn = mutableListOf<Overlay>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osm", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(R.layout.activity_main)
        map = findViewById(R.id.map); label = findViewById(R.id.coords)
        routeSwitch = findViewById(R.id.route); speed = findViewById(R.id.speed)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(5.0); map.controller.setCenter(GeoPoint(30.0, 70.0))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { select(p); return true }
            override fun longPressHelper(p: GeoPoint) = false
        }))

        findViewById<Button>(R.id.start).setOnClickListener { start() }
        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, MockService::class.java)); toast("Stopped. Real GPS restored.")
        }
        findViewById<Button>(R.id.clear).setOnClickListener { points.clear(); redraw() }
        findViewById<Button>(R.id.dev).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        findViewById<Button>(R.id.save).setOnClickListener { saveFav() }
        findViewById<Button>(R.id.favs).setOnClickListener { showFavs() }
        findViewById<Button>(R.id.search).setOnClickListener { search() }
        findViewById<EditText>(R.id.q).setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
        routeSwitch.setOnCheckedChangeListener { _, on ->
            if (!on && points.size > 1) { val last = points.last(); points.clear(); points.add(last) }
            redraw()
        }

        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
    }

    private fun select(p: GeoPoint) {
        if (!routeSwitch.isChecked) points.clear()
        points.add(p); redraw()
    }

    private fun redraw() {
        map.overlays.removeAll(drawn.toSet()); drawn.clear()
        if (points.size > 1) {
            val line = Polyline(map).apply { setPoints(points); outlinePaint.strokeWidth = 8f }
            drawn.add(line)
        }
        points.forEachIndexed { i, p ->
            drawn.add(Marker(map).apply {
                position = p; title = "Point ${i + 1}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            })
        }
        map.overlays.addAll(drawn); map.invalidate()
        label.text = when {
            points.isEmpty() -> "Tap the map to choose a location"
            points.size == 1 -> "Selected: %.6f, %.6f".format(points[0].latitude, points[0].longitude)
            else -> "Route with ${points.size} points"
        }
    }

    private fun start() {
        if (points.isEmpty()) return toast("Pick a location first")
        val i = Intent(this, MockService::class.java)
        if (routeSwitch.isChecked && points.size > 1) {
            i.putExtra("route", points.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
            i.putExtra("speed", (speed.text.toString().toFloatOrNull() ?: 30f).coerceIn(1f, 300f))
        } else {
            i.putExtra("lat", points.last().latitude); i.putExtra("lon", points.last().longitude)
        }
        ContextCompat.startForegroundService(this, i)
        toast("Mock location started")
    }

    private fun search() {
        val q = findViewById<EditText>(R.id.q).text.toString().trim()
        if (q.isEmpty()) return
        Thread {
            try {
                val c = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                        URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", packageName)
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                runOnUiThread {
                    if (arr.length() == 0) toast("Not found") else {
                        val o = arr.getJSONObject(0)
                        val p = GeoPoint(o.getString("lat").toDouble(), o.getString("lon").toDouble())
                        map.controller.setZoom(15.0); map.controller.animateTo(p); select(p)
                    }
                }
            } catch (e: Exception) { runOnUiThread { toast("Search failed: check internet") } }
        }.start()
    }

    private val prefs get() = getSharedPreferences("favs", MODE_PRIVATE)

    private fun saveFav() {
        if (points.isEmpty()) return toast("Pick a location first")
        val p = points.last()
        val name = EditText(this).apply { hint = "Name" }
        AlertDialog.Builder(this).setTitle("Save location").setView(name)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().replace("|", " ").ifBlank { "Place" }
                prefs.edit().putStringSet("list",
                    (prefs.getStringSet("list", emptySet())!! + "$n|${p.latitude}|${p.longitude}")).apply()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFavs() {
        val list = prefs.getStringSet("list", emptySet())!!.toList().sorted()
        if (list.isEmpty()) return toast("No favorites yet")
        AlertDialog.Builder(this).setTitle("Favorites")
            .setItems(list.map { it.substringBefore("|") }.toTypedArray()) { _, w ->
                val s = list[w].split("|")
                val p = GeoPoint(s[1].toDouble(), s[2].toDouble())
                map.controller.setZoom(15.0); map.controller.animateTo(p); select(p)
            }
            .setNeutralButton("Delete all") { _, _ -> prefs.edit().clear().apply() }
            .setNegativeButton("Close", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }
}
