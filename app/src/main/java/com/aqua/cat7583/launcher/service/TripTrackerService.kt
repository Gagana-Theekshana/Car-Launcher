package com.aqua.cat7583.launcher.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aqua.cat7583.launcher.data.AquaDatabase
import com.aqua.cat7583.launcher.data.DoorAuditEntity
import com.aqua.cat7583.launcher.data.TripEntity
import com.aqua.cat7583.launcher.telemetry.TelemetryRepository
import com.aqua.cat7583.launcher.telemetry.VehicleTelemetryState
import com.aqua.cat7583.launcher.telemetry.XyAutoCanbusReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight ForegroundService for the XY AUTO (F9212B / 1GB RAM) Head Unit.
 * Uses Android's native LocationManager.GPS_PROVIDER (zero Google Play Services overhead)
 * for real-time speed, Haversine distance, auto-trip recording, and door opening security logs.
 */
class TripTrackerService : Service(), LocationListener {

    companion object {
        private const val TAG = "TripTrackerService"
        private const val NOTIFICATION_CHANNEL_ID = "aqua_telemetry_tracker_channel"
        private const val NOTIFICATION_ID = 7583

        // Auto-trip thresholds
        private const val TRIP_START_SPEED_KMH = 5
        private const val STATIONARY_TIMEOUT_MS = 3 * 60 * 1000L // 3 minutes stationary saves trip
        private const val MIN_TRIP_DISTANCE_KM = 0.05f           // Ignore micro-jitter < 50m

        // Default Sri Lanka (Colombo / Western Province WP CAT 7583) fallback coordinates
        private const val DEFAULT_LAT = 6.9271
        private const val DEFAULT_LNG = 79.8612
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var database: AquaDatabase
    private var locationManager: LocationManager? = null

    private var tickerJob: Job? = null
    private var doorObserverJob: Job? = null

    // Live GPS coordinates & speed state
    @Volatile
    private var currentLat: Double = DEFAULT_LAT

    @Volatile
    private var currentLng: Double = DEFAULT_LNG

    @Volatile
    private var lastValidLocation: Location? = null

    @Volatile
    private var latestGpsSpeedKmh: Int = 0

    // Active Auto-Trip Session State
    private var isTripRecording: Boolean = false
    private var tripStartTimeMillis: Long = 0L
    private var lastMovingTimeMillis: Long = 0L
    private var tripDistanceKm: Float = 0f
    private var tripMaxSpeedKmh: Int = 0
    private var tripSpeedSamplesSum: Long = 0L
    private var tripSpeedSampleCount: Int = 0
    private var tripStartLat: Double = DEFAULT_LAT
    private var tripStartLng: Double = DEFAULT_LNG

    // Snapshot of previous door states for edge-triggered security audit logging
    private var prevDoorsInitialized: Boolean = false
    private var prevFlOpen: Boolean = false
    private var prevFrOpen: Boolean = false
    private var prevRlOpen: Boolean = false
    private var prevRrOpen: Boolean = false
    private var prevTrunkOpen: Boolean = false
    private var prevHoodOpen: Boolean = false

    // Receiver for ACC_OFF / Ignition Sleep events from XY AUTO MCU
    private val accOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == XyAutoCanbusReceiver.ACTION_XYAUTO_ACC_STATE ||
                action == XyAutoCanbusReceiver.ACTION_XYAUTO_ACC_EVENT ||
                action == Intent.ACTION_SHUTDOWN
            ) {
                val accOn = intent.getBooleanExtra("acc_on", true) &&
                    intent.getIntExtra("acc_state", 1) == 1 &&
                    action != Intent.ACTION_SHUTDOWN
                if (!accOn) {
                    serviceScope.launch {
                        finalizeAndSaveActiveTrip(reason = "ACC_OFF")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        TelemetryRepository.initialize(applicationContext)
        database = AquaDatabase.getInstance(applicationContext)

        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        registerAccOffReceiver()
        startNativeGpsUpdates()
        startDoorSecurityAuditObserver()
        startOneSecondTelemetryTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.launch {
            finalizeAndSaveActiveTrip(reason = "SERVICE_DESTROY")
        }
        try {
            unregisterReceiver(accOffReceiver)
        } catch (_: Exception) {
        }
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
        tickerJob?.cancel()
        doorObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    // =========================================================================
    // 1. NATIVE GPS PROVIDER (ZERO GOOGLE PLAY SERVICES OVERHEAD)
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun startNativeGpsUpdates() {
        val hasFine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permission not yet granted; relying on CANBUS/Sim speed.")
            return
        }

        try {
            val mgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationManager = mgr

            val lastKnown = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastKnown != null) {
                currentLat = lastKnown.latitude
                currentLng = lastKnown.longitude
                lastValidLocation = lastKnown
            }

            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request native GPS updates", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        val prevLoc = lastValidLocation
        currentLat = location.latitude
        currentLng = location.longitude

        val rawSpeedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).roundToInt().coerceIn(0, 220)
        } else {
            0
        }
        latestGpsSpeedKmh = rawSpeedKmh

        var stepDistanceKm = 0f
        if (prevLoc != null) {
            val deltaKm = computeHaversineDistanceKm(
                prevLoc.latitude,
                prevLoc.longitude,
                location.latitude,
                location.longitude
            )
            // Filter out stationary GPS drift (< 2 meters or unrealistic jumps > 200m/sec)
            if (deltaKm in 0.002f..0.200f && rawSpeedKmh >= 2) {
                stepDistanceKm = deltaKm
            }
        }
        lastValidLocation = location

        // Push real-time GPS speed & Haversine distance increment to TelemetryRepository
        TelemetryRepository.updateGpsSpeedAndDistance(
            speedKmh = rawSpeedKmh,
            deltaDistanceKm = stepDistanceKm
        )

        if (isTripRecording && stepDistanceKm > 0f) {
            tripDistanceKm += stepDistanceKm
        }
    }

