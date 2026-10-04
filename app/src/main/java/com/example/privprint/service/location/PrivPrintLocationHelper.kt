package com.example.privprint.service.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object PrivPrintLocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    fun getCurrentLocation(
        context: Context,
        onLocationReceived: (Location?) -> Unit
    ) {
        if (!hasLocationPermission(context)) {
            onLocationReceived(null)
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onLocationReceived(null)
            return
        }

        try {
            // Check last known location first for rapid response
            val gpsLastKnown = try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } else null
            } catch (e: Exception) { null }

            val networkLastKnown = try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } else null
            } catch (e: Exception) { null }

            val bestLastKnown = when {
                gpsLastKnown != null && networkLastKnown != null -> {
                    if (gpsLastKnown.time > networkLastKnown.time) gpsLastKnown else networkLastKnown
                }
                gpsLastKnown != null -> gpsLastKnown
                else -> networkLastKnown
            }

            if (bestLastKnown != null && (System.currentTimeMillis() - bestLastKnown.time) < 10 * 60 * 1000) {
                onLocationReceived(bestLastKnown)
                return
            }

            // Otherwise request single location update
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider == null) {
                onLocationReceived(bestLastKnown)
                return
            }

            var delivered = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!delivered) {
                        delivered = true
                        try { locationManager.removeUpdates(this) } catch (e: Exception) {}
                        onLocationReceived(location)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

        } catch (e: SecurityException) {
            onLocationReceived(null)
        } catch (e: Exception) {
            onLocationReceived(null)
        }
    }

    /**
     * Calculates distance between two geographical points using the Haversine formula.
     * @return distance in kilometers (km)
     */
    fun calculateHaversineDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371.0 // Earth radius in kilometers

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
