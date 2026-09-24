package com.golftracker.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.tracker.TrajectoryMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTrackerManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _currentLocation = MutableStateFlow<GpsCoordinate?>(null)
    val currentLocation: StateFlow<GpsCoordinate?> = _currentLocation.asStateFlow()

    private val _launchLocation = MutableStateFlow<GpsCoordinate?>(null)
    val launchLocation: StateFlow<GpsCoordinate?> = _launchLocation.asStateFlow()

    private val _landingLocation = MutableStateFlow<GpsCoordinate?>(null)
    val landingLocation: StateFlow<GpsCoordinate?> = _landingLocation.asStateFlow()

    private val _shotDistanceYards = MutableStateFlow(0.0)
    val shotDistanceYards: StateFlow<Double> = _shotDistanceYards.asStateFlow()

    private val _shotDistanceMeters = MutableStateFlow(0.0)
    val shotDistanceMeters: StateFlow<Double> = _shotDistanceMeters.asStateFlow()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val lastLocation = result.lastLocation ?: return
            val coord = GpsCoordinate(
                latitude = lastLocation.latitude,
                longitude = lastLocation.longitude,
                altitude = lastLocation.altitude,
                accuracyMeters = lastLocation.accuracy
            )
            _currentLocation.value = coord

            // If launch location is set, update live distance estimation as golfer walks
            val launch = _launchLocation.value
            if (launch != null && _landingLocation.value == null) {
                val distMeters = TrajectoryMath.calculateGpsDistanceMeters(launch, coord)
                _shotDistanceMeters.value = distMeters
                _shotDistanceYards.value = TrajectoryMath.metersToYards(distMeters)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    _currentLocation.value = GpsCoordinate(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        accuracyMeters = loc.accuracy
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Locks current GPS location as the Shot Tee / Launch Point.
     */
    fun recordLaunchLocation(): GpsCoordinate? {
        val curr = _currentLocation.value
        _launchLocation.value = curr
        _landingLocation.value = null
        _shotDistanceYards.value = 0.0
        _shotDistanceMeters.value = 0.0
        return curr
    }

    /**
     * Locks current GPS location as the Ball Landing Point.
     */
    fun recordLandingLocation(): GpsCoordinate? {
        val curr = _currentLocation.value ?: return null
        val launch = _launchLocation.value

        _landingLocation.value = curr

        if (launch != null) {
            val distMeters = TrajectoryMath.calculateGpsDistanceMeters(launch, curr)
            _shotDistanceMeters.value = distMeters
            _shotDistanceYards.value = TrajectoryMath.metersToYards(distMeters)
        }
        return curr
    }

    /**
     * Manually overrides shot distance in yards.
     */
    fun setManualDistanceYards(yards: Double) {
        _shotDistanceYards.value = yards
        _shotDistanceMeters.value = TrajectoryMath.yardsToMeters(yards)
    }

    fun reset() {
        _launchLocation.value = null
        _landingLocation.value = null
        _shotDistanceYards.value = 0.0
        _shotDistanceMeters.value = 0.0
    }
}
