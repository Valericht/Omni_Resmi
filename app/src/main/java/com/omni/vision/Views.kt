package com.omni.vision

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/** Joystick virtual. vx: kanan +, vy: atas +, keduanya -1..1; kembali ke 0 saat dilepas. lockY = hanya sumbu X. */
class JoystickView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var lockY = false
    @Volatile var vx = 0f; private set
    @Volatile var vy = 0f; private set
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 255, 255) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = min(width, height) / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = (e.x - width / 2f) / r
                var dy = (e.y - height / 2f) / r
                if (lockY) dy = 0f
                val m = hypot(dx, dy)
                if (m > 1f) { dx /= m; dy /= m }
                vx = dx; vy = -dy
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vx = 0f; vy = 0f }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        c.drawCircle(cx, cy, r - 2f, bg)
        c.drawCircle(cx, cy, r - 2f, ring)
        c.drawCircle(cx + vx * r * 0.7f, cy - vy * r * 0.7f, r * 0.28f, knob)
    }
}

/** FR7/FR11: coretan landmark yang digambar di overlay. matched = terkena deteksi frame ini. */
class LmDraw(val name: String, val pts: FloatArray, val matched: Boolean)

/** Overlay hasil deteksi di atas preview (FIT_CENTER): kotak kelas, mask training, zona halangan, FPS. Koordinat input 0..1 terhadap frame. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    @Volatile var dets: List<Det> = emptyList()
    @Volatile var mask: Bitmap? = null
    @Volatile var obs: Obs? = null
    @Volatile var zone: FloatArray? = null        // x0, x1, y0 (normalisasi)
    @Volatile var fps = 0f
    @Volatile var fw = 4
    @Volatile var fh = 3
    @Volatile var lms: List<LmDraw> = emptyList()
    var onTap: ((Float, Float) -> Unit)? = null
    var scribble = false                          // FR11: mode coret bebas
    var onStroke: ((FloatArray) -> Unit)? = null  // coretan selesai: x0,y0,x1,y1,... normalisasi
    private val stroke = ArrayList<Float>()
    private val lmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; strokeJoin = Paint.Join.ROUND }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.YELLOW; strokeJoin = Paint.Join.ROUND }

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.WHITE; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val zonePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }

    private fun scale() = min(width / fw.toFloat(), height / fh.toFloat())

    override fun onDraw(c: Canvas) {
        val s = scale()
        val ox = (width - fw * s) / 2f; val oy = (height - fh * s) / 2f
        val W = fw * s; val H = fh * s
        mask?.let { c.drawBitmap(it, null, RectF(ox, oy, ox + W, oy + H), null) }
        zone?.let { z ->
            val o = obs
            zonePaint.color = when (o?.level) { ObsLevel.STOP -> Color.RED; ObsLevel.SLOW -> Color.YELLOW; else -> Color.argb(120, 255, 255, 255) }
            c.drawRect(ox + z[0] * W, oy + z[2] * H, ox + z[1] * W, oy + H, zonePaint)
        }
        for (l in lms) {                                   // landmark tersimpan: coretan asli + nama
            if (l.pts.size < 4) continue
            lmPaint.color = if (l.matched) Color.CYAN else Color.argb(110, 0, 255, 255)
            val path = Path()
            path.moveTo(ox + l.pts[0] * W, oy + l.pts[1] * H)
            var i = 2
            while (i + 1 < l.pts.size) { path.lineTo(ox + l.pts[i] * W, oy + l.pts[i + 1] * H); i += 2 }
            c.drawPath(path, lmPaint)
            c.drawText(l.name, ox + l.pts[0] * W + 6f, oy + l.pts[1] * H - 8f, txt)
        }
        for (d in dets) {
            val def = Classes.get(d.cls) ?: continue
            box.color = def.color
            c.drawRect(ox + d.x * W, oy + d.y * H, ox + (d.x + d.w) * W, oy + (d.y + d.h) * H, box)
            val nm = d.landmark?.let { " [$it]" } ?: ""
            c.drawText("${def.id}$nm ${(d.area * 100).toInt()}%", ox + d.x * W + 4f, oy + d.y * H + 28f, txt)
        }
        if (stroke.size >= 4) {
            val path = Path()
            path.moveTo(ox + stroke[0] * W, oy + stroke[1] * H)
            var i = 2
            while (i + 1 < stroke.size) { path.lineTo(ox + stroke[i] * W, oy + stroke[i + 1] * H); i += 2 }
            c.drawPath(path, strokePaint)
        }
        c.drawText("%.0f FPS".format(fps), 12f, height - 12f, txt)
    }

    private fun addPoint(e: MotionEvent) {
        val s = scale()
        val ox = (width - fw * s) / 2f; val oy = (height - fh * s) / 2f
        stroke.add(((e.x - ox) / (fw * s)).coerceIn(0f, 1f))
        stroke.add(((e.y - oy) / (fh * s)).coerceIn(0f, 1f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scribble) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { stroke.clear(); addPoint(e) }
                MotionEvent.ACTION_MOVE -> addPoint(e)
                MotionEvent.ACTION_UP -> {
                    addPoint(e)
                    val pts = stroke.toFloatArray(); stroke.clear()
                    if (pts.size >= 16) onStroke?.invoke(pts)
                }
                MotionEvent.ACTION_CANCEL -> stroke.clear()
            }
            invalidate()
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        val s = scale()
        val ox = (width - fw * s) / 2f; val oy = (height - fh * s) / 2f
        val nx = (e.x - ox) / (fw * s); val ny = (e.y - oy) / (fh * s)
        if (nx in 0f..1f && ny in 0f..1f) onTap?.invoke(nx, ny)
        performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
