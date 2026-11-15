package de.familienplaner.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper

class LocService : Service() {
    private var lm: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { cb?.invoke(l) }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("fp_loc", "Standort teilen", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "fp_loc") else Notification.Builder(this)
        val n = b.setContentTitle("Familien-Planer")
            .setContentText("Dein Standort wird mit der Familie geteilt")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(7, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        startUpdates()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startUpdates() {
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        try { m.removeUpdates(listener) } catch (e: Exception) {}
        try {
            if (m.isProviderEnabled(LocationManager.GPS_PROVIDER)) m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000L, 30f, listener, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000L, 30f, listener, Looper.getMainLooper())
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { lm?.removeUpdates(listener) } catch (e: Exception) {}
        super.onDestroy()
    }

    companion object {
        @Volatile var cb: ((Location) -> Unit)? = null
    }
}
