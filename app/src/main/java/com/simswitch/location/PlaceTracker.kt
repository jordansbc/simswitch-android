package com.simswitch.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Turns raw location into the geohash key the learned store is indexed by.
 *
 * Uses `LocationManager.FUSED_PROVIDER` (API 31+) rather than the Play Services fused client, so
 * the app keeps zero Google dependencies. Updates are deliberately coarse — 60s / 100m — because
 * this drives a slow-moving learned map, not turn-by-turn navigation, and location is by far the
 * most expensive thing SimSwitch does to the battery.
 */
class PlaceTracker(private val context: Context) {

    data class Place(val key: String, val lat: Double, val lon: Double, val accuracy: Float, val at: Long)

    private val _place = MutableStateFlow<Place?>(null)
    val place: StateFlow<Place?> = _place

    private var manager: LocationManager? = null

    private val listener = LocationListener { location -> onLocation(location) }

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        manager = lm

        val provider = when {
            lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }

        runCatching {
            lm.requestLocationUpdates(provider, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, Looper.getMainLooper())
            // Seed immediately from the last known fix so a place is available before the first
            // update arrives, rather than dropping the first minute of samples on the floor.
            lm.getLastKnownLocation(provider)?.let(::onLocation)
        }
    }

    fun stop() {
        runCatching { manager?.removeUpdates(listener) }
        manager = null
    }

    private fun onLocation(location: Location) {
        // A 500m-accuracy cell-tower fix would smear several geohash cells together and pollute
        // the learned map with confident-looking nonsense. Better to record no place at all.
        if (location.accuracy > MAX_ACCURACY_M) return

        _place.value = Place(
            key = Geohash.encode(location.latitude, location.longitude),
            lat = location.latitude,
            lon = location.longitude,
            accuracy = location.accuracy,
            at = System.currentTimeMillis(),
        )
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val MIN_DISTANCE_M = 100f
        const val MAX_ACCURACY_M = 200f
    }
}
