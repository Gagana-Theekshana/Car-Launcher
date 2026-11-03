package com.aqua.cat7583.launcher.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.aqua.cat7583.launcher.telemetry.GearPosition
import com.aqua.cat7583.launcher.telemetry.TelemetryRepository
import com.aqua.cat7583.launcher.telemetry.VehicleTelemetryState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class AquaVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var telemetryState: VehicleTelemetryState = TelemetryRepository.state.value

    // Smoothly interpolated door swing fractions (0f = closed, 1f = open 35 deg)
    private var flOpenFraction = 0f
    private var frOpenFraction = 0f
    private var rlOpenFraction = 0f
    private var rrOpenFraction = 0f
    private var pulsePhase = 0f

    private val pulseAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
            pulsePhase = anim.animatedValue as Float
            val stepChanged = stepDoorAnimations()
            if (stepChanged || telemetryState.anyDoorOpen || telemetryState.gearPosition == GearPosition.R) {
                postInvalidateOnAnimation()
            }
        }
    }

    // Color Palette (Toyota Aqua NHP10 Facelift Pearl White & Hybrid Cyan)
    private val colorPearlWhite = Color.parseColor("#F5F7FA")
    private val colorPearlShadow = Color.parseColor("#D8E0E8")
    private val colorBodyOutline = Color.parseColor("#90A4AE")
    private val colorGlassCanopy = Color.parseColor("#1A222C")
    private val colorGlassReflection = Color.parseColor("#293646")
    private val colorGlossBlack = Color.parseColor("#121212")
    private val colorChrome = Color.parseColor("#CFD8DC")
    private val colorHybridCyan = Color.parseColor("#00E5FF")
    private val colorWarningRed = Color.parseColor("#FF1744")
    private val colorAmberWarn = Color.parseColor("#FFAB00")
    private val colorSafeGreen = Color.parseColor("#00E676")
    private val colorPanelBg = Color.parseColor("#141C24")

    // Pre-allocated Paints (Zero allocations during onDraw for 1GB RAM board)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPearlWhite
    }
    private val bodyCreasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = colorPearlShadow
    }
    private val bodyOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = colorBodyOutline
    }
    private val aeroBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorGlossBlack
    }
    private val chromeAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = colorChrome
    }
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorGlassCanopy
    }
    private val glassHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorGlassReflection
    }
    private val wheelTirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#181818")
    }
    private val wheelRimLipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = colorChrome
    }
    private val brakeCaliperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorWarningRed
    }
    private val warningFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorWarningRed
    }
    private val warningStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = colorWarningRed
    }
    private val lightBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fogBeamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val headlampLensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E0F7FA")
    }
    private val tailLampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D50000")
    }
    private val tpmsBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPanelBg
    }
    private val tpmsStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = colorHybridCyan
    }
    private val tpmsTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val tpmsUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE")
        textSize = 9.5f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val plateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0D131A")
    }
    private val plateBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = colorHybridCyan
    }
    private val plateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.08f
        textAlign = Paint.Align.CENTER
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorHybridCyan
        textSize = 10f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.12f
        textAlign = Paint.Align.CENTER
    }
    private val radarArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val hudGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#1A00E5FF")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    // Pre-allocated Paths & RectFs
    private val bodyPath = Path()
    private val lipPath = Path()
    private val diffuserPath = Path()
    private val canopyPath = Path()
    private val windshieldPath = Path()
    private val rearGlassPath = Path()
    private val hoodPath = Path()
    private val trunkPath = Path()
    private val sharkFinPath = Path()
    private val doorPath = Path()
    private val lightConePath = Path()

    private val wheelRect = RectF()
    private val caliperRect = RectF()
    private val mirrorRect = RectF()
    private val badgeRect = RectF()
    private val plateRect = RectF()
    private val radarOval = RectF()

    // Touch hit-test regions in canvas coordinates
    private val hitHood = RectF()
    private val hitTrunk = RectF()
    private val hitHeadlights = RectF()
    private val hitFlDoor = RectF()
    private val hitFrDoor = RectF()
    private val hitRlDoor = RectF()
    private val hitRrDoor = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulseAnimator.isRunning) {
            pulseAnimator.start()
        }
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    fun updateTelemetryState(newState: VehicleTelemetryState) {
        telemetryState = newState
        stepDoorAnimations()
        postInvalidateOnAnimation()
    }

    private fun stepDoorAnimations(): Boolean {
        var changed = false
        flOpenFraction = moveTowards(flOpenFraction, if (telemetryState.frontLeftOpen) 1f else 0f).also {
            if (it != flOpenFraction) changed = true
        }
        frOpenFraction = moveTowards(frOpenFraction, if (telemetryState.frontRightOpen) 1f else 0f).also {
            if (it != frOpenFraction) changed = true
        }
        rlOpenFraction = moveTowards(rlOpenFraction, if (telemetryState.rearLeftOpen) 1f else 0f).also {
            if (it != rlOpenFraction) changed = true
        }
        rrOpenFraction = moveTowards(rrOpenFraction, if (telemetryState.rearRightOpen) 1f else 0f).also {
            if (it != rrOpenFraction) changed = true
        }
        return changed
    }

    private fun moveTowards(current: Float, target: Float): Float {
        val diff = target - current
        return if (abs(diff) < 0.04f) target else current + diff * 0.25f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.45f

        // Scale the Toyota Aqua NHP10 top-down vector cleanly to fit the view
        val carLength = min(h * 0.68f, w * 0.88f)
        val carWidth = carLength * 0.44f
        val halfL = carLength * 0.5f
        val halfW = carWidth * 0.5f

        val frontY = cy - halfL
        val rearY = cy + halfL
        val leftX = cx - halfW
        val rightX = cx + halfW

        // Update touch hit-test zones
        hitHeadlights.set(leftX - 15f, frontY - 35f, rightX + 15f, frontY + carLength * 0.10f)
        hitHood.set(leftX + 10f, frontY + carLength * 0.10f, rightX - 10f, frontY + carLength * 0.27f)
        hitFlDoor.set(leftX - 35f, frontY + carLength * 0.27f, cx - 5f, frontY + carLength * 0.54f)
        hitFrDoor.set(cx + 5f, frontY + carLength * 0.27f, rightX + 35f, frontY + carLength * 0.54f)
        hitRlDoor.set(leftX - 35f, frontY + carLength * 0.54f, cx - 5f, frontY + carLength * 0.77f)
        hitRrDoor.set(cx + 5f, frontY + carLength * 0.54f, rightX + 35f, frontY + carLength * 0.77f)
        hitTrunk.set(leftX + 10f, frontY + carLength * 0.77f, rightX - 10f, rearY + 15f)

        // Subtle telemetry scanner crosshair ring
        canvas.drawCircle(cx, cy, halfL * 1.06f, hudGridPaint)

        // 1. Headlight & Fog Light Cones (Rendered first underneath front bumper)
        if (telemetryState.headlightsOn || telemetryState.highBeamOn) {
            drawHeadlightBeams(canvas, cx, frontY, halfW, carLength, telemetryState.highBeamOn)
        }
        if (telemetryState.fogLightsOn) {
            drawFogLightBeams(canvas, cx, frontY, halfW, carLength)
        }

        // 2. 4 Wheels with Black Rims, Machined Silver Lips, and Red Brake Caliper Accents
        drawFourWheels(canvas, leftX, rightX, frontY, carLength, telemetryState.steeringWheelAngleDeg)

        // 3. Modellista-Style Black/Chrome Front Lip Spoiler & Rear Bumper Diffuser
        drawFrontLipAndRearDiffuser(canvas, cx, frontY, rearY, halfW, carLength)

        // 4. Contoured Pearl White Toyota Aqua NHP10 Facelift Body Shell
        drawMainBodyShell(canvas, cx, frontY, rearY, leftX, rightX, halfW, carLength)

        // 5. Dark-Tinted Glass Canopy, Windshield, Rear Hatch Glass & Roof Panel
        drawGlassCanopyAndRoof(canvas, cx, frontY, rearY, halfW, carLength)

        // 6. Hood & Trunk Open Warning Overlays
        val pulseAlpha = (140 + (sin(pulsePhase.toDouble()) * 85).toInt()).coerceIn(60, 235)
        if (telemetryState.hoodOpen) {
            drawOpenHoodWarning(canvas, cx, frontY, halfW, carLength, pulseAlpha)
        }
        if (telemetryState.trunkOpen) {
            drawOpenTrunkWarning(canvas, cx, rearY, halfW, carLength, pulseAlpha)
        }

        // 7. Projector Headlamps & Facelift LED Tail Lamps
        drawExteriorLamps(canvas, leftX, rightX, frontY, rearY, halfW, carLength)

        // 8. 4 Individual Swinging Doors (FL, FR, RL, RR) + Gloss Black Side Mirrors
        drawSwingingDoorsAndMirrors(canvas, leftX, rightX, frontY, carLength, pulseAlpha)

        // 9. Pearl White Shark-Fin Antenna near Rear Spoiler
        drawSharkFinAntenna(canvas, cx, rearY, carLength)

        // 10. Live 4-Wheel TPMS Tire Pressure Badges
        drawTpmsBadges(canvas, leftX, rightX, frontY, carLength)

        // 11. Reverse Ultrasonic Parking Radar Arcs (4-Zone behind Rear Bumper when in R)
        if (telemetryState.gearPosition == GearPosition.R) {
            drawReverseRadarArcs(canvas, cx, rearY, halfW)
        }

        // 12. Bottom License Plate Badge ("WP CAT 7583") & Hybrid Synergy Drive Sub-label
        drawRegistrationBadge(canvas, cx, h, rearY)
    }

    private fun drawHeadlightBeams(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        halfW: Float,
        carLength: Float,
        highBeam: Boolean
    ) {
        val beamReach = if (highBeam) carLength * 0.42f else carLength * 0.28f
        val topY = max(4f, frontY - beamReach)
        val startAlpha = if (highBeam) 150 else 105
        lightBeamPaint.shader = LinearGradient(
            cx,
            frontY,
            cx,
            topY,
            Color.argb(startAlpha, 215, 248, 255),
            Color.argb(0, 0, 229, 255),
            Shader.TileMode.CLAMP
        )

        // Left Projector Cone
        val lx = cx - halfW * 0.68f
        lightConePath.reset()
        lightConePath.moveTo(lx - halfW * 0.14f, frontY + 12f)
        lightConePath.lineTo(lx - halfW * 0.56f, topY)
        lightConePath.lineTo(lx + halfW * 0.28f, topY)
        lightConePath.lineTo(lx + halfW * 0.14f, frontY + 12f)
        lightConePath.close()
        canvas.drawPath(lightConePath, lightBeamPaint)

        // Right Projector Cone
        val rx = cx + halfW * 0.68f
        lightConePath.reset()
        lightConePath.moveTo(rx - halfW * 0.14f, frontY + 12f)
        lightConePath.lineTo(rx - halfW * 0.28f, topY)
        lightConePath.lineTo(rx + halfW * 0.56f, topY)
        lightConePath.lineTo(rx + halfW * 0.14f, frontY + 12f)
        lightConePath.close()
        canvas.drawPath(lightConePath, lightBeamPaint)
    }

    private fun drawFogLightBeams(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        halfW: Float,
        carLength: Float
    ) {
        val fogReach = carLength * 0.16f
        val topY = max(8f, frontY - fogReach)
        fogBeamPaint.shader = LinearGradient(
            cx,
            frontY,
            cx,
            topY,
            Color.argb(125, 255, 255, 255),
            Color.argb(0, 200, 250, 255),
            Shader.TileMode.CLAMP
        )

        // Wide low-angle LED fog spread
        lightConePath.reset()
        lightConePath.moveTo(cx - halfW * 0.85f, frontY + 16f)
        lightConePath.lineTo(cx - halfW * 1.35f, topY)
        lightConePath.lineTo(cx + halfW * 1.35f, topY)
        lightConePath.lineTo(cx + halfW * 0.85f, frontY + 16f)
        lightConePath.close()
        canvas.drawPath(lightConePath, fogBeamPaint)
    }

    private fun drawFourWheels(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        frontY: Float,
        carLength: Float,
        steeringAngleDeg: Int
    ) {
        val wheelW = carLength * 0.068f
        val wheelH = carLength * 0.155f
        val frontWheelCenterY = frontY + carLength * 0.205f
        val rearWheelCenterY = frontY + carLength * 0.805f
        val visualSteerAngle = (steeringAngleDeg / 18f).coerceIn(-24f, 24f)

        // Front Left Wheel
        drawSingleWheel(
            canvas = canvas,
            centerX = leftX + wheelW * 0.25f,
            centerY = frontWheelCenterY,
            wheelW = wheelW,
            wheelH = wheelH,
            isLeftSide = true,
            steerDeg = visualSteerAngle
        )
        // Front Right Wheel
        drawSingleWheel(
            canvas = canvas,
            centerX = rightX - wheelW * 0.25f,
            centerY = frontWheelCenterY,
            wheelW = wheelW,
            wheelH = wheelH,
            isLeftSide = false,
            steerDeg = visualSteerAngle
        )
        // Rear Left Wheel
        drawSingleWheel(
            canvas = canvas,
            centerX = leftX + wheelW * 0.25f,
            centerY = rearWheelCenterY,
            wheelW = wheelW,
            wheelH = wheelH,
            isLeftSide = true,
            steerDeg = 0f
        )
        // Rear Right Wheel
        drawSingleWheel(
            canvas = canvas,
            centerX = rightX - wheelW * 0.25f,
            centerY = rearWheelCenterY,
            wheelW = wheelW,
            wheelH = wheelH,
            isLeftSide = false,
            steerDeg = 0f
        )
    }

    private fun drawSingleWheel(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        wheelW: Float,
        wheelH: Float,
        isLeftSide: Boolean,
        steerDeg: Float
    ) {
        canvas.save()
        canvas.rotate(steerDeg, centerX, centerY)

        wheelRect.set(
            centerX - wheelW * 0.5f,
            centerY - wheelH * 0.5f,
            centerX + wheelW * 0.5f,
            centerY + wheelH * 0.5f
        )
        // Black Tire & Rim
        canvas.drawRoundRect(wheelRect, 6f, 6f, wheelTirePaint)

        // Machined Silver Lip
        wheelRect.inset(2.5f, 3.5f)
        canvas.drawRoundRect(wheelRect, 4f, 4f, wheelRimLipPaint)

        // Red Brake Caliper Accent (#FF1744)
        val caliperX = if (isLeftSide) wheelRect.left + 2f else wheelRect.right - 6f
        caliperRect.set(caliperX, centerY - wheelH * 0.18f, caliperX + 4f, centerY + wheelH * 0.08f)
        canvas.drawRoundRect(caliperRect, 2f, 2f, brakeCaliperPaint)

        canvas.restore()
    }

    private fun drawFrontLipAndRearDiffuser(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        rearY: Float,
        halfW: Float,
        carLength: Float
    ) {
        // Aggressive Black/Chrome Modellista-style Front Lip Spoiler
        lipPath.reset()
        lipPath.moveTo(cx - halfW * 0.84f, frontY + carLength * 0.055f)
        lipPath.quadTo(cx, frontY - carLength * 0.025f, cx + halfW * 0.84f, frontY + carLength * 0.055f)
        lipPath.lineTo(cx + halfW * 0.76f, frontY + carLength * 0.085f)
        lipPath.quadTo(cx, frontY + carLength * 0.015f, cx - halfW * 0.76f, frontY + carLength * 0.085f)
        lipPath.close()
        canvas.drawPath(lipPath, aeroBlackPaint)
        canvas.drawPath(lipPath, chromeAccentPaint)

        // Black Rear Bumper Diffuser with F1 center fin accents
        diffuserPath.reset()
        diffuserPath.moveTo(cx - halfW * 0.82f, rearY - carLength * 0.045f)
        diffuserPath.lineTo(cx + halfW * 0.82f, rearY - carLength * 0.045f)
        diffuserPath.lineTo(cx + halfW * 0.72f, rearY + carLength * 0.018f)
        diffuserPath.quadTo(cx, rearY + carLength * 0.030f, cx - halfW * 0.72f, rearY + carLength * 0.018f)
        diffuserPath.close()
        canvas.drawPath(diffuserPath, aeroBlackPaint)
        canvas.drawPath(diffuserPath, chromeAccentPaint)
    }

    private fun drawMainBodyShell(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        rearY: Float,
        leftX: Float,
        rightX: Float,
        halfW: Float,
        carLength: Float
    ) {
        bodyPath.reset()
        // Front bumper nose
        bodyPath.moveTo(cx - halfW * 0.72f, frontY + carLength * 0.03f)
        bodyPath.quadTo(cx, frontY - carLength * 0.008f, cx + halfW * 0.72f, frontY + carLength * 0.03f)
        // Right front fender flare
        bodyPath.quadTo(rightX + 4f, frontY + carLength * 0.11f, rightX, frontY + carLength * 0.24f)
        // Right side waist
        bodyPath.lineTo(rightX - 2f, frontY + carLength * 0.74f)
        // Right rear quarter & haunch
        bodyPath.quadTo(rightX + 4f, frontY + carLength * 0.86f, cx + halfW * 0.76f, rearY - carLength * 0.02f)
        // Rear bumper tail
        bodyPath.quadTo(cx, rearY + carLength * 0.012f, cx - halfW * 0.76f, rearY - carLength * 0.02f)
        // Left rear quarter & haunch
        bodyPath.quadTo(leftX - 4f, frontY + carLength * 0.86f, leftX + 2f, frontY + carLength * 0.74f)
        // Left side waist
        bodyPath.lineTo(leftX, frontY + carLength * 0.24f)
        // Left front fender flare
        bodyPath.quadTo(leftX - 4f, frontY + carLength * 0.11f, cx - halfW * 0.72f, frontY + carLength * 0.03f)
        bodyPath.close()

        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawPath(bodyPath, bodyOutlinePaint)

        // Sculpted Hood Character Lines
        hoodPath.reset()
        hoodPath.moveTo(cx - halfW * 0.65f, frontY + carLength * 0.08f)
        hoodPath.lineTo(cx - halfW * 0.74f, frontY + carLength * 0.25f)
        hoodPath.moveTo(cx + halfW * 0.65f, frontY + carLength * 0.08f)
        hoodPath.lineTo(cx + halfW * 0.74f, frontY + carLength * 0.25f)
        canvas.drawPath(hoodPath, bodyCreasePaint)

        // Toyota Hybrid Front Nose Emblem Badge
        badgeRect.set(
            cx - halfW * 0.11f,
            frontY + carLength * 0.045f,
            cx + halfW * 0.11f,
            frontY + carLength * 0.072f
        )
        tpmsStrokePaint.color = colorHybridCyan
        canvas.drawOval(badgeRect, tpmsStrokePaint)
    }

    private fun drawGlassCanopyAndRoof(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        rearY: Float,
        halfW: Float,
        carLength: Float
    ) {
        // Dark-tinted glass greenhouse (Windshield + Side Windows + Rear Hatch Glass)
        canopyPath.reset()
        canopyPath.moveTo(cx - halfW * 0.72f, frontY + carLength * 0.26f)
        canopyPath.quadTo(cx, frontY + carLength * 0.22f, cx + halfW * 0.72f, frontY + carLength * 0.26f)
        canopyPath.lineTo(cx + halfW * 0.66f, frontY + carLength * 0.86f)
        canopyPath.quadTo(cx, frontY + carLength * 0.90f, cx - halfW * 0.66f, frontY + carLength * 0.86f)
        canopyPath.close()
        canvas.drawPath(canopyPath, glassPaint)

        // Front Windshield Reflection Highlight
        windshieldPath.reset()
        windshieldPath.moveTo(cx - halfW * 0.66f, frontY + carLength * 0.27f)
        windshieldPath.quadTo(cx, frontY + carLength * 0.24f, cx + halfW * 0.66f, frontY + carLength * 0.27f)
        windshieldPath.lineTo(cx + halfW * 0.56f, frontY + carLength * 0.39f)
        windshieldPath.quadTo(cx, frontY + carLength * 0.37f, cx - halfW * 0.56f, frontY + carLength * 0.39f)
        windshieldPath.close()
        canvas.drawPath(windshieldPath, glassHighlightPaint)

        // Pearl White Roof Panel Center Insert
        val roofRect = badgeRect
        roofRect.set(
            cx - halfW * 0.55f,
            frontY + carLength * 0.40f,
            cx + halfW * 0.55f,
            frontY + carLength * 0.75f
        )
        canvas.drawRoundRect(roofRect, 14f, 14f, bodyPaint)
        canvas.drawRoundRect(roofRect, 14f, 14f, bodyCreasePaint)

        // Rear Spoiler Wing (Gloss Black Edge + Pearl White Main Wing)
        rearGlassPath.reset()
        rearGlassPath.moveTo(cx - halfW * 0.65f, rearY - carLength * 0.14f)
        rearGlassPath.lineTo(cx + halfW * 0.65f, rearY - carLength * 0.14f)
        rearGlassPath.lineTo(cx + halfW * 0.62f, rearY - carLength * 0.09f)
        rearGlassPath.lineTo(cx - halfW * 0.62f, rearY - carLength * 0.09f)
        rearGlassPath.close()
        canvas.drawPath(rearGlassPath, aeroBlackPaint)
    }

    private fun drawOpenHoodWarning(
        canvas: Canvas,
        cx: Float,
        frontY: Float,
        halfW: Float,
        carLength: Float,
        pulseAlpha: Int
    ) {
        hoodPath.reset()
        hoodPath.moveTo(cx - halfW * 0.68f, frontY + carLength * 0.08f)
        hoodPath.quadTo(cx, frontY + carLength * 0.05f, cx + halfW * 0.68f, frontY + carLength * 0.08f)
        hoodPath.lineTo(cx + halfW * 0.72f, frontY + carLength * 0.25f)
        hoodPath.quadTo(cx, frontY + carLength * 0.22f, cx - halfW * 0.72f, frontY + carLength * 0.25f)
        hoodPath.close()

        warningFillPaint.alpha = (pulseAlpha * 0.55f).toInt()
        warningStrokePaint.alpha = pulseAlpha
        canvas.drawPath(hoodPath, warningFillPaint)
        canvas.drawPath(hoodPath, warningStrokePaint)
    }

    private fun drawOpenTrunkWarning(
        canvas: Canvas,
        cx: Float,
        rearY: Float,
        halfW: Float,
        carLength: Float,
        pulseAlpha: Int
    ) {
        trunkPath.reset()
        trunkPath.moveTo(cx - halfW * 0.64f, rearY - carLength * 0.22f)
        trunkPath.lineTo(cx + halfW * 0.64f, rearY - carLength * 0.22f)
        trunkPath.lineTo(cx + halfW * 0.70f, rearY - carLength * 0.04f)
        trunkPath.quadTo(cx, rearY - carLength * 0.01f, cx - halfW * 0.70f, rearY - carLength * 0.04f)
        trunkPath.close()

        warningFillPaint.alpha = (pulseAlpha * 0.55f).toInt()
        warningStrokePaint.alpha = pulseAlpha
        canvas.drawPath(trunkPath, warningFillPaint)
        canvas.drawPath(trunkPath, warningStrokePaint)
    }

    private fun drawExteriorLamps(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        frontY: Float,
        rearY: Float,
        halfW: Float,
        carLength: Float
    ) {
        // NHP10 Facelift Bi-Beam Projector Headlamps
        headlampLensPaint.color = if (telemetryState.headlightsOn) {
            Color.parseColor("#80D8FF")
        } else {
            Color.parseColor("#B0BEC5")
        }

        // Left Headlamp
        badgeRect.set(
            leftX + halfW * 0.10f,
            frontY + carLength * 0.045f,
            leftX + halfW * 0.48f,
            frontY + carLength * 0.105f
        )
        canvas.drawRoundRect(badgeRect, 6f, 6f, headlampLensPaint)

        // Right Headlamp
        badgeRect.set(
            rightX - halfW * 0.48f,
            frontY + carLength * 0.045f,
            rightX - halfW * 0.10f,
            frontY + carLength * 0.105f
        )
        canvas.drawRoundRect(badgeRect, 6f, 6f, headlampLensPaint)

        // Facelift Vertical LED Tail Lamps
        tailLampPaint.color = if (telemetryState.headlightsOn || telemetryState.handbrakeEngaged) {
            Color.parseColor("#FF1744")
        } else {
            Color.parseColor("#8E0000")
        }
        badgeRect.set(
            leftX + halfW * 0.06f,
            rearY - carLength * 0.16f,
            leftX + halfW * 0.24f,
            rearY - carLength * 0.04f
        )
        canvas.drawRoundRect(badgeRect, 4f, 4f, tailLampPaint)

        badgeRect.set(
            rightX - halfW * 0.24f,
            rearY - carLength * 0.16f,
            rightX - halfW * 0.06f,
            rearY - carLength * 0.04f
        )
        canvas.drawRoundRect(badgeRect, 4f, 4f, tailLampPaint)
    }

    private fun drawSwingingDoorsAndMirrors(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        frontY: Float,
        carLength: Float,
        pulseAlpha: Int
    ) {
        val frontHingeY = frontY + carLength * 0.30f
        val frontDoorLen = carLength * 0.23f
        val rearHingeY = frontY + carLength * 0.54f
        val rearDoorLen = carLength * 0.21f
        val doorThickness = carLength * 0.032f

        // Front Left Door (FL) - Swings outward -35 deg when open
        drawSingleDoor(
            canvas = canvas,
            hingeX = leftX + 2f,
            hingeY = frontHingeY,
            doorLen = frontDoorLen,
            thickness = doorThickness,
            isLeftSide = true,
            hasMirror = true,
            openFraction = flOpenFraction,
            pulseAlpha = pulseAlpha
        )

        // Front Right Door (FR) - Swings outward +35 deg when open
        drawSingleDoor(
            canvas = canvas,
            hingeX = rightX - 2f,
            hingeY = frontHingeY,
            doorLen = frontDoorLen,
            thickness = doorThickness,
            isLeftSide = false,
            hasMirror = true,
            openFraction = frOpenFraction,
            pulseAlpha = pulseAlpha
        )

        // Rear Left Door (RL) - Swings outward -35 deg when open
        drawSingleDoor(
            canvas = canvas,
            hingeX = leftX + 2f,
            hingeY = rearHingeY,
            doorLen = rearDoorLen,
            thickness = doorThickness,
            isLeftSide = true,
            hasMirror = false,
            openFraction = rlOpenFraction,
            pulseAlpha = pulseAlpha
        )

        // Rear Right Door (RR) - Swings outward +35 deg when open
        drawSingleDoor(
            canvas = canvas,
            hingeX = rightX - 2f,
            hingeY = rearHingeY,
            doorLen = rearDoorLen,
            thickness = doorThickness,
            isLeftSide = false,
            hasMirror = false,
            openFraction = rrOpenFraction,
            pulseAlpha = pulseAlpha
        )
    }

    private fun drawSingleDoor(
        canvas: Canvas,
        hingeX: Float,
        hingeY: Float,
        doorLen: Float,
        thickness: Float,
        isLeftSide: Boolean,
        hasMirror: Boolean,
        openFraction: Float,
        pulseAlpha: Int
    ) {
        val swingDeg = (if (isLeftSide) 35f else -35f) * openFraction

        canvas.save()
        canvas.rotate(swingDeg, hingeX, hingeY)

        // Warning red glow arc inside cabin doorway when open
        if (openFraction > 0.05f) {
            warningFillPaint.alpha = (pulseAlpha * 0.45f * openFraction).toInt()
            warningStrokePaint.alpha = (pulseAlpha * openFraction).toInt()

            doorPath.reset()
            val innerOffset = if (isLeftSide) thickness * 1.4f else -thickness * 1.4f
            doorPath.moveTo(hingeX, hingeY)
            doorPath.lineTo(hingeX + innerOffset, hingeY + doorLen * 0.85f)
            doorPath.lineTo(hingeX, hingeY + doorLen)
            doorPath.close()
            canvas.drawPath(doorPath, warningFillPaint)
        }

        // Door outer panel skin
        val left = if (isLeftSide) hingeX - thickness * 0.4f else hingeX - thickness * 0.6f
        val right = left + thickness
        badgeRect.set(left, hingeY, right, hingeY + doorLen)
        canvas.drawRoundRect(badgeRect, 4f, 4f, bodyPaint)
        if (openFraction > 0.05f) {
            warningStrokePaint.alpha = pulseAlpha
            canvas.drawRoundRect(badgeRect, 4f, 4f, warningStrokePaint)
        } else {
            canvas.drawRoundRect(badgeRect, 4f, 4f, bodyOutlinePaint)
        }

        // Gloss Black Side Mirror (#121212) mounted near front door A-pillar
        if (hasMirror) {
            val mirrorW = thickness * 1.9f
            val mirrorH = doorLen * 0.22f
            val mirrorTop = hingeY + doorLen * 0.08f
            if (isLeftSide) {
                mirrorRect.set(left - mirrorW, mirrorTop, left + 2f, mirrorTop + mirrorH)
            } else {
                mirrorRect.set(right - 2f, mirrorTop, right + mirrorW, mirrorTop + mirrorH)
            }
            canvas.drawRoundRect(mirrorRect, 5f, 5f, aeroBlackPaint)
            canvas.drawRoundRect(mirrorRect, 5f, 5f, bodyOutlinePaint)
        }

        canvas.restore()
    }

    private fun drawSharkFinAntenna(canvas: Canvas, cx: Float, rearY: Float, carLength: Float) {
        val finCenterY = rearY - carLength * 0.29f
        val finHalfW = carLength * 0.018f
        val finLen = carLength * 0.065f

        sharkFinPath.reset()
        sharkFinPath.moveTo(cx, finCenterY - finLen * 0.5f)
        sharkFinPath.lineTo(cx + finHalfW, finCenterY + finLen * 0.5f)
        sharkFinPath.lineTo(cx - finHalfW, finCenterY + finLen * 0.5f)
        sharkFinPath.close()

        canvas.drawPath(sharkFinPath, bodyPaint)
        canvas.drawPath(sharkFinPath, bodyOutlinePaint)
    }

    private fun drawTpmsBadges(
        canvas: Canvas,
        leftX: Float,
        rightX: Float,
        frontY: Float,
        carLength: Float
    ) {
        val frontWheelY = frontY + carLength * 0.205f
        val rearWheelY = frontY + carLength * 0.805f
        val horizontalOffset = carLength * 0.145f

        drawSingleTpmsBadge(
            canvas = canvas,
            centerX = leftX - horizontalOffset,
            centerY = frontWheelY,
            psi = telemetryState.tireFlPsi,
            label = "FL"
        )
        drawSingleTpmsBadge(
            canvas = canvas,
            centerX = rightX + horizontalOffset,
            centerY = frontWheelY,
            psi = telemetryState.tireFrPsi,
            label = "FR"
        )
        drawSingleTpmsBadge(
            canvas = canvas,
            centerX = leftX - horizontalOffset,
            centerY = rearWheelY,
            psi = telemetryState.tireRlPsi,
            label = "RL"
        )
        drawSingleTpmsBadge(
            canvas = canvas,
            centerX = rightX + horizontalOffset,
            centerY = rearWheelY,
            psi = telemetryState.tireRrPsi,
            label = "RR"
        )
    }

    private fun drawSingleTpmsBadge(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        psi: Float,
        label: String
    ) {
        val isLow = psi < 28.0f
        val boxW = 54f
        val boxH = 34f
        badgeRect.set(
            centerX - boxW * 0.5f,
            centerY - boxH * 0.5f,
            centerX + boxW * 0.5f,
            centerY + boxH * 0.5f
        )

        tpmsStrokePaint.color = if (isLow) colorWarningRed else colorHybridCyan
        tpmsTextPaint.color = if (isLow) colorWarningRed else Color.WHITE

        canvas.drawRoundRect(badgeRect, 7f, 7f, tpmsBoxPaint)
        canvas.drawRoundRect(badgeRect, 7f, 7f, tpmsStrokePaint)

        canvas.drawText(String.format("%.1f", psi), centerX, centerY + 1f, tpmsTextPaint)
        canvas.drawText("$label PSI", centerX, centerY + 12.5f, tpmsUnitPaint)
    }

    private fun drawReverseRadarArcs(
        canvas: Canvas,
        cx: Float,
        rearY: Float,
        halfW: Float
    ) {
        val zones = intArrayOf(
            telemetryState.radarRearLeft,
            telemetryState.radarRearCenterLeft,
            telemetryState.radarRearCenterRight,
            telemetryState.radarRearRight
        )
        // 4 angular segments behind the rear bumper (covering 50 deg to 130 deg)
        val startAngles = floatArrayOf(112f, 92f, 72f, 52f)
        val sweepAngle = 16f

        for (zoneIdx in 0..3) {
            val level = zones[zoneIdx].coerceIn(0, 4)
            if (level == 0) continue

            for (ring in 1..3) {
                val radius = halfW * (0.35f + ring * 0.22f)
                radarOval.set(cx - radius, rearY - radius * 0.65f, cx + radius, rearY + radius)
                radarArcPaint.color = when {
                    level >= 4 || ring == 1 -> colorWarningRed
                    level == 3 || ring == 2 -> colorAmberWarn
                    else -> colorSafeGreen
                }
                val activeRing = (5 - level) <= ring
                radarArcPaint.alpha = if (activeRing) 230 else 65
                canvas.drawArc(radarOval, startAngles[zoneIdx], sweepAngle, false, radarArcPaint)
            }
        }
    }

    private fun drawRegistrationBadge(
        canvas: Canvas,
        cx: Float,
        viewHeight: Float,
        rearY: Float
    ) {
        val plateW = 168f
        val plateH = 30f
        val plateTop = min(viewHeight - 46f, rearY + 18f)

        plateRect.set(cx - plateW * 0.5f, plateTop, cx + plateW * 0.5f, plateTop + plateH)
        canvas.drawRoundRect(plateRect, 6f, 6f, plateBgPaint)
        canvas.drawRoundRect(plateRect, 6f, 6f, plateBorderPaint)

        canvas.drawText("WP CAT 7583", cx, plateTop + 20.5f, plateTextPaint)
        canvas.drawText(
            "TOYOTA HYBRID SYNERGY DRIVE",
            cx,
            plateTop + plateH + 13.5f,
            subLabelPaint
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            when {
                hitFlDoor.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(0)
                    performClick()
                    return true
                }
                hitFrDoor.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(1)
                    performClick()
                    return true
                }
                hitRlDoor.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(2)
                    performClick()
                    return true
                }
                hitRrDoor.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(3)
                    performClick()
                    return true
                }
                hitTrunk.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(4)
                    performClick()
                    return true
                }
                hitHood.contains(x, y) -> {
                    TelemetryRepository.toggleDoor(5)
                    performClick()
                    return true
                }
                hitHeadlights.contains(x, y) -> {
                    TelemetryRepository.toggleHeadlights()
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}

