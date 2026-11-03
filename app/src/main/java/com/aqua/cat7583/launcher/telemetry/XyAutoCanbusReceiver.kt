package com.aqua.cat7583.launcher.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.math.roundToInt

/**
 * Hardware BroadcastReceiver for the XY AUTO (F9212B / MCU 4.0 X2) Head Unit
 * with Raise / Hiworld Toyota Aqua (NHP10) CANBUS Decoder Box.
 */
class XyAutoCanbusReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "XyAutoCanbusReceiver"

        // Primary XY AUTO & Raise/Hiworld Toyota CANBUS Broadcast Actions
        const val ACTION_XYAUTO_CANBUS_RECEIVE = "com.xyauto.broadcast.CANBUS_RECEIVE"
        const val ACTION_CANBUS_DOOR_STATUS = "com.canbus.action.DOOR_STATUS"
        const val ACTION_CANBUS_AC_STATUS = "com.canbus.action.AC_STATUS"
        const val ACTION_XYAUTO_ACC_STATE = "com.xyauto.broadcast.ACC_STATE"
        const val ACTION_XYAUTO_ACC_EVENT = "com.xyauto.broadcast.ACC_EVENT"
        const val ACTION_XYAUTO_ILL_EVENT = "com.xyauto.broadcast.ILL_EVENT"
        const val ACTION_XYAUTO_DOOR_EVENT = "com.xyauto.broadcast.DOOR_EVENT"

        // Raise / Hiworld Toyota Protocol Data Type IDs (after 0x2E header)
        private const val DATA_TYPE_RAISE_DOOR_INFO = 0x24
        private const val DATA_TYPE_RAISE_AC_INFO = 0x28
        private const val DATA_TYPE_RAISE_REAR_RADAR = 0x1E
        private const val DATA_TYPE_RAISE_STEERING_ANGLE = 0x29
        private const val DATA_TYPE_RAISE_HYBRID_INFO = 0x41
        private const val DATA_TYPE_RAISE_TPMS_INFO = 0x48
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        TelemetryRepository.initialize(context)

        val action = intent.action ?: return
        val rawBytes = extractCanbusBytes(intent.extras)
        val rawHex = rawBytes?.joinToString(" ") { String.format("%02X", it) }

        try {
            when (action) {
                ACTION_XYAUTO_CANBUS_RECEIVE -> {
                    if (rawBytes != null && rawBytes.isNotEmpty()) {
                        parseRawCanbusFrame(rawBytes, rawHex ?: "")
                    } else {
                        parseFallbackExtras(intent, action)
                    }
                }

                ACTION_CANBUS_DOOR_STATUS,
                ACTION_XYAUTO_DOOR_EVENT -> {
                    parseDoorStatusIntent(intent, rawBytes, rawHex)
                }

                ACTION_CANBUS_AC_STATUS -> {
                    parseAcStatusIntent(intent, rawBytes, rawHex)
                }

                ACTION_XYAUTO_ACC_STATE,
                ACTION_XYAUTO_ACC_EVENT -> {
                    parseAccStateIntent(intent, rawHex)
                }

                ACTION_XYAUTO_ILL_EVENT -> {
                    val illOn = intent.getBooleanExtra("ill_state", false) ||
                        intent.getIntExtra("state", 0) == 1
                    TelemetryRepository.updateFromCanbusState { state ->
                        state.copy(
                            headlightsOn = illOn,
                            autoDimmerNightMode = illOn,
                            lastRawCanbusHex = rawHex ?: "ILL_EVENT: ${if (illOn) "ON" else "OFF"}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing XY AUTO CANBUS broadcast ($action)", e)
        }
    }

    private fun extractCanbusBytes(extras: Bundle?): ByteArray? {
        if (extras == null) return null
        val candidateKeys = arrayOf(
            "canbus_data",
            "data",
            "bytes",
            "cmd_bytes",
            "can_data",
            "raw_data"
        )
        for (key in candidateKeys) {
            val byteArr = extras.getByteArray(key)
            if (byteArr != null && byteArr.isNotEmpty()) return byteArr

            val intArr = extras.getIntArray(key)
            if (intArr != null && intArr.isNotEmpty()) {
                return ByteArray(intArr.size) { idx -> (intArr[idx] and 0xFF).toByte() }
            }
        }
        return null
    }

    /**
     * Parses standard Raise / Hiworld / XY AUTO Toyota frames:
     * Format: [0x2E, DataType, Length, Data0, Data1, ..., Checksum]
     * Or headerless frames: [DataType, Length, Data0, ...]
     */
    private fun parseRawCanbusFrame(bytes: ByteArray, rawHex: String) {
        val hasHeader = (bytes[0].toInt() and 0xFF) == 0x2E && bytes.size >= 4
        val dataType = if (hasHeader) {
            bytes[1].toInt() and 0xFF
        } else {
            bytes[0].toInt() and 0xFF
        }
        val payloadOffset = if (hasHeader) 3 else 2

        when (dataType) {
            DATA_TYPE_RAISE_DOOR_INFO -> {
                if (bytes.size > payloadOffset) {
                    val doorMask = bytes[payloadOffset].toInt() and 0xFF
                    val statusByte2 = if (bytes.size > payloadOffset + 1) {
                        bytes[payloadOffset + 1].toInt() and 0xFF
                    } else {
                        0
                    }
                    applyDoorBitmask(doorMask, statusByte2, rawHex)
                }
            }

            DATA_TYPE_RAISE_AC_INFO -> {
                if (bytes.size >= payloadOffset + 4) {
                    val flags0 = bytes[payloadOffset].toInt() and 0xFF
                    val fanAndWind = bytes[payloadOffset + 1].toInt() and 0xFF
                    val rawTemp = bytes[payloadOffset + 2].toInt() and 0xFF
                    val flags3 = bytes[payloadOffset + 3].toInt() and 0xFF
                    val rawOutsideTemp = if (bytes.size > payloadOffset + 4) {
                        bytes[payloadOffset + 4].toInt() and 0xFF
                    } else {
                        -1
                    }
                    applyToyotaAcBytes(flags0, fanAndWind, rawTemp, flags3, rawOutsideTemp, rawHex)
                }
            }

            DATA_TYPE_RAISE_REAR_RADAR -> {
                if (bytes.size >= payloadOffset + 4) {
                    val rl = (bytes[payloadOffset].toInt() and 0xFF).coerceIn(0, 4)
                    val rcl = (bytes[payloadOffset + 1].toInt() and 0xFF).coerceIn(0, 4)
                    val rcr = (bytes[payloadOffset + 2].toInt() and 0xFF).coerceIn(0, 4)
                    val rr = (bytes[payloadOffset + 3].toInt() and 0xFF).coerceIn(0, 4)
                    TelemetryRepository.updateFromCanbusState { state ->
                        state.copy(
                            simModeActive = false,
                            gearPosition = if (rl > 0 || rcl > 0 || rcr > 0 || rr > 0) GearPosition.R else state.gearPosition,
                            radarRearLeft = rl,
                            radarRearCenterLeft = rcl,
                            radarRearCenterRight = rcr,
                            radarRearRight = rr,
                            lastRawCanbusHex = rawHex
                        )
                    }
                }
            }

            DATA_TYPE_RAISE_STEERING_ANGLE -> {
                if (bytes.size >= payloadOffset + 2) {
                    val low = bytes[payloadOffset].toInt() and 0xFF
                    val high = bytes[payloadOffset + 1].toInt()
                    val signedRaw = (high shl 8) or low
                    val angleDeg = (signedRaw * 0.1f).roundToInt().coerceIn(-540, 540)
                    TelemetryRepository.updateFromCanbusState { state ->
                        state.copy(
                            simModeActive = false,
                            steeringWheelAngleDeg = angleDeg,
                            lastRawCanbusHex = rawHex
                        )
                    }
                }
            }

            DATA_TYPE_RAISE_HYBRID_INFO -> {
                if (bytes.size >= payloadOffset + 4) {
                    val speed = bytes[payloadOffset].toInt() and 0xFF
                    val rpmHigh = bytes[payloadOffset + 1].toInt() and 0xFF
                    val rpmLow = bytes[payloadOffset + 2].toInt() and 0xFF
                    val rpm = ((rpmHigh shl 8) or rpmLow).coerceIn(0, 6500)
                    val soc = (bytes[payloadOffset + 3].toInt() and 0xFF).coerceIn(0, 100)
                    val gearCode = if (bytes.size > payloadOffset + 4) {
                        bytes[payloadOffset + 4].toInt() and 0x0F
                    } else {
                        -1
                    }
                    TelemetryRepository.updateFromCanbusState { state ->
                        state.copy(
                            simModeActive = false,
                            speedKmh = speed,
                            rpm = rpm,
                            evModeActive = rpm == 0 && speed > 0,
                            hvBatterySocPercent = soc,
                            gearPosition = if (gearCode >= 0) GearPosition.fromCanbusCode(gearCode) else state.gearPosition,
                            lastRawCanbusHex = rawHex
                        )
                    }
                }
            }

            DATA_TYPE_RAISE_TPMS_INFO -> {
                if (bytes.size >= payloadOffset + 4) {
                    val flPsi = (bytes[payloadOffset].toInt() and 0xFF) * 0.2f
                    val frPsi = (bytes[payloadOffset + 1].toInt() and 0xFF) * 0.2f
                    val rlPsi = (bytes[payloadOffset + 2].toInt() and 0xFF) * 0.2f
                    val rrPsi = (bytes[payloadOffset + 3].toInt() and 0xFF) * 0.2f
                    TelemetryRepository.updateFromCanbusState { state ->
                        state.copy(
                            simModeActive = false,
                            tireFlPsi = if (flPsi in 10f..60f) flPsi else state.tireFlPsi,
                            tireFrPsi = if (frPsi in 10f..60f) frPsi else state.tireFrPsi,
                            tireRlPsi = if (rlPsi in 10f..60f) rlPsi else state.tireRlPsi,
                            tireRrPsi = if (rrPsi in 10f..60f) rrPsi else state.tireRrPsi,
                            lastRawCanbusHex = rawHex
                        )
                    }
                }
            }

            else -> {
                // Unknown or vendor-specific frame: still record raw hex for the CANBUS Sniffer UI
                TelemetryRepository.updateFromCanbusState { state ->
                    state.copy(lastRawCanbusHex = rawHex)
                }
            }
        }
    }

    /**
     * Standard Raise / Hiworld Toyota Door Bitmask:
     * Bit 7 (0x80): Front Left
     * Bit 6 (0x40): Front Right
     * Bit 5 (0x20): Rear Left
     * Bit 4 (0x10): Rear Right
     * Bit 3 (0x08): Trunk / Hatch
     * Bit 2 (0x04): Hood / Bonnet
     * Bit 1 (0x02): Handbrake Engaged
     * Bit 0 (0x01): Seatbelt Unbuckled
     */
    private fun applyDoorBitmask(doorMask: Int, statusByte2: Int, rawHex: String) {
        val flOpen = (doorMask and 0x80) != 0
        val frOpen = (doorMask and 0x40) != 0
        val rlOpen = (doorMask and 0x20) != 0
        val rrOpen = (doorMask and 0x10) != 0
        val trunkOpen = (doorMask and 0x08) != 0
        val hoodOpen = (doorMask and 0x04) != 0
        val handbrake = (doorMask and 0x02) != 0
        val driverBeltBuckled = (doorMask and 0x01) == 0

        val headlights = (statusByte2 and 0x01) != 0
        val highBeam = (statusByte2 and 0x02) != 0
        val fogLights = (statusByte2 and 0x04) != 0
        val reverseGear = (statusByte2 and 0x08) != 0

        TelemetryRepository.updateFromCanbusState { state ->
            state.copy(
                simModeActive = false,
                frontLeftOpen = flOpen,
                frontRightOpen = frOpen,
                rearLeftOpen = rlOpen,
                rearRightOpen = rrOpen,
                trunkOpen = trunkOpen,
                hoodOpen = hoodOpen,
                handbrakeEngaged = handbrake,
                driverSeatbeltBuckled = driverBeltBuckled,
                headlightsOn = if (statusByte2 != 0) headlights else state.headlightsOn,
                highBeamOn = if (statusByte2 != 0) highBeam else state.highBeamOn,
                fogLightsOn = if (statusByte2 != 0) fogLights else state.fogLightsOn,
                gearPosition = if (reverseGear) GearPosition.R else state.gearPosition,
                lastRawCanbusHex = rawHex.ifEmpty { String.format("DOOR MASK: 0x%02X", doorMask) }
            )
        }
    }

    private fun parseDoorStatusIntent(intent: Intent, rawBytes: ByteArray?, rawHex: String?) {
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            val mask = rawBytes[0].toInt() and 0xFF
            val byte2 = if (rawBytes.size > 1) rawBytes[1].toInt() and 0xFF else 0
            applyDoorBitmask(mask, byte2, rawHex ?: "")
            return
        }

        val maskExtra = intent.getIntExtra("door_mask", -1)
        if (maskExtra >= 0) {
            applyDoorBitmask(maskExtra, 0, String.format("DOOR_STATUS: %02X", maskExtra))
            return
        }

        TelemetryRepository.updateFromCanbusState { state ->
            val fl = intent.getBooleanExtra("fl", state.frontLeftOpen)
            val fr = intent.getBooleanExtra("fr", state.frontRightOpen)
            val rl = intent.getBooleanExtra("rl", state.rearLeftOpen)
            val rr = intent.getBooleanExtra("rr", state.rearRightOpen)
            val trunk = intent.getBooleanExtra("trunk", state.trunkOpen)
            val hood = intent.getBooleanExtra("hood", state.hoodOpen)
            state.copy(
                simModeActive = false,
                frontLeftOpen = fl,
                frontRightOpen = fr,
                rearLeftOpen = rl,
                rearRightOpen = rr,
                trunkOpen = trunk,
                hoodOpen = hood,
                lastRawCanbusHex = rawHex ?: "DOOR_INTENT: FL=$fl FR=$fr RL=$rl RR=$rr"
            )
        }
    }

    /**
     * Parses Toyota Aqua NHP10 AC Amplifier State from CANBUS 0x28 frame.
     */
    private fun applyToyotaAcBytes(
        flags0: Int,
        fanAndWind: Int,
        rawTemp: Int,
        flags3: Int,
        rawOutsideTemp: Int,
        rawHex: String
    ) {
        val acPower = (flags0 and 0x80) != 0
        val acCompressor = (flags0 and 0x40) != 0
        val recirculation = (flags0 and 0x20) != 0
        val autoMode = (flags0 and 0x08) != 0
        val frontDefrost = (flags0 and 0x02) != 0 || (flags3 and 0x80) != 0
        val rearDefogger = (flags0 and 0x01) != 0 || (flags3 and 0x40) != 0
        val ecoHeatCool = (flags3 and 0x10) != 0

        val fanLevel = (fanAndWind and 0x0F).coerceIn(0, 7)
        val windCode = ((fanAndWind shr 4) and 0x0F).coerceIn(1, 5)
        val airflow = AirflowMode.fromCanbusCode(windCode)

        // Toyota Aqua temp encoding: either 0.5C * byte (36..64 -> 18.0..32.0C) or direct 18..32
        val targetTempC = when {
            rawTemp in 36..64 -> rawTemp * 0.5f
            rawTemp in 18..32 -> rawTemp.toFloat()
            rawTemp == 0x00 -> 18.0f
            rawTemp == 0xFF || rawTemp == 0x1F -> 32.0f
            else -> 24.0f
        }.coerceIn(18.0f, 32.0f)

        TelemetryRepository.updateFromCanbusState { state ->
            val outsideC = if (rawOutsideTemp in 1..130) {
                (rawOutsideTemp - 40).toFloat()
            } else {
                state.outsideTempC
            }
            state.copy(
                simModeActive = false,
                acPowerOn = acPower,
                acCompressorOn = acCompressor,
                autoClimateMode = autoMode,
                recirculationInner = recirculation,
                frontDefrostMax = frontDefrost,
                rearWindowDefogger = rearDefogger,
                ecoHeatCoolMode = ecoHeatCool,
                fanSpeedLevel = if (acPower) fanLevel else 0,
                airflowMode = airflow,
                targetTempC = targetTempC,
                outsideTempC = outsideC,
                lastRawCanbusHex = rawHex
            )
        }
    }

    private fun parseAcStatusIntent(intent: Intent, rawBytes: ByteArray?, rawHex: String?) {
        if (rawBytes != null && rawBytes.size >= 4) {
            val outside = if (rawBytes.size > 4) rawBytes[4].toInt() and 0xFF else -1
            applyToyotaAcBytes(
                flags0 = rawBytes[0].toInt() and 0xFF,
                fanAndWind = rawBytes[1].toInt() and 0xFF,
                rawTemp = rawBytes[2].toInt() and 0xFF,
                flags3 = rawBytes[3].toInt() and 0xFF,
                rawOutsideTemp = outside,
                rawHex = rawHex ?: ""
            )
            return
        }

        TelemetryRepository.updateFromCanbusState { state ->
            val temp = intent.getFloatExtra("target_temp", state.targetTempC).coerceIn(18.0f, 32.0f)
            val fan = intent.getIntExtra("fan_speed", state.fanSpeedLevel).coerceIn(0, 7)
            val acOn = intent.getBooleanExtra("ac_compressor", state.acCompressorOn)
            val autoOn = intent.getBooleanExtra("auto_mode", state.autoClimateMode)
            val recirc = intent.getBooleanExtra("recirculation", state.recirculationInner)
            val frontDef = intent.getBooleanExtra("front_defrost", state.frontDefrostMax)
            val rearDef = intent.getBooleanExtra("rear_defogger", state.rearWindowDefogger)
            state.copy(
                simModeActive = false,
                acPowerOn = fan > 0 || autoOn,
                targetTempC = temp,
                fanSpeedLevel = fan,
                acCompressorOn = acOn,
                autoClimateMode = autoOn,
                recirculationInner = recirc,
                frontDefrostMax = frontDef,
                rearWindowDefogger = rearDef,
                lastRawCanbusHex = rawHex ?: "AC_INTENT: ${temp}C FAN=$fan"
            )
        }
    }

    private fun parseAccStateIntent(intent: Intent, rawHex: String?) {
        val accOn = intent.getBooleanExtra("acc_on", true) ||
            intent.getIntExtra("acc_state", 1) == 1
        TelemetryRepository.updateFromCanbusState { state ->
            state.copy(
                gearPosition = if (!accOn) GearPosition.P else state.gearPosition,
                speedKmh = if (!accOn) 0 else state.speedKmh,
                rpm = if (!accOn) 0 else state.rpm,
                lastRawCanbusHex = rawHex ?: "ACC_STATE: ${if (accOn) "ON" else "SLEEP"}"
            )
        }
    }

    private fun parseFallbackExtras(intent: Intent, action: String) {
        val cmdType = intent.getIntExtra("cmd_type", -1)
        TelemetryRepository.updateFromCanbusState { state ->
            state.copy(
                lastRawCanbusHex = "RX [$action] CMD=0x${String.format("%02X", cmdType and 0xFF)}"
            )
        }
    }
}

