package com.example.mockloc

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat

class MockService : Service() {
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private var lat = 0.0
    private var lon = 0.0
    private var route: DoubleArray? = null
    private var ti = 1
    private var speedMs = 0f
    private var bearing = 0f
    private var active = false

    private val tick = object : Runnable {
        override fun run() { advance(); push(); handler.postDelayed(this, 1000) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mock", "Mock location", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "mock")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mock location running").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)

        val r = i?.getDoubleArrayExtra("route")
        if (r != null && r.size >= 4) {
            route = r; lat = r[0]; lon = r[1]; ti = 1
            speedMs = i.getFloatExtra("speed", 30f) / 3.6f
        } else {
            route = null; speedMs = 0f
            lat = i?.getDoubleExtra("lat", 0.0) ?: 0.0
            lon = i?.getDoubleExtra("lon", 0.0) ?: 0.0
        }

        if (!active) {
            lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                providers.forEach {
                    try { lm.removeTestProvider(it) } catch (_: Exception) {}
                    lm.addTestProvider(it, false, false, false, false, true, true, true, 1, 1)
                    lm.setTestProviderEnabled(it, true)
                }
                active = true
                handler.post(tick)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Select this app under Developer options > Mock location app", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun advance() {
        val r = route ?: return
        if (ti * 2 + 1 >= r.size) { speedMs = 0f; return }
        val tl = r[ti * 2]; val tn = r[ti * 2 + 1]
        val res = FloatArray(2)
        Location.distanceBetween(lat, lon, tl, tn, res)
        if (res[0] > 0.5f) bearing = if (res[1] < 0) res[1] + 360f else res[1]
        if (res[0] <= speedMs) { lat = tl; lon = tn; ti++ }
        else { val f = speedMs / res[0]; lat += (tl - lat) * f; lon += (tn - lon) * f }
    }

    private fun push() {
        providers.forEach {
            val l = Location(it).apply {
                latitude = lat; longitude = lon; altitude = 0.0
                accuracy = 3f; speed = speedMs; bearing = this@MockService.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try { lm.setTestProviderLocation(it, l) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (active) providers.forEach { try { lm.removeTestProvider(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
