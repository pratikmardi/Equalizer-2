package com.pratikmardi.equalizerpro

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : Activity() {
    private lateinit var eqView: EqualizerProView
    private val permissionRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 10, 15)
        window.navigationBarColor = Color.rgb(9, 10, 15)

        eqView = EqualizerProView(this)
        setContentView(eqView)

        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
        }
    }

    override fun onDestroy() {
        eqView.release()
        super.onDestroy()
    }
}

private class EqualizerProView(context: Context) : View(context) {
    private val bg = Color.rgb(9, 10, 15)
    private val panel = Color.rgb(17, 19, 27)
    private val panel2 = Color.rgb(22, 24, 34)
    private val text = Color.rgb(241, 243, 248)
    private val muted = Color.rgb(145, 150, 164)
    private val accent = Color.rgb(112, 126, 255)
    private val accent2 = Color.rgb(63, 220, 188)
    private val track = Color.rgb(47, 50, 64)

    private val bands = longArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    private val bandLabels = arrayOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")
    private val levels = FloatArray(10) { 0f }

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var visualizer: Visualizer? = null
    private var enabled = true
    private var bassEnabled = false
    private var selectedPreset = "Normal"
    private var master = 0.75f

    private val spectrum = FloatArray(48) { 0.12f }
    private val targetSpectrum = FloatArray(48) { 0.12f }
    private val random = Random(42)
    private val handler = Handler(Looper.getMainLooper())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }

    private var sliderTop = 0f
    private var sliderBottom = 0f
    private var activeSlider = -1

    private val animation = object : Runnable {
        override fun run() {
            for (i in spectrum.indices) {
                spectrum[i] += (targetSpectrum[i] - spectrum[i]) * 0.22f
                targetSpectrum[i] *= 0.90f
                if (targetSpectrum[i] < 0.08f) {
                    targetSpectrum[i] = 0.08f + random.nextFloat() * 0.22f
                }
            }
            invalidate()
            handler.postDelayed(this, 28)
        }
    }

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setupEffects()
        handler.post(animation)
    }

    private fun setupEffects() {
        try {
            equalizer = Equalizer(0, 0).also { eq ->
                eq.enabled = enabled
                val range = eq.bandLevelRange
                val minDb = range[0].toFloat()
                val maxDb = range[1].toFloat()
                for (i in 0 until min(10, eq.numberOfBands.toInt())) {
                    levels[i] = 0f
                    eq.setBandLevel(i.toShort(), 0)
                }
            }
        } catch (_: Throwable) {
            equalizer = null
        }

        try {
            bassBoost = BassBoost(0, 0).also { it.enabled = false; it.setStrength(700.toShort()) }
        } catch (_: Throwable) {
            bassBoost = null
        }

        try {
            visualizer = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 4) return
                        val bins = min(spectrum.size, fft.size / 2)
                        for (i in 0 until bins) {
                            val re = fft[i * 2].toInt()
                            val im = fft[i * 2 + 1].toInt()
                            val mag = sqrt((re * re + im * im).toFloat()) / 128f
                            val boost = 0.45f + 0.55f * levels[min(9, i * 10 / max(1, bins))] / 12f + 1f
                            targetSpectrum[i] = min(1f, 0.08f + mag * boost)
                        }
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
        } catch (_: Throwable) {
            visualizer = null
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(bg)
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density

        // Header
        bold.color = text
        bold.textSize = 25f * density
        c.drawText("Equalizer Pro", 20f * density, 38f * density, bold)

        paint.color = muted
        paint.textSize = 12f * density
        c.drawText("10-BAND AUDIO ENGINE", 20f * density, 58f * density, paint)

        drawPower(c, w - 56f * density, 34f * density, density)
        drawReset(c, w - 105f * density, 34f * density, density)

        // Spectrum card
        val cardTop = 76f * density
        val cardBottom = min(h * 0.33f, 250f * density)
        roundRect(c, 14f * density, cardTop, w - 14f * density, cardBottom, 20f * density, panel)
        paint.color = muted
        paint.textSize = 11f * density
        c.drawText("LIVE SPECTRUM", 28f * density, cardTop + 25f * density, paint)

        drawSpectrum(c, 26f * density, cardTop + 40f * density, w - 26f * density, cardBottom - 16f * density, density)

        // Equalizer panel
        val eqTop = cardBottom + 14f * density
        val eqBottom = eqTop + 250f * density
        roundRect(c, 14f * density, eqTop, w - 14f * density, eqBottom, 20f * density, panel)

        bold.color = text
        bold.textSize = 15f * density
        c.drawText("10-Band Equalizer", 28f * density, eqTop + 27f * density, bold)

        paint.color = muted
        paint.textSize = 11f * density
        c.drawText(if (enabled) "ACTIVE" else "BYPASSED", 28f * density, eqTop + 46f * density, paint)

        sliderTop = eqTop + 65f * density
        sliderBottom = eqBottom - 35f * density
        drawSliders(c, 28f * density, sliderTop, w - 28f * density, sliderBottom, density)

        // Presets
        val presetTop = eqBottom + 14f * density
        paint.color = muted
        paint.textSize = 11f * density
        c.drawText("PRESETS", 20f * density, presetTop + 18f * density, paint)
        val names = arrayOf("Normal", "Rock", "Pop", "Classical", "Jazz", "Bass Boost")
        var x = 20f * density
        var y = presetTop + 28f * density
        val chipH = 38f * density
        for (name in names) {
            val chipW = (paint.measureText(name) + 32f * density)
            if (x + chipW > w - 20f * density) {
                x = 20f * density
                y += chipH + 8f * density
            }
            val active = name == selectedPreset
            roundRect(c, x, y, x + chipW, y + chipH, 19f * density, if (active) accent else panel2)
            bold.color = if (active) Color.WHITE else text
            bold.textSize = 12f * density
            c.drawText(name, x + 16f * density, y + 24f * density, bold)
            x += chipW + 8f * density
        }

        // Bottom hint
        paint.color = muted
        paint.textSize = 10f * density
        c.drawText("Drag bands • Tap presets • Power toggles processing", 20f * density, h - 18f * density, paint)
    }

    private fun drawPower(c: Canvas, cx: Float, cy: Float, d: Float) {
        paint.color = if (enabled) accent2 else track
        c.drawCircle(cx, cy, 18f * d, paint)
        paint.color = bg
        c.drawCircle(cx, cy, 13f * d, paint)
        paint.color = if (enabled) accent2 else muted
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * d
        c.drawCircle(cx, cy + 1f * d, 7f * d, paint)
        c.drawLine(cx, cy - 10f * d, cx, cy - 1f * d, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawReset(c: Canvas, cx: Float, cy: Float, d: Float) {
        paint.color = panel2
        c.drawCircle(cx, cy, 18f * d, paint)
        paint.color = muted
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * d
        c.drawArc(cx - 8f*d, cy - 8f*d, cx + 8f*d, cy + 8f*d, -70f, 285f, false, paint)
        paint.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(cx + 7f*d, cy - 8f*d); lineTo(cx + 10f*d, cy - 1f*d); lineTo(cx + 3f*d, cy - 2f*d); close()
        }
        c.drawPath(path, paint)
    }

    private fun drawSpectrum(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: Float) {
        val count = spectrum.size
        val gap = 3f * d
        val bw = (r - l - gap * (count - 1)) / count
        for (i in 0 until count) {
            val v = if (enabled) spectrum[i].coerceIn(0.05f, 1f) else 0.06f
            val bh = (b - t) * v
            val left = l + i * (bw + gap)
            val top = b - bh
            val glow = Paint(Paint.ANTI_ALIAS_FLAG)
            glow.color = if (i % 3 == 0) accent else accent2
            glow.alpha = 35
            glow.maskFilter = BlurMaskFilter(8f * d, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(left, top, left + bw, b, bw/2, bw/2, glow)
            glow.maskFilter = null
            glow.alpha = 210
            c.drawRoundRect(left, top, left + bw, b, bw/2, bw/2, glow)
        }
    }

    private fun drawSliders(c: Canvas, l: Float, t: Float, r: Float, b: Float, d: Float) {
        val areaW = r - l
        val step = areaW / 10f
        for (i in 0 until 10) {
            val x = l + step * (i + 0.5f)
            paint.color = track
            c.drawRoundRect(x - 3f*d, t, x + 3f*d, b, 3f*d, 3f*d, paint)

            val norm = ((levels[i] + 12f) / 24f).coerceIn(0f, 1f)
            val y = b - norm * (b - t)
            paint.color = if (i == activeSlider) Color.WHITE else accent
            c.drawCircle(x, y, 9f*d, paint)
            paint.color = bg
            c.drawCircle(x, y, 4f*d, paint)

            bold.color = text
            bold.textSize = 9f*d
            bold.textAlign = Paint.Align.CENTER
            c.drawText(bandLabels[i], x, b + 19f*d, bold)

            paint.color = muted
            paint.textSize = 8f*d
            paint.textAlign = Paint.Align.CENTER
            val db = levels[i]
            val dbText = if (abs(db) < 0.5f) "0" else String.format("%+.0f", db)
            c.drawText(dbText, x, t - 9f*d, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        paint.color = color
        c.drawRoundRect(l, t, r, b, radius, radius, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    val dx = e.x
                    val w = width.toFloat()
                    if (dx > w - 80f*d && e.y < 70f*d) {
                        enabled = !enabled
                        equalizer?.enabled = enabled
                        bassBoost?.enabled = bassEnabled && enabled
                        invalidate()
                        return true
                    }
                    if (dx > w - 130f*d && dx < w - 80f*d && e.y < 70f*d) {
                        reset()
                        return true
                    }
                    val preset = hitPreset(e.x, e.y, d)
                    if (preset != null) {
                        applyPreset(preset)
                        return true
                    }
                }
                if (e.y in sliderTop..sliderBottom) {
                    val idx = ((e.x - 28f*d) / ((width - 56f*d) / 10f)).toInt().coerceIn(0, 9)
                    activeSlider = idx
                    val normalized = 1f - ((e.y - sliderTop) / (sliderBottom - sliderTop))
                    val db = normalized * 24f - 12f
                    levels[idx] = db.coerceIn(-12f, 12f)
                    equalizer?.let { eq ->
                        try {
                            val band = idx.toShort()
                            if (idx < eq.numberOfBands.toInt()) eq.setBandLevel(band, (levels[idx] * 100).toInt().toShort())
                        } catch (_: Throwable) {}
                    }
                    selectedPreset = "Custom"
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeSlider = -1
                invalidate()
            }
        }
        return true
    }

    private fun hitPreset(x: Float, y: Float, d: Float): String? {
        val top = min(height * 0.33f, 250f*d) + 14f*d + 250f*d + 14f*d
        val paintLocal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f*d }
        val names = arrayOf("Normal", "Rock", "Pop", "Classical", "Jazz", "Bass Boost")
        var px = 20f*d
        var py = top + 28f*d
        val chipH = 38f*d
        for (name in names) {
            val chipW = paintLocal.measureText(name) + 32f*d
            if (px + chipW > width - 20f*d) {
                px = 20f*d
                py += chipH + 8f*d
            }
            if (x in px..(px+chipW) && y in py..(py+chipH)) return name
            px += chipW + 8f*d
        }
        return null
    }

    private fun applyPreset(name: String) {
        selectedPreset = name
        val p = when (name) {
            "Rock" -> floatArrayOf(5f, 4f, 2f, -1f, -2f, 1f, 3f, 5f, 5f, 3f)
            "Pop" -> floatArrayOf(-1f, 1f, 4f, 4f, 2f, -1f, 2f, 4f, 4f, 2f)
            "Classical" -> floatArrayOf(4f, 3f, 2f, 0f, -1f, 1f, 2f, 3f, 4f, 4f)
            "Jazz" -> floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 1f, 2f, 4f, 3f)
            "Bass Boost" -> floatArrayOf(10f, 9f, 7f, 4f, 2f, 0f, 0f, 0f, 1f, 2f)
            else -> FloatArray(10) { 0f }
        }
        for (i in 0 until 10) {
            levels[i] = p[i]
            try {
                equalizer?.setBandLevel(i.toShort(), (levels[i] * 100).toInt().toShort())
            } catch (_: Throwable) {}
        }
        bassEnabled = name == "Bass Boost"
        bassBoost?.enabled = bassEnabled && enabled
        invalidate()
    }

    private fun reset() {
        applyPreset("Normal")
        selectedPreset = "Normal"
    }

    fun release() {
        handler.removeCallbacks(animation)
        try { visualizer?.release() } catch (_: Throwable) {}
        try { bassBoost?.release() } catch (_: Throwable) {}
        try { equalizer?.release() } catch (_: Throwable) {}
    }
}
