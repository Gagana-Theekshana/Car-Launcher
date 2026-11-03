package com.aqua.cat7583.launcher.telemetry

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object TelemetryRepository {

    private const val TAG = "TelemetryRepository"

    // XY AUTO (F9212B / MCU 4.0 X2) Outbound CANBUS Broadcast Action & Extras
    const val ACTION_SEND_CANBUS_CMD = "com.xyauto.broadcast.SEND_CANBUS_CMD"
    const val EXTRA_CANBUS_CMD_BYTES = "canbus_cmd_bytes"
    const val EXTRA_CANBUS_DATA_TYPE = "canbus_data_type"

    // Standard Raise / Hiworld Toyota Aqua NHP10 Command IDs
    private const val CMD_TYPE_TOYOTA_AC: Int = 0xA8
    private const val CMD_TYPE_TOYOTA_BODY: Int = 0xA9
    private const val CMD_TYPE_OBD_HV_FAN: Int = 0x30

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null
    private var simJob: Job? = null
    private var simTick: Long = 0L

    private val _state = MutableStateFlow(VehicleTelemetryState())
    val state: StateFlow<VehicleTelemetryState> = _state.asStateFlow()
    val telemetryState: StateFlow<VehicleTelemetryState>
        get() = state

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (_state.value.simModeActive && simJob?.isActive != true) {
            startSimulationLoop()
        }
    }

    // =========================================================================
    // 1. INTERACTIVE AC & CLIMATE CONTROL MUTATORS (BI-DIRECTIONAL CANBUS)
    // =========================================================================

    fun setTargetTemp(delta: Float) {
        _state.update { current ->
            val stepped = ((current.targetTempC + delta) * 2f).roundToInt() / 2f
            val clamped = stepped.coerceIn(18.0f, 32.0f)
            current.copy(
                acPowerOn = true,
                targetTempC = clamped
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x03, param = (_state.value.targetTempC * 2f).roundToInt())
    }

    fun setTargetTempAbsolute(tempC: Float) {
        val stepped = (tempC * 2f).roundToInt() / 2f
        val clamped = stepped.coerceIn(18.0f, 32.0f)
        _state.update { current ->
            current.copy(
                acPowerOn = true,
                targetTempC = clamped
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x03, param = (clamped * 2f).roundToInt())
    }

    fun setFanSpeed(level: Int) {
        val clamped = level.coerceIn(0, 7)
        _state.update { current ->
            current.copy(
                acPowerOn = clamped > 0,
                fanSpeedLevel = clamped,
                autoClimateMode = if (clamped != current.fanSpeedLevel) false else current.autoClimateMode
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x02, param = clamped)
    }

    fun adjustFanSpeed(delta: Int) {
        setFanSpeed(_state.value.fanSpeedLevel + delta)
    }

    fun cycleAirflowMode() {
        val nextMode = _state.value.airflowMode.next()
        _state.update { current ->
            current.copy(
                acPowerOn = true,
                airflowMode = nextMode,
                autoClimateMode = false,
                frontDefrostMax = nextMode == AirflowMode.WINDSHIELD
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x04, param = nextMode.canbusCode)
    }

    fun setAirflowMode(mode: AirflowMode) {
        _state.update { current ->
            current.copy(
                acPowerOn = true,
                airflowMode = mode,
                autoClimateMode = false,
                frontDefrostMax = mode == AirflowMode.WINDSHIELD
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x04, param = mode.canbusCode)
    }

    fun toggleAcPower() {
        _state.update { current ->
            val nextPower = !current.acPowerOn
            current.copy(
                acPowerOn = nextPower,
                fanSpeedLevel = if (nextPower && current.fanSpeedLevel == 0) 2 else if (!nextPower) 0 else current.fanSpeedLevel
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x00, param = if (_state.value.acPowerOn) 0x01 else 0x00)
    }

    fun toggleAcCompressor() {
        _state.update { current ->
            val nextCompressor = !current.acCompressorOn
            current.copy(
                acPowerOn = if (nextCompressor) true else current.acPowerOn,
                acCompressorOn = nextCompressor
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x01, param = if (_state.value.acCompressorOn) 0x01 else 0x00)
    }

    fun toggleAutoClimate() {
        _state.update { current ->
            val nextAuto = !current.autoClimateMode
            current.copy(
                acPowerOn = if (nextAuto) true else current.acPowerOn,
                autoClimateMode = nextAuto,
                acCompressorOn = if (nextAuto) true else current.acCompressorOn,
                fanSpeedLevel = if (nextAuto && current.fanSpeedLevel == 0) 3 else current.fanSpeedLevel
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x05, param = if (_state.value.autoClimateMode) 0x01 else 0x00)
    }

    fun toggleRecirculation() {
        _state.update { current ->
            current.copy(recirculationInner = !current.recirculationInner)
        }
        dispatchToyotaAcCommand(subCmd = 0x06, param = if (_state.value.recirculationInner) 0x01 else 0x00)
    }

    fun toggleFrontDefrost() {
        _state.update { current ->
            val nextDefrost = !current.frontDefrostMax
            current.copy(
                acPowerOn = if (nextDefrost) true else current.acPowerOn,
                frontDefrostMax = nextDefrost,
                acCompressorOn = if (nextDefrost) true else current.acCompressorOn,
                airflowMode = if (nextDefrost) AirflowMode.WINDSHIELD else AirflowMode.FACE,
                fanSpeedLevel = if (nextDefrost) max(5, current.fanSpeedLevel) else current.fanSpeedLevel,
                recirculationInner = if (nextDefrost) false else current.recirculationInner
            )
        }
        dispatchToyotaAcCommand(subCmd = 0x07, param = if (_state.value.frontDefrostMax) 0x01 else 0x00)
    }

    fun toggleRearDefogger() {
        _state.update { current ->
            current.copy(rearWindowDefogger = !current.rearWindowDefogger)
        }
        dispatchToyotaAcCommand(subCmd = 0x08, param = if (_state.value.rearWindowDefogger) 0x01 else 0x00)
    }

    fun toggleEcoHeatCoolMode() {
        _state.update { current ->
            current.copy(ecoHeatCoolMode = !current.ecoHeatCoolMode)
        }
        dispatchToyotaAcCommand(subCmd = 0x09, param = if (_state.value.ecoHeatCoolMode) 0x01 else 0x00)
    }

    // =========================================================================
    // 2. INTERACTIVE DOOR, HARDWARE & POWERTRAIN MUTATORS
    // =========================================================================

    fun toggleDoor(doorIndex: Int) {
        _state.update { current ->
            when (doorIndex) {
                0 -> current.copy(frontLeftOpen = !current.frontLeftOpen)
                1 -> current.copy(frontRightOpen = !current.frontRightOpen)
                2 -> current.copy(rearLeftOpen = !current.rearLeftOpen)
                3 -> current.copy(rearRightOpen = !current.rearRightOpen)
                4 -> current.copy(trunkOpen = !current.trunkOpen)
                5 -> current.copy(hoodOpen = !current.hoodOpen)
                else -> current
            }
        }
    }

    fun toggleCentralLock() {
        _state.update { current ->
            current.copy(isCentralLocked = !current.isCentralLocked)
        }
        dispatchCanbusFrame(
            cmdType = CMD_TYPE_TOYOTA_BODY,
            payload = byteArrayOf(0x01, if (_state.value.isCentralLocked) 0x01 else 0x00)
        )
    }

    fun toggleHeadlights() {
        _state.update { current ->
            val nextHeadlights = !current.headlightsOn
            current.copy(
                headlightsOn = nextHeadlights,
                autoDimmerNightMode = nextHeadlights,
                highBeamOn = if (!nextHeadlights) false else current.highBeamOn
            )
        }
    }

    fun toggleHighBeam() {
        _state.update { current ->
            val nextHigh = !current.highBeamOn
            current.copy(
                headlightsOn = if (nextHigh) true else current.headlightsOn,
                highBeamOn = nextHigh
            )
        }
    }

    fun toggleFogLights() {
        _state.update { current ->
            current.copy(fogLightsOn = !current.fogLightsOn)
        }
    }

    fun toggleHandbrake() {
        _state.update { current ->
            current.copy(handbrakeEngaged = !current.handbrakeEngaged)
        }
    }

    fun toggleEvMode() {
        _state.update { current ->
            val canEngageEv = current.hvBatterySocPercent >= 40 && current.speedKmh <= 55
            val nextEv = if (!current.evModeActive && canEngageEv) true else false
            current.copy(
                evModeActive = nextEv,
                rpm = if (nextEv) 0 else current.rpm
            )
        }
        dispatchCanbusFrame(
            cmdType = CMD_TYPE_TOYOTA_BODY,
            payload = byteArrayOf(0x10, if (_state.value.evModeActive) 0x01 else 0x00)
        )
    }

    fun toggleEcoMode() {
        _state.update { current ->
            current.copy(ecoModeActive = !current.ecoModeActive)
        }
    }

    fun setGearPosition(gear: GearPosition) {
        _state.update { current ->
            val isReverse = gear == GearPosition.R
            val isParked = gear == GearPosition.P
            current.copy(
                gearPosition = gear,
                handbrakeEngaged = isParked,
                speedKmh = if (isParked) 0 else current.speedKmh,
                rpm = if (isParked) 0 else current.rpm,
                radarRearLeft = if (isReverse) 2 else 0,
                radarRearCenterLeft = if (isReverse) 3 else 0,
                radarRearCenterRight = if (isReverse) 3 else 0,
                radarRearRight = if (isReverse) 1 else 0
            )
        }
    }

    fun cycleGearPosition() {
        setGearPosition(_state.value.gearPosition.next())
    }

    fun setHvCoolingFanStep(step: Int) {
        val clamped = step.coerceIn(0, 6)
        _state.update { current ->
            current.copy(hvCoolingFanStep = clamped)
        }
        // Standard Toyota HSD HV Battery Cooling Fan Mode 0x30 Actuator Override
        dispatchCanbusFrame(
            cmdType = CMD_TYPE_OBD_HV_FAN,
            payload = byteArrayOf(0x07, 0xE2.toByte(), 0x30, 0x81.toByte(), 0x06, clamped.toByte())
        )
    }

    fun setFuelPricePerLiterLkr(priceLkr: Float) {
        if (priceLkr <= 0f) return
        _state.update { current ->
            val litersUsed = if (current.avgKmPerLiter > 0f) current.currentTripKm / current.avgKmPerLiter else 0f
            current.copy(
                fuelPricePerLiterLkr = priceLkr,
                currentTripCostLkr = litersUsed * priceLkr
            )
        }
    }

    fun resetCurrentTrip() {
        _state.update { current ->
            current.copy(
                currentTripKm = 0.0f,
                currentTripDurationSec = 0L,
                currentTripCostLkr = 0.0f
            )
        }
    }

    // =========================================================================
    // 3. INBOUND HARDWARE UPDATES (CANBUS / GPS / OBD2)
    // =========================================================================

    fun updateFromCanbusState(transform: (VehicleTelemetryState) -> VehicleTelemetryState) {
        _state.update { current ->
            transform(current.copy(canbusConnected = true))
        }
    }

    fun updateGpsSpeedAndDistance(speedKmh: Int, deltaDistanceKm: Float) {
        _state.update { current ->
            val newTripKm = current.currentTripKm + deltaDistanceKm.coerceAtLeast(0f)
            val litersUsed = if (current.avgKmPerLiter > 0f) newTripKm / current.avgKmPerLiter else 0f
            current.copy(
                gpsConnected = true,
                speedKmh = if (!current.canbusConnected && !current.simModeActive) speedKmh else current.speedKmh,
                currentTripKm = newTripKm,
                currentTripCostLkr = litersUsed * current.fuelPricePerLiterLkr
            )
        }
    }

    // =========================================================================
    // 4. SIMULATION LOOP (FOR HARDWARE-FREE UI & TELEMETRY TESTING)
    // =========================================================================

    fun setSimulationMode(enabled: Boolean) {
        _state.update { current ->
            current.copy(simModeActive = enabled)
        }
        if (enabled) {
            startSimulationLoop()
        } else {
            stopSimulationLoop()
        }
    }

    fun toggleSimulationMode() {
        setSimulationMode(!_state.value.simModeActive)
    }

    private fun startSimulationLoop() {
        if (simJob?.isActive == true) return
        simJob = repositoryScope.launch {
            while (isActive && _state.value.simModeActive) {
                delay(500L)
                simTick++
                _state.update { current ->
                    advanceSimulationStep(current, simTick)
                }
            }
        }
    }

    private fun stopSimulationLoop() {
        simJob?.cancel()
        simJob = null
    }

    private fun advanceSimulationStep(current: VehicleTelemetryState, tick: Long): VehicleTelemetryState {
        val phase = (tick % 240L).toDouble() / 240.0 * (2.0 * Math.PI)

        // Automatically put into D if in P during active simulation after a few ticks
        val activeGear = if (current.gearPosition == GearPosition.P && tick == 4L) {
            GearPosition.D
        } else {
            current.gearPosition
        }

        val isMovingGear = activeGear == GearPosition.D || activeGear == GearPosition.B || activeGear == GearPosition.R
        val simulatedSpeed = if (!isMovingGear) {
            0
        } else if (activeGear == GearPosition.R) {
            (8.0 + 4.0 * abs(sin(phase * 2.0))).roundToInt()
        } else {
            // Smooth urban-to-cruising Toyota Aqua driving profile (0 to 78 km/h)
            val rawWave = (sin(phase) + 0.85) * 42.0
            rawWave.roundToInt().coerceIn(0, 82)
        }

        // Toyota Aqua 1NZ-FXE Hybrid Synergy Drive EV / ICE logic
        val evActive = simulatedSpeed <= 44 && current.hvBatterySocPercent >= 42
        val simulatedRpm = when {
            !isMovingGear || simulatedSpeed == 0 -> 0
            evActive -> 0
            else -> (1150 + (simulatedSpeed * 28) + (sin(phase * 3.0) * 180).roundToInt()).coerceIn(1100, 3800)
        }

        // Instant fuel economy (99.9 km/L in EV mode, realistic 16 - 34 km/L on ICE)
        val instantKml = when {
            simulatedSpeed == 0 -> 0.0f
            evActive -> 99.9f
            else -> (26.5f - (simulatedRpm - 1200) * 0.0045f).coerceIn(14.2f, 36.0f)
        }

        // Smoothly converge cabin temperature toward target AC temperature
        val cabinStep = when {
            !current.acPowerOn -> (current.outsideTempC - current.cabinTempC) * 0.01f
            abs(current.targetTempC - current.cabinTempC) < 0.1f -> 0f
            current.targetTempC < current.cabinTempC -> -0.05f
            else -> 0.05f
        }
        val nextCabinTemp = ((current.cabinTempC + cabinStep) * 10f).roundToInt() / 10f

        // HV Battery SOC oscillation between 48% and 78%
        val socDelta = when {
            evActive && simulatedSpeed > 15 && tick % 14L == 0L -> -1
            !evActive && simulatedRpm > 1400 && tick % 10L == 0L -> 1
            else -> 0
        }
        val nextSoc = (current.hvBatterySocPercent + socDelta).coerceIn(45, 80)

        // HV Battery Temperature & Automatic Fan Step
        val nextHvTemp = (33.5f + 2.2f * sin(phase * 0.5).toFloat() * 10f).roundToInt() / 10f
        val autoFanStep = when {
            nextHvTemp >= 38.0f -> 5
            nextHvTemp >= 36.0f -> 4
            nextHvTemp >= 34.5f -> 3
            else -> current.hvCoolingFanStep.coerceAtLeast(1)
        }

        // Steering wheel angle & Reverse radar animation
        val steeringDeg = (sin(phase * 2.0) * 45.0).roundToInt()
        val isRev = activeGear == GearPosition.R
        val radarCycle = ((tick / 3L) % 5L).toInt()

        // Distance & Trip LKR Cost accumulation (500ms tick)
        val deltaKm = (simulatedSpeed / 3600.0f) * 0.5f
        val nextTripKm = current.currentTripKm + deltaKm
        val nextDurationSec = if (tick % 2L == 0L) current.currentTripDurationSec + 1L else current.currentTripDurationSec
        val nextAvgKml = ((current.avgKmPerLiter * 0.995f) + (min(instantKml, 38.0f) * 0.005f))
            .coerceIn(18.5f, 28.5f)
        val litersConsumed = if (nextAvgKml > 0f) nextTripKm / nextAvgKml else 0f
        val nextTripCostLkr = litersConsumed * current.fuelPricePerLiterLkr

        // Simulated XY AUTO / Raise Toyota CANBUS hex frame for live sniffer display
        val simulatedHex = String.format(
            "2E 28 05 %02X %02X %02X %02X %02X",
            simulatedSpeed and 0xFF,
            (simulatedRpm shr 8) and 0xFF,
            simulatedRpm and 0xFF,
            nextSoc and 0xFF,
            (current.targetTempC * 2).roundToInt() and 0xFF
        )

        return current.copy(
            gearPosition = activeGear,
            handbrakeEngaged = activeGear == GearPosition.P,
            speedKmh = simulatedSpeed,
            rpm = simulatedRpm,
            evModeActive = evActive,
            cabinTempC = nextCabinTemp,
            hvBatterySocPercent = nextSoc,
            hvBatteryTempC = nextHvTemp,
            hvCoolingFanStep = autoFanStep,
            coolantTempC = if (simulatedRpm > 0) min(88, current.coolantTempC + 1) else max(76, current.coolantTempC),
            inverterTempC = (39 + abs(cos(phase) * 6).roundToInt()),
            steeringWheelAngleDeg = steeringDeg,
            radarRearLeft = if (isRev) radarCycle else 0,
            radarRearCenterLeft = if (isRev) min(4, radarCycle + 1) else 0,
            radarRearCenterRight = if (isRev) min(4, radarCycle + 1) else 0,
            radarRearRight = if (isRev) max(0, radarCycle - 1) else 0,
            instantKmPerLiter = (instantKml * 10f).roundToInt() / 10f,
            avgKmPerLiter = (nextAvgKml * 10f).roundToInt() / 10f,
            currentTripKm = (nextTripKm * 100f).roundToInt() / 100f,
            currentTripDurationSec = nextDurationSec,
            currentTripCostLkr = (nextTripCostLkr * 100f).roundToInt() / 100f,
            lastRawCanbusHex = simulatedHex
        )
    }

    // =========================================================================
    // 5. XY AUTO MCU CANBUS FRAME BUILDER & DISPATCHER
    // =========================================================================

    private fun dispatchToyotaAcCommand(subCmd: Int, param: Int) {
        val current = _state.value
        val statusFlags = (if (current.acPowerOn) 0x80 else 0x00) or
            (if (current.acCompressorOn) 0x40 else 0x00) or
            (if (current.autoClimateMode) 0x20 else 0x00) or
            (if (current.recirculationInner) 0x10 else 0x00) or
            (if (current.frontDefrostMax) 0x08 else 0x00) or
            (if (current.rearWindowDefogger) 0x04 else 0x00) or
            (if (current.ecoHeatCoolMode) 0x02 else 0x00)

        val payload = byteArrayOf(
            subCmd.toByte(),
            param.toByte(),
            statusFlags.toByte(),
            current.fanSpeedLevel.toByte(),
            current.airflowMode.canbusCode.toByte(),
            (current.targetTempC * 2f).roundToInt().toByte()
        )
        dispatchCanbusFrame(cmdType = CMD_TYPE_TOYOTA_AC, payload = payload)
    }

    private fun dispatchCanbusFrame(cmdType: Int, payload: ByteArray) {
        // Standard Raise / Hiworld / XY AUTO Frame: [0x2E, cmdType, length, ...payload..., checksum]
        val frameLength = payload.size
        val frame = ByteArray(frameLength + 4)
        frame[0] = 0x2E.toByte()
        frame[1] = (cmdType and 0xFF).toByte()
        frame[2] = (frameLength and 0xFF).toByte()

        var sum = (cmdType and 0xFF) + (frameLength and 0xFF)
        for (i in payload.indices) {
            frame[3 + i] = payload[i]
            sum += (payload[i].toInt() and 0xFF)
        }
        // Checksum: (sum & 0xFF) XOR 0xFF
        frame[frame.lastIndex] = ((sum and 0xFF) xor 0xFF).toByte()

        val hexString = frame.joinToString(" ") { String.format("%02X", it) }
        _state.update { it.copy(lastRawCanbusHex = "TX: $hexString") }

        val ctx = appContext
        if (ctx != null) {
            try {
                val intent = Intent(ACTION_SEND_CANBUS_CMD).apply {
                    putExtra(EXTRA_CANBUS_DATA_TYPE, cmdType)
                    putExtra(EXTRA_CANBUS_CMD_BYTES, frame)
                }
                ctx.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dispatch XY AUTO CANBUS broadcast: $hexString", e)
            }
        }
    }
}

