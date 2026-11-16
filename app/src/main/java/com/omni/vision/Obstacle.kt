package com.omni.vision

import org.opencv.core.*
import org.opencv.imgproc.Imgproc

enum class ObsLevel { NONE, SLOW, STOP }
class Obs(val level: ObsLevel, val score: Float, val front: Float, val side: Float)

/**
 * FR4 lapis 2: perbedaan frame pada zona depan (bawah-tengah) dibanding zona samping.
 * Gerak sendiri robot mengubah seluruh frame; objek asing yang mendekat mengubah zona depan lebih kuat daripada samping.
 * Monokuler tanpa depth: keputusan dari skor perubahan + kenaikannya (objek membesar). Properti sah (blok) dikecualikan.
 * Lapis 1 (ultrasonik firmware) tetap prioritas; ini hanya SLOW/STOP tambahan.
 */
class ObstacleWatch {
    private val gray = Mat()
    private val prev = Mat()
    private val diff = Mat()
    private val bin = Mat()
    private var havePrev = false
    private var ema = 0f
    private var raised = 0
    private var level = ObsLevel.NONE
    val zoneX0 = 0.25f
    val zoneX1 = 0.75f
    val zoneY0 = 0.40f

    private fun count(r0: Int, r1: Int, c0: Int, c1: Int): Int {
        if (r1 <= r0 || c1 <= c0) return 0
        val s = bin.submat(r0, r1, c0, c1)
        val n = Core.countNonZero(s)
        s.release()
        return n
    }

    fun update(rgba: Mat, legit: List<Det>, slow: Float, stop: Float): Obs {
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        if (!havePrev || prev.rows() != gray.rows() || prev.cols() != gray.cols()) {
            gray.copyTo(prev); havePrev = true
            return Obs(ObsLevel.NONE, 0f, 0f, 0f)
        }
        Core.absdiff(gray, prev, diff)
        gray.copyTo(prev)
        Imgproc.threshold(diff, bin, 25.0, 255.0, Imgproc.THRESH_BINARY)
        val w = bin.cols(); val h = bin.rows()
        for (d in legit) {
            if (d.kind == Kind.BLOCK || d.kind == Kind.BALL) {
                Imgproc.rectangle(bin, Point((d.x * w).toDouble(), (d.y * h).toDouble()), Point(((d.x + d.w) * w).toDouble(), ((d.y + d.h) * h).toDouble()), Scalar(0.0), -1)
            }
        }
        val ys = (zoneY0 * h).toInt(); val x0 = (zoneX0 * w).toInt(); val x1 = (zoneX1 * w).toInt()
        val frontPx = ((h - ys) * (x1 - x0)).coerceAtLeast(1)
        val sidePx = ((h - ys) * (x0 + (w - x1))).coerceAtLeast(1)
        val front = count(ys, h, x0, x1).toFloat() / frontPx
        val side = (count(ys, h, 0, x0) + count(ys, h, x1, w)).toFloat() / sidePx
        val score = (front - 0.5f * side).coerceAtLeast(0f)
        val growth = score - ema
        ema = 0.6f * ema + 0.4f * score
        val want = when {
            score >= stop || (score >= slow && growth >= Tuning.OBS_GROWTH) -> ObsLevel.STOP
            score >= slow -> ObsLevel.SLOW
            else -> ObsLevel.NONE
        }
        // naik butuh 2 frame beruntun (anti-kedip), turun langsung
        if (want.ordinal > level.ordinal) { raised++; if (raised >= 2) { level = want; raised = 0 } } else { level = want; raised = 0 }
        return Obs(level, score, front, side)
    }
}
