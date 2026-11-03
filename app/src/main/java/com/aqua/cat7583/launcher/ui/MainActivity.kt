package com.aqua.cat7583.launcher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aqua.cat7583.launcher.data.AquaDatabase
import com.aqua.cat7583.launcher.data.FuelLogEntity
import com.aqua.cat7583.launcher.databinding.ActivityMainBinding
import com.aqua.cat7583.launcher.databinding.DialogAcControlsBinding
import com.aqua.cat7583.launcher.service.TripTrackerService
import com.aqua.cat7583.launcher.telemetry.AirflowMode
import com.aqua.cat7583.launcher.telemetry.GearPosition
import com.aqua.cat7583.launcher.telemetry.TelemetryRepository
import com.aqua.cat7583.launcher.telemetry.VehicleTelemetryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSIONS_CODE = 7583
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: AquaDatabase
    private var activeAcDialog: Dialog? = null
    private var activeAcDialogJob: Job? = null
    private var activeSnifferDialogJob: Job? = null
    private var isMediaPlaying: Boolean = false

    private val timestampFormat = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake while head unit ACC is powered
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureImmersiveFullScreen()

        // Initialize Singleton Telemetry Repository & Room SQLite Database
        TelemetryRepository.initialize(applicationContext)
        database = AquaDatabase.getInstance(applicationContext)

        // Request runtime permissions and start background GPS & Door Security Service
        requestHeadUnitPermissionsAndStartService()

        // Wire up all dashboard buttons, quick-climate controls, and 2x3 launcher tiles
        setupInteractiveListeners()

        // Observe real-time VehicleTelemetryState Flow
        observeTelemetryState()
    }

    override fun onResume() {
        super.onResume()
        configureImmersiveFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configureImmersiveFullScreen()
        }
    }

    /**
     * Disable Back-button exit so MainActivity acts as the permanent system car home launcher.
     */
    @Deprecated("Deprecated in Android API, required for targetSdk 28 compatibility")
    override fun onBackPressed() {
        // Intentional no-op for persistent car home launcher
    }

    // =========================================================================
    // 1. SYSTEM HOME IMMERSIVE MODE & FOREGROUND SERVICE STARTUP
    // =========================================================================

    private fun configureImmersiveFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun requestHeadUnitPermissionsAndStartService() {
        val neededPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val missing = neededPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS_CODE)
        } else {
            startTripTrackerForegroundService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS_CODE) {
            startTripTrackerForegroundService()
        }
    }

    private fun startTripTrackerForegroundService() {
        try {
            val serviceIntent = Intent(this, TripTrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Fallback if background restrictions apply
        }
    }

    // =========================================================================
    // 2. REAL-TIME STATEFLOW BINDING TO 1024x600 DASHBOARD UI
    // =========================================================================

    private fun observeTelemetryState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TelemetryRepository.state.collect { state ->
                    renderTelemetryToDashboard(state)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderTelemetryToDashboard(state: VehicleTelemetryState) {
        // 1. Update Custom Hardware-Accelerated Toyota Aqua Vector Visualizer
        binding.aquaVisualizerView.updateTelemetryState(state)

        // 2. Top Status Bar Badges
        binding.tvOutsideTemp.text = String.format(
            Locale.US,
            "OUT: %.1f°C  |  CABIN: %.1f°C",
            state.outsideTempC,
            state.cabinTempC
        )

        if (state.anyDoorOpen) {
            binding.tvDoorWarningPill.text = "DOOR OPEN (${state.openDoorCount})"
            binding.tvDoorWarningPill.setBackgroundColor(Color.parseColor("#3B1520"))
            binding.tvDoorWarningPill.setTextColor(Color.parseColor("#FF1744"))
        } else {
            binding.tvDoorWarningPill.text = if (state.isCentralLocked) "DOORS LOCKED" else "DOORS SECURE"
            binding.tvDoorWarningPill.setBackgroundColor(Color.parseColor("#1B3B2B"))
            binding.tvDoorWarningPill.setTextColor(Color.parseColor("#00E676"))
        }

        binding.badgeGpsStatus.text = if (state.gpsConnected) "GPS: OK" else "GPS: WAIT"
        binding.badgeGpsStatus.setTextColor(
            if (state.gpsConnected) Color.parseColor("#00E676") else Color.parseColor("#90A4AE")
        )

        binding.badgeCanbusStatus.text = if (state.canbusConnected) "CANBUS: LIVE" else "CANBUS: SIM"
        binding.badgeCanbusStatus.setTextColor(
            if (state.canbusConnected) Color.parseColor("#00E5FF") else Color.parseColor("#90A4AE")
        )

        binding.badgeObdStatus.text = if (state.obdConnected) "OBD2: LIVE" else "OBD2: STBY"
        binding.badgeObdStatus.setTextColor(
            if (state.obdConnected) Color.parseColor("#00E676") else Color.parseColor("#90A4AE")
        )

        binding.btnSimModeToggle.text = if (state.simModeActive) "[SIM MODE: ON]" else "[SIM MODE: OFF]"
        binding.btnSimModeToggle.setBackgroundColor(
            if (state.simModeActive) Color.parseColor("#00E5FF") else Color.parseColor("#263238")
        )
        binding.btnSimModeToggle.setTextColor(
            if (state.simModeActive) Color.parseColor("#0D1117") else Color.parseColor("#FFFFFF")
        )

        // 3. Left Instrument Cluster: Speedometer, Gear, EV/ECO, RPM, HV Battery SOC
        binding.tvSpeedKmh.text = state.speedKmh.toString()
        binding.tvActiveGearLarge.text = state.gearPosition.label
        binding.tvActiveGearLarge.setTextColor(
            when (state.gearPosition) {
                GearPosition.R -> Color.parseColor("#FF1744")
                GearPosition.D, GearPosition.B -> Color.parseColor("#00E676")
                else -> Color.parseColor("#00E5FF")
            }
        )

        binding.tvGearStrip.text = GearPosition.values().joinToString("  ") { gear ->
            if (gear == state.gearPosition) "[${gear.label}]" else gear.label
        }

        binding.badgeEvMode.setBackgroundColor(
            if (state.evModeActive) Color.parseColor("#00E676") else Color.parseColor("#263238")
        )
        binding.badgeEvMode.setTextColor(
            if (state.evModeActive) Color.parseColor("#0D1117") else Color.parseColor("#90A4AE")
        )

        binding.badgeEcoMode.setBackgroundColor(
            if (state.ecoModeActive) Color.parseColor("#1E3A2F") else Color.parseColor("#263238")
        )
        binding.badgeEcoMode.setTextColor(
            if (state.ecoModeActive) Color.parseColor("#00E676") else Color.parseColor("#90A4AE")
        )

        binding.progressRpm.progress = state.rpm.coerceIn(0, 5000)
        binding.tvRpmValue.text = if (state.rpm == 0) {
            "0 RPM (EV DRIVE)"
        } else {
            "${state.rpm} RPM (1NZ-FXE)"
        }

        binding.progressHvSoc.progress = state.hvBatterySocPercent.coerceIn(0, 100)
        binding.tvHvSocValue.text = String.format(
            Locale.US,
            "%d%% • %.1f°C (FAN %d)",
            state.hvBatterySocPercent,
            state.hvBatteryTempC,
            state.hvCoolingFanStep
        )

        binding.tvCoolantTemp.text = "${state.coolantTempC}°C"
        binding.tvCoolantTemp.setTextColor(
            if (state.coolantTempC >= 95) Color.parseColor("#FF1744") else Color.WHITE
        )
        binding.tvInverterTemp.text = "${state.inverterTempC}°C"
        binding.tvAux12vVolts.text = String.format(Locale.US, "%.1fV", state.aux12vBatteryVolts)
        binding.tvAux12vVolts.setTextColor(
            if (state.aux12vBatteryVolts < 12.0f) Color.parseColor("#FF1744") else Color.parseColor("#00E676")
        )

        // 4. Active Trip & Economy Card
        val durationMin = state.currentTripDurationSec / 60L
        binding.tvTripDistAndTime.text = String.format(
            Locale.US,
            "%.1f km • %dm",
            state.currentTripKm,
            durationMin
        )
        val instantStr = if (state.instantKmPerLiter >= 90f) "EV" else String.format(Locale.US, "%.1f", state.instantKmPerLiter)
        binding.tvInstantAndAvgKml.text = String.format(
            Locale.US,
            "%s / %.1f km/L",
            instantStr,
            state.avgKmPerLiter
        )
        binding.tvTripCostLkr.text = String.format(Locale.US, "COST: LKR %.2f", state.currentTripCostLkr)
        binding.tvRemainingRangeKm.text = "RANGE: ${state.remainingRangeKm} km (${state.fuelTankPercent}%)"

        // 5. Center Panel Quick Lighting & Quick Climate Bar
        binding.btnQuickHeadlights.setBackgroundColor(
            if (state.headlightsOn) Color.parseColor("#00E5FF") else Color.parseColor("#1F2937")
        )
        binding.btnQuickHeadlights.setTextColor(
            if (state.headlightsOn) Color.parseColor("#0D1117") else Color.parseColor("#00E5FF")
        )
        binding.btnQuickFogLights.setBackgroundColor(
            if (state.fogLightsOn) Color.parseColor("#00E676") else Color.parseColor("#1F2937")
        )
        binding.btnQuickFogLights.setTextColor(
            if (state.fogLightsOn) Color.parseColor("#0D1117") else Color.parseColor("#B0BEC5")
        )

        val acModeLabel = when {
            !state.acPowerOn -> "A/C OFF"
            state.autoClimateMode -> "A/C AUTO"
            state.acCompressorOn -> "A/C ON"
            else -> "VENT ONLY"
        }
        binding.tvQuickAcSummary.text = String.format(
            Locale.US,
            "%s • TEMP %.1f°C • FAN %d",
            acModeLabel,
            state.targetTempC,
            state.fanSpeedLevel
        )
        val recircLabel = if (state.recirculationInner) "RECIRC" else "FRESH AIR"
        binding.tvQuickAcSub.text =
            "MODE: ${state.airflowMode.displayName} • $recircLabel (TAP FOR FULL A/C)"

        // 6. Right 2x3 Grid Dynamic Sub-labels
        binding.tvTileClimateSub.text = String.format(
            Locale.US,
            "%.1f°C • Fan %d",
            state.targetTempC,
            state.fanSpeedLevel
        )
        binding.tvTileFuelSub.text = String.format(
            Locale.US,
            "%.0f LKR/L • %.1f km/L",
            state.fuelPricePerLiterLkr,
            state.avgKmPerLiter
        )
        binding.tvTileCanbusHex.text = state.lastRawCanbusHex
    }

    // =========================================================================
    // 3. INTERACTIVE LISTENERS FOR ALL BUTTONS, CLIMATE BAR & 2x3 TILES
    // =========================================================================

    private fun setupInteractiveListeners() {
        // Simulation Mode Toggle
        binding.btnSimModeToggle.setOnClickListener {
            TelemetryRepository.toggleSimulationMode()
        }

        // Door lock toggle when tapping top door status pill
        binding.tvDoorWarningPill.setOnClickListener {
            TelemetryRepository.toggleCentralLock()
        }

        // Left Cluster interactive toggles
        binding.badgeEvMode.setOnClickListener {
            TelemetryRepository.toggleEvMode()
        }
        binding.badgeEcoMode.setOnClickListener {
            TelemetryRepository.toggleEcoMode()
        }
        binding.tvGearStrip.setOnClickListener {
            TelemetryRepository.cycleGearPosition()
        }
        binding.tvActiveGearLarge.setOnClickListener {
            TelemetryRepository.cycleGearPosition()
        }
        binding.btnResetTripInline.setOnClickListener {
            TelemetryRepository.resetCurrentTrip()
            Toast.makeText(this, "Current trip meter reset", Toast.LENGTH_SHORT).show()
        }

        // Center Panel quick exterior lights
        binding.btnQuickHeadlights.setOnClickListener {
            TelemetryRepository.toggleHeadlights()
        }
        binding.btnQuickFogLights.setOnClickListener {
            TelemetryRepository.toggleFogLights()
        }

        // Center Panel Media Controls (Dispatches hardware media key events to active player/BT)
        binding.btnMediaPrev.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        binding.btnMediaPlayPause.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            isMediaPlaying = !isMediaPlaying
            binding.btnMediaPlayPause.text = if (isMediaPlaying) "PAUSE" else "PLAY"
        }
        binding.btnMediaNext.setOnClickListener {
            dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        // Center Panel Quick Climate Bar [-], [+], and Full AC Dialog Trigger
        binding.btnQuickTempMinus.setOnClickListener {
            TelemetryRepository.setTargetTemp(-0.5f)
        }
        binding.btnQuickTempPlus.setOnClickListener {
            TelemetryRepository.setTargetTemp(+0.5f)
        }
        binding.quickClimateBar.setOnClickListener {
            showFullAcClimateDialog()
        }
        binding.layoutOpenAcDrawerTrigger.setOnClickListener {
            showFullAcClimateDialog()
        }

        // Right 2x3 Driver Quick-Action Tiles
        binding.tileNavigation.setOnClickListener {
            launchNavigationApp()
        }
        binding.tileClimateSuite.setOnClickListener {
            showFullAcClimateDialog()
        }
        binding.tileFuelCostLog.setOnClickListener {
            showFuelAndMaintenanceDialog()
        }
        binding.tileTripDoorAudit.setOnClickListener {
            showTripAndDoorAuditDialog()
        }
        binding.tileCanbusSniffer.setOnClickListener {
            showCanbusSnifferDialog()
        }
        binding.tileAllAppsDrawer.setOnClickListener {
            showAllAppsDrawerDialog()
        }
    }

    private fun dispatchMediaKeyEvent(keyCode: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)
        } catch (_: Exception) {
        }
    }

    private fun launchNavigationApp() {
        val navPackages = listOf(
            "com.google.android.apps.maps",
            "com.waze",
            "com.here.app.maps"
        )
        for (pkg in navPackages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
        }
        try {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:6.9271,79.8612?z=14"))
            startActivity(geoIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "No Navigation app installed on head unit", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 4. DIALOG 1: DIGITAL AC & CLIMATE AMPLIFIER POP-UP PANEL
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun showFullAcClimateDialog() {
        activeAcDialog?.dismiss()
        activeAcDialogJob?.cancel()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogBinding = DialogAcControlsBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.78f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Close button
        dialogBinding.btnAcDialogClose.setOnClickListener {
            dialog.dismiss()
        }

        // Temperature [-], [+], and AUTO Master
        dialogBinding.btnAcTempDown.setOnClickListener {
            TelemetryRepository.setTargetTemp(-0.5f)
        }
        dialogBinding.btnAcTempUp.setOnClickListener {
            TelemetryRepository.setTargetTemp(+0.5f)
        }
        dialogBinding.btnAcAutoMaster.setOnClickListener {
            TelemetryRepository.toggleAutoClimate()
        }

        // Fan Speed Buttons (OFF, 1..7, AUTO)
        val fanButtons = listOf(
            dialogBinding.btnFanOff to 0,
            dialogBinding.btnFan1 to 1,
            dialogBinding.btnFan2 to 2,
            dialogBinding.btnFan3 to 3,
            dialogBinding.btnFan4 to 4,
            dialogBinding.btnFan5 to 5,
            dialogBinding.btnFan6 to 6,
            dialogBinding.btnFan7 to 7
        )
        for ((btn, speed) in fanButtons) {
            btn.setOnClickListener {
                TelemetryRepository.setFanSpeed(speed)
            }
        }
        dialogBinding.btnFanAuto.setOnClickListener {
            TelemetryRepository.toggleAutoClimate()
        }

        // Vent Airflow Mode Buttons
        val modeButtons = listOf(
            dialogBinding.btnModeFace to AirflowMode.FACE,
            dialogBinding.btnModeFaceFeet to AirflowMode.FACE_FEET,
            dialogBinding.btnModeFeet to AirflowMode.FEET,
            dialogBinding.btnModeFeetDefrost to AirflowMode.FEET_DEFROST,
            dialogBinding.btnModeDefrost to AirflowMode.WINDSHIELD
        )
        for ((btn, mode) in modeButtons) {
            btn.setOnClickListener {
                TelemetryRepository.setAirflowMode(mode)
            }
        }

        // Hardware Relay Switches
        dialogBinding.switchAcCompressor.setOnClickListener {
            TelemetryRepository.toggleAcCompressor()
        }
        dialogBinding.switchEcoHeatCool.setOnClickListener {
            TelemetryRepository.toggleEcoHeatCoolMode()
        }
        dialogBinding.switchRecirculation.setOnClickListener {
            TelemetryRepository.toggleRecirculation()
        }
        dialogBinding.switchFrontDefrostMax.setOnClickListener {
            TelemetryRepository.toggleFrontDefrost()
        }
        dialogBinding.switchRearWindowDefogger.setOnClickListener {
            TelemetryRepository.toggleRearDefogger()
        }

        // Observe state changes live inside the open dialog
        activeAcDialogJob = lifecycleScope.launch {
            TelemetryRepository.state.collect { state ->
                dialogBinding.tvAcTargetTempLarge.text =
                    String.format(Locale.US, "%.1f°C", state.targetTempC)
                dialogBinding.tvDialogAcTelemetrySub.text = String.format(
                    Locale.US,
                    "CABIN: %.1f°C  •  OUTSIDE: %.1f°C  •  LAST CANBUS: %s",
                    state.cabinTempC,
                    state.outsideTempC,
                    state.lastRawCanbusHex
                )

                // Highlight active fan speed button
                for ((btn, speed) in fanButtons) {
                    val isSelected = state.fanSpeedLevel == speed && (speed > 0 || !state.acPowerOn)
                    btn.setBackgroundColor(
                        if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#141C24")
                    )
                    btn.setTextColor(
                        if (isSelected) Color.parseColor("#0D1117") else Color.WHITE
                    )
                }

                // Highlight active airflow mode button
                for ((btn, mode) in modeButtons) {
                    val isSelected = state.airflowMode == mode
                    btn.setBackgroundColor(
                        if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#141C24")
                    )
                    btn.setTextColor(
                        if (isSelected) Color.parseColor("#0D1117") else Color.WHITE
                    )
                }

                dialogBinding.switchAcCompressor.isChecked = state.acCompressorOn
                dialogBinding.switchEcoHeatCool.isChecked = state.ecoHeatCoolMode
                dialogBinding.switchRecirculation.isChecked = state.recirculationInner
                dialogBinding.switchFrontDefrostMax.isChecked = state.frontDefrostMax
                dialogBinding.switchRearWindowDefogger.isChecked = state.rearWindowDefogger
            }
        }

        dialog.setOnDismissListener {
            activeAcDialogJob?.cancel()
            configureImmersiveFullScreen()
        }

        activeAcDialog = dialog
        dialog.show()
    }

    // =========================================================================
    // 5. DIALOG 2: FUEL & LKR COST LOG + TOYOTA AQUA MAINTENANCE REMINDERS
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun showFuelAndMaintenanceDialog() {
        lifecycleScope.launch {
            val dao = database.telemetryDao()
            val latestFuel = withContext(Dispatchers.IO) { dao.getLatestFuelLog() }
            val maintenanceList = withContext(Dispatchers.IO) { dao.getMaintenanceItems() }
            val state = TelemetryRepository.state.value

            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#0D1117"))
                setPadding(28, 24, 28, 24)
            }

            val summaryView = TextView(this@MainActivity).apply {
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                val latestFuelText = if (latestFuel != null) {
                    "Last Refill: ${latestFuel.litersAdded}L @ LKR ${latestFuel.pricePerLiterLkr}/L (Total: LKR ${latestFuel.totalCostLkr})"
                } else {
                    "No manual refill logs yet. Default Petrol 92/95 price: LKR ${state.fuelPricePerLiterLkr}/L"
                }
                text = "ACTIVE TRIP COST: LKR ${String.format(Locale.US, "%.2f", state.currentTripCostLkr)} " +
                    "(${state.currentTripKm} km @ ${state.avgKmPerLiter} km/L)\n$latestFuelText"
            }
            container.addView(summaryView)

            // Quick Input Row to log fuel refill or update LKR/Liter price
            val inputRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 14, 0, 14)
            }
            val etPriceLkr = EditText(this@MainActivity).apply {
                hint = "LKR/Liter (e.g. 340)"
                setText(state.fuelPricePerLiterLkr.toInt().toString())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val etLitersAdded = EditText(this@MainActivity).apply {
                hint = "Liters Added (e.g. 25)"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnSaveRefill = Button(this@MainActivity).apply {
                text = "SAVE REFILL"
                setBackgroundColor(Color.parseColor("#00E676"))
                setTextColor(Color.parseColor("#0D1117"))
            }
            inputRow.addView(etPriceLkr)
            inputRow.addView(etLitersAdded)
            inputRow.addView(btnSaveRefill)
            container.addView(inputRow)

            // Maintenance Schedule List (0W-20 Oil, HV Fan Filter, ATF WS, Inverter Coolant)
            val maintHeader = TextView(this@MainActivity).apply {
                text = "TOYOTA AQUA (NHP10) SERVICE SCHEDULE:"
                setTextColor(Color.parseColor("#FFAB00"))
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 10, 0, 6)
            }
            container.addView(maintHeader)

            for (item in maintenanceList) {
                val rowText = TextView(this@MainActivity).apply {
                    text = "• ${item.serviceItem}: Last @ ${item.lastServiceOdoKm} km | Next Due @ ${item.nextDueOdoKm} km (Interval ${item.intervalKm} km)"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(0, 4, 0, 4)
                }
                container.addView(rowText)
            }

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("WP CAT 7583 • Fuel Economy (LKR) & Hybrid Maintenance")
                .setView(container)
                .setPositiveButton("CLOSE") { d, _ -> d.dismiss() }
                .create()

            btnSaveRefill.setOnClickListener {
                val price = etPriceLkr.text.toString().toFloatOrNull() ?: state.fuelPricePerLiterLkr
                val liters = etLitersAdded.text.toString().toFloatOrNull() ?: 0f
                TelemetryRepository.setFuelPricePerLiterLkr(price)
                if (liters > 0f) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        dao.insertFuelLog(
                            FuelLogEntity(
                                timestampMillis = System.currentTimeMillis(),
                                odometerKm = 95420,
                                litersAdded = liters,
                                pricePerLiterLkr = price,
                                totalCostLkr = liters * price,
                                isFullTank = true,
                                calculatedKmPerLiter = state.avgKmPerLiter
                            )
                        )
                    }
                }
                Toast.makeText(
                    this@MainActivity,
                    "Updated Fuel Price: LKR $price/L",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }

            dialog.setOnDismissListener { configureImmersiveFullScreen() }
            dialog.show()
        }
    }

    // =========================================================================
    // 6. DIALOG 3: TRIP & DOOR OPENING SECURITY AUDIT LOGS (SQLITE)
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun showTripAndDoorAuditDialog() {
        lifecycleScope.launch {
            val dao = database.telemetryDao()
            val trips = withContext(Dispatchers.IO) { dao.getRecentTrips(limit = 20) }
            val doorLogs = withContext(Dispatchers.IO) { dao.getDoorAuditLogs(limit = 40) }

            val scrollView = ScrollView(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#0D1117"))
            }
            val contentLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 20, 28, 20)
            }
            scrollView.addView(contentLayout)

            val tripsHeader = TextView(this@MainActivity).apply {
                text = "RECENT RECORDED GPS TRIPS (${trips.size})"
                setTextColor(Color.parseColor("#00E676"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }
            contentLayout.addView(tripsHeader)

            if (trips.isEmpty()) {
                val emptyTrips = TextView(this@MainActivity).apply {
                    text = "No finalized trips yet. Trips auto-record when speed > 5 km/h."
                    setTextColor(Color.parseColor("#90A4AE"))
                    textSize = 12f
                    setPadding(0, 4, 0, 12)
                }
                contentLayout.addView(emptyTrips)
            } else {
                for (trip in trips) {
                    val startStr = timestampFormat.format(Date(trip.startTimeMillis))
                    val tripRow = TextView(this@MainActivity).apply {
                        text = String.format(
                            Locale.US,
                            "[%s] %.2f km | Avg %.1f km/h | Max %d km/h | Cost: LKR %.2f",
                            startStr,
                            trip.distanceKm,
                            trip.avgSpeedKmh,
                            trip.maxSpeedKmh,
                            trip.tripCostLkr
                        )
                        setTextColor(Color.WHITE)
                        typeface = Typeface.MONOSPACE
                        textSize = 11.5f
                        setPadding(0, 4, 0, 4)
                    }
                    contentLayout.addView(tripRow)
                }
            }

            val doorHeader = TextView(this@MainActivity).apply {
                text = "\nDOOR / HOOD / TRUNK SECURITY AUDIT LOG (${doorLogs.size})"
                setTextColor(Color.parseColor("#FF1744"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }
            contentLayout.addView(doorHeader)

            if (doorLogs.isEmpty()) {
                val emptyDoors = TextView(this@MainActivity).apply {
                    text = "No door opening events logged yet. Tap any door on the car visualizer to test."
                    setTextColor(Color.parseColor("#90A4AE"))
                    textSize = 12f
                    setPadding(0, 4, 0, 8)
                }
                contentLayout.addView(emptyDoors)
            } else {
                for (event in doorLogs) {
                    val timeStr = timestampFormat.format(Date(event.timestampMillis))
                    val doorRow = TextView(this@MainActivity).apply {
                        text = String.format(
                            Locale.US,
                            "[%s] %-5s %-6s | GPS: %.4f, %.4f | %d km/h",
                            timeStr,
                            event.doorName,
                            event.action,
                            event.latitude,
                            event.longitude,
                            event.speedAtEventKmh
                        )
                        setTextColor(
                            if (event.action == "OPENED") Color.parseColor("#FF8A80") else Color.parseColor("#B0BEC5")
                        )
                        typeface = Typeface.MONOSPACE
                        textSize = 11.5f
                        setPadding(0, 3, 0, 3)
                    }
                    contentLayout.addView(doorRow)
                }
            }

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("WP CAT 7583 • Trip History & Door Security Audit")
                .setView(scrollView)
                .setNeutralButton("CLEAR LOGS") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        dao.clearDoorAuditLogs()
                        dao.clearAllTrips()
                    }
                }
                .setPositiveButton("CLOSE") { d, _ -> d.dismiss() }
                .create()

            dialog.setOnDismissListener { configureImmersiveFullScreen() }
            dialog.show()
        }
    }

    // =========================================================================
    // 7. DIALOG 4: XY AUTO MCU 4.0 X2 CANBUS HEX SNIFFER & HV FAN OVERRIDE
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun showCanbusSnifferDialog() {
        activeSnifferDialogJob?.cancel()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(28, 22, 28, 22)
        }

        val tvLiveHex = TextView(this).apply {
            setTextColor(Color.parseColor("#00E676"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
        }
        container.addView(tvLiveHex)

        val fanLabel = TextView(this).apply {
            text = "\nHV BATTERY COOLING FAN ACTUATOR OVERRIDE (MODE 0x30):"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        container.addView(fanLabel)

        val fanRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        for (step in 0..6) {
            val btn = Button(this).apply {
                text = if (step == 0) "AUTO" else "S$step"
                setBackgroundColor(Color.parseColor("#1C2834"))
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, 72, 1f).apply {
                    marginEnd = 6
                }
                setOnClickListener {
                    TelemetryRepository.setHvCoolingFanStep(step)
                }
            }
            fanRow.addView(btn)
        }
        container.addView(fanRow)

        activeSnifferDialogJob = lifecycleScope.launch {
            TelemetryRepository.state.collect { state ->
                tvLiveHex.text =
                    "BOARD: XY AUTO F9212B (MCU 4.0 X2) | PROTO: Raise/Hiworld Toyota\n" +
                    "LAST FRAME: ${state.lastRawCanbusHex}\n" +
                    "STEERING ANGLE: ${state.steeringWheelAngleDeg}° | HV SOC: ${state.hvBatterySocPercent}% | " +
                    "HV TEMP: ${state.hvBatteryTempC}°C (FAN STEP ${state.hvCoolingFanStep})\n" +
                    "REAR RADAR ZONES: [L:${state.radarRearLeft} CL:${state.radarRearCenterLeft} CR:${state.radarRearCenterRight} R:${state.radarRearRight}]"
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("CANBUS Hex Sniffer & Hybrid Diagnostics")
            .setView(container)
            .setPositiveButton("CLOSE") { d, _ -> d.dismiss() }
            .create()

        dialog.setOnDismissListener {
            activeSnifferDialogJob?.cancel()
            configureImmersiveFullScreen()
        }
        dialog.show()
    }

    // =========================================================================
    // 8. DIALOG 5: ALL INSTALLED APPS DRAWER
    // =========================================================================

    private fun showAllAppsDrawerDialog() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                packageManager.queryIntentActivities(mainIntent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .sortedBy { it.loadLabel(packageManager).toString().lowercase(Locale.ROOT) }
            }

            val scrollView = ScrollView(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#0D1117"))
            }
            val listLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 16, 24, 16)
            }
            scrollView.addView(listLayout)

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Installed Head Unit Applications (${apps.size})")
                .setView(scrollView)
                .setPositiveButton("CLOSE") { d, _ -> d.dismiss() }
                .create()

            for (resolveInfo in apps) {
                val label = resolveInfo.loadLabel(packageManager).toString()
                val pkgName = resolveInfo.activityInfo.packageName

                val appBtn = Button(this@MainActivity).apply {
                    text = "$label  ($pkgName)"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#141C24"))
                    setTextColor(Color.WHITE)
                    isAllCaps = false
                    setPadding(24, 14, 24, 14)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 8
                    }
                    setOnClickListener {
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkgName)
                        if (launchIntent != null) {
                            dialog.dismiss()
                            startActivity(launchIntent)
                        }
                    }
                }
                listLayout.addView(appBtn)
            }

            dialog.setOnDismissListener { configureImmersiveFullScreen() }
            dialog.show()
        }
    }
}