    @Deprecated("Deprecated in Android API, required for minSdk 22 compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    // =========================================================================
    // 2. 1-SECOND TELEMETRY & AUTO-TRIP RECORDING TICKER
    // =========================================================================

    private fun startOneSecondTelemetryTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                val state = TelemetryRepository.state.value
                val effectiveSpeedKmh = max(state.speedKmh, latestGpsSpeedKmh)
                val now = System.currentTimeMillis()

                // Auto-Trip start condition: speed > 5 km/h
                if (!isTripRecording && effectiveSpeedKmh > TRIP_START_SPEED_KMH) {
                    beginNewTripSession(now)
                }

                if (isTripRecording) {
                    if (effectiveSpeedKmh > TRIP_START_SPEED_KMH) {
                        lastMovingTimeMillis = now
                    }
                    tripMaxSpeedKmh = max(tripMaxSpeedKmh, effectiveSpeedKmh)
                    tripSpeedSamplesSum += effectiveSpeedKmh
                    tripSpeedSampleCount++

                    // If running in simulation or CANBUS-only mode without GPS movement,
                    // accumulate distance from effective speed (km/h -> km/s)
                    if (lastValidLocation == null || state.simModeActive) {
                        val deltaSimKm = effectiveSpeedKmh / 3600.0f
                        tripDistanceKm += deltaSimKm
                    }

                    // Stationary for 3 minutes -> Save completed TripEntity to SQLite
                    if (now - lastMovingTimeMillis >= STATIONARY_TIMEOUT_MS) {
                        finalizeAndSaveActiveTrip(reason = "STATIONARY_3_MIN")
                    }
                }
            }
        }
    }

    private fun beginNewTripSession(nowMillis: Long) {
        isTripRecording = true
        tripStartTimeMillis = nowMillis
        lastMovingTimeMillis = nowMillis
        tripDistanceKm = 0f
        tripMaxSpeedKmh = 0
        tripSpeedSamplesSum = 0L
        tripSpeedSampleCount = 0
        tripStartLat = currentLat
        tripStartLng = currentLng
        Log.i(TAG, "Auto-Trip started at ($tripStartLat, $tripStartLng)")
    }

    private suspend fun finalizeAndSaveActiveTrip(reason: String) {
        if (!isTripRecording) return
        isTripRecording = false

        val endTimeMillis = System.currentTimeMillis()
        val state = TelemetryRepository.state.value
        val recordedDistanceKm = max(tripDistanceKm, state.currentTripKm)

        if (recordedDistanceKm < MIN_TRIP_DISTANCE_KM) {
            Log.d(TAG, "Discarding micro-trip ($reason): distance=$recordedDistanceKm km")
            return
        }

        val avgSpeed = if (tripSpeedSampleCount > 0) {
            tripSpeedSamplesSum.toFloat() / tripSpeedSampleCount.toFloat()
        } else {
            val durationHours = ((endTimeMillis - tripStartTimeMillis).coerceAtLeast(1000L)) / 3_600_000f
            (recordedDistanceKm / durationHours).coerceIn(0f, 180f)
        }

        val avgKml = state.avgKmPerLiter.coerceAtLeast(1.0f)
        val fuelUsedLiters = recordedDistanceKm / avgKml
        val tripCostLkr = fuelUsedLiters * state.fuelPricePerLiterLkr

        val tripEntity = TripEntity(
            startTimeMillis = tripStartTimeMillis,
            endTimeMillis = endTimeMillis,
            distanceKm = (recordedDistanceKm * 100f).roundToInt() / 100f,
            avgSpeedKmh = (avgSpeed * 10f).roundToInt() / 10f,
            maxSpeedKmh = max(tripMaxSpeedKmh, state.speedKmh),
            fuelUsedLiters = (fuelUsedLiters * 100f).roundToInt() / 100f,
            tripCostLkr = (tripCostLkr * 100f).roundToInt() / 100f,
            startLat = tripStartLat,
            startLng = tripStartLng,
            endLat = currentLat,
            endLng = currentLng
        )

        try {
            database.telemetryDao().insertTrip(tripEntity)
            Log.i(TAG, "Saved TripEntity ($reason): ${tripEntity.distanceKm} km, LKR ${tripEntity.tripCostLkr}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert TripEntity into AquaDatabase", e)
        }
    }

    // =========================================================================
    // 3. DOOR OPENING SECURITY AUDIT LOGGER
    // =========================================================================

    private fun startDoorSecurityAuditObserver() {
        doorObserverJob?.cancel()
        doorObserverJob = serviceScope.launch {
            TelemetryRepository.state.collect { state ->
                checkAndLogDoorTransitions(state)
            }
        }
    }

    private suspend fun checkAndLogDoorTransitions(state: VehicleTelemetryState) {
        if (!prevDoorsInitialized) {
            prevFlOpen = state.frontLeftOpen
            prevFrOpen = state.frontRightOpen
            prevRlOpen = state.rearLeftOpen
            prevRrOpen = state.rearRightOpen
            prevTrunkOpen = state.trunkOpen
            prevHoodOpen = state.hoodOpen
            prevDoorsInitialized = true
            return
        }

        val now = System.currentTimeMillis()
        val speed = state.speedKmh

        if (state.frontLeftOpen != prevFlOpen) {
            prevFlOpen = state.frontLeftOpen
            recordDoorEvent("FL", state.frontLeftOpen, now, speed)
        }
        if (state.frontRightOpen != prevFrOpen) {
            prevFrOpen = state.frontRightOpen
            recordDoorEvent("FR", state.frontRightOpen, now, speed)
        }
        if (state.rearLeftOpen != prevRlOpen) {
            prevRlOpen = state.rearLeftOpen
            recordDoorEvent("RL", state.rearLeftOpen, now, speed)
        }
        if (state.rearRightOpen != prevRrOpen) {
            prevRrOpen = state.rearRightOpen
            recordDoorEvent("RR", state.rearRightOpen, now, speed)
        }
        if (state.trunkOpen != prevTrunkOpen) {
            prevTrunkOpen = state.trunkOpen
            recordDoorEvent("TRUNK", state.trunkOpen, now, speed)
        }
        if (state.hoodOpen != prevHoodOpen) {
            prevHoodOpen = state.hoodOpen
            recordDoorEvent("HOOD", state.hoodOpen, now, speed)
        }
    }

    private suspend fun recordDoorEvent(
        doorName: String,
        isOpen: Boolean,
        timestampMillis: Long,
        speedKmh: Int
    ) {
        val actionStr = if (isOpen) "OPENED" else "CLOSED"
        val auditEntity = DoorAuditEntity(
            timestampMillis = timestampMillis,
            doorName = doorName,
            action = actionStr,
            latitude = currentLat,
            longitude = currentLng,
            speedAtEventKmh = speedKmh
        )
        try {
            database.telemetryDao().insertDoorAudit(auditEntity)
            Log.i(TAG, "Door Security Log: $doorName $actionStr @ ($currentLat, $currentLng)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert DoorAuditEntity", e)
        }
    }

    // =========================================================================
    // 4. HAVERSINE FORMULA & FOREGROUND NOTIFICATION
    // =========================================================================

    private fun computeHaversineDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return (earthRadiusKm * c).toFloat()
    }

    private fun registerAccOffReceiver() {
        val filter = IntentFilter().apply {
            addAction(XyAutoCanbusReceiver.ACTION_XYAUTO_ACC_STATE)
            addAction(XyAutoCanbusReceiver.ACTION_XYAUTO_ACC_EVENT)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(accOffReceiver, filter)
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Toyota Aqua Telemetry & Security Tracker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks GPS trip telemetry and door opening security audit logs for WP CAT 7583"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)
        } else {
            null
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("WP CAT 7583 • Telemetry Active")
            .setContentText("Monitoring Toyota Aqua Hybrid HSD, GPS Trips & Door Security")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}

