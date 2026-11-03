package com.aqua.cat7583.launcher.telemetry

enum class AirflowMode(val displayName: String, val canbusCode: Int) {
    FACE("FACE", 0x01),
    FACE_FEET("BI-LEVEL", 0x02),
    FEET("FLOOR", 0x03),
    FEET_DEFROST("FLOOR/DEF", 0x04),
    WINDSHIELD("DEFROST", 0x05);

    fun next(): AirflowMode {
        val values = values()
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromCanbusCode(code: Int): AirflowMode {
            return values().firstOrNull { it.canbusCode == code } ?: FACE
        }
    }
}

enum class GearPosition(val label: String, val canbusCode: Int) {
    P("P", 0x00),
    R("R", 0x01),
    N("N", 0x02),
    D("D", 0x03),
    B("B", 0x04);

    fun next(): GearPosition {
        val values = values()
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromCanbusCode(code: Int): GearPosition {
            return values().firstOrNull { it.canbusCode == code } ?: P
        }
    }
}

data class VehicleTelemetryState(
    // 1. Doors, Locks & Body
    val frontLeftOpen: Boolean = false,
    val frontRightOpen: Boolean = false,
    val rearLeftOpen: Boolean = false,
    val rearRightOpen: Boolean = false,
    val trunkOpen: Boolean = false,
    val hoodOpen: Boolean = false,
    val isCentralLocked: Boolean = true,
    val driverSeatbeltBuckled: Boolean = true,
    val passengerSeatbeltBuckled: Boolean = true,
    val handbrakeEngaged: Boolean = true,
    val headlightsOn: Boolean = false,
    val highBeamOn: Boolean = false,
    val fogLightsOn: Boolean = false,
    val autoDimmerNightMode: Boolean = false,

    // 2. Full AC & Climate Control State (Matching Toyota Aqua NHP10 Unit)
    val acPowerOn: Boolean = true,
    val acCompressorOn: Boolean = true,
    val autoClimateMode: Boolean = true,
    val targetTempC: Float = 24.0f,
    val cabinTempC: Float = 26.5f,
    val outsideTempC: Float = 30.0f,
    val fanSpeedLevel: Int = 3,
    val airflowMode: AirflowMode = AirflowMode.FACE,
    val recirculationInner: Boolean = true,
    val frontDefrostMax: Boolean = false,
    val rearWindowDefogger: Boolean = false,
    val ecoHeatCoolMode: Boolean = true,

    // 3. Powertrain & Toyota Hybrid Synergy Drive (HSD)
    val speedKmh: Int = 0,
    val rpm: Int = 0,
    val gearPosition: GearPosition = GearPosition.P,
    val evModeActive: Boolean = true,
    val ecoModeActive: Boolean = true,
    val coolantTempC: Int = 78,
    val inverterTempC: Int = 41,
    val hvBatterySocPercent: Int = 68,
    val hvBatteryTempC: Float = 34.2f,
    val hvCoolingFanStep: Int = 2,
    val aux12vBatteryVolts: Float = 14.1f,

    // 4. TPMS (Tire Pressure) & Chassis
    val tireFlPsi: Float = 33.0f,
    val tireFrPsi: Float = 33.0f,
    val tireRlPsi: Float = 32.0f,
    val tireRrPsi: Float = 32.0f,
    val steeringWheelAngleDeg: Int = 0,

    // 5. Reverse Parking Radar Sensors (4-Zone Distance 0-4, where 0 = clear, 4 = critical close)
    val radarRearLeft: Int = 0,
    val radarRearCenterLeft: Int = 0,
    val radarRearCenterRight: Int = 0,
    val radarRearRight: Int = 0,

    // 6. Fuel, Trip & LKR Cost Tracking
    val instantKmPerLiter: Float = 24.5f,
    val avgKmPerLiter: Float = 22.8f,
    val fuelTankPercent: Int = 72,
    val remainingRangeKm: Int = 580,
    val currentTripKm: Float = 14.2f,
    val currentTripDurationSec: Long = 1380L,
    val currentTripCostLkr: Float = 211.75f,
    val fuelPricePerLiterLkr: Float = 340.0f,

    // 7. Hardware Status & Sniffer
    val gpsConnected: Boolean = true,
    val canbusConnected: Boolean = false,
    val obdConnected: Boolean = false,
    val simModeActive: Boolean = true,
    val lastRawCanbusHex: String = "2E 28 05 B8 1E 63 10 CB"
) {
    val anyDoorOpen: Boolean
        get() = frontLeftOpen || frontRightOpen || rearLeftOpen || rearRightOpen || trunkOpen || hoodOpen

    val openDoorCount: Int
        get() = listOf(
            frontLeftOpen,
            frontRightOpen,
            rearLeftOpen,
            rearRightOpen,
            trunkOpen,
            hoodOpen
        ).count { it }
}

