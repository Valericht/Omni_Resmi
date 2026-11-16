package com.omni.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Hasil deteksi, semua koordinat dinormalisasi 0..1 terhadap frame. */
class Det(val cls: String, val kind: Kind, val x: Float, val y: Float, val w: Float, val h: Float, val area: Float, val base: String = cls) {
    /** FR11: diisi LmMatch bila deteksi menempati region landmark tersimpan. */
    var landmark: String? = null
    var lmRegion: Region? = null
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    /** galat horizontal -1..1 (negatif = target di kiri) */
    val errX get() = (cx - 0.5f) * 2f
}

/** FR1-FR3 + FR10: HSV -> inRange per kelas -> morfologi -> kontur -> verifikasi luas & aspek. Dipakai hanya dari thread analisis. */
class Detector {
    private val rgb = Mat()
    private val hsv = Mat()
    private val m1 = Mat()
    private val m2 = Mat()
    private val mk = Mat()
    private val hier = Mat()
    private val color = Mat()
    private val bmps = arrayOfNulls<Bitmap>(2)
    private var bi = 0
    private val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    var w = 0; private set
    var h = 0; private set

    /** Panggil sekali per frame (RGBA) sebelum detect/sample. */
    fun prepare(rgba: Mat) {
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        w = rgba.cols(); h = rgba.rows()
    }

    /** OR semua rentang ke out. false bila tidak ada rentang (out = hitam). */
    private fun buildMask(ranges: List<HsvRange>, out: Mat): Boolean {
        if (ranges.isEmpty()) { out.create(h, w, CvType.CV_8UC1); out.setTo(Scalar(0.0)); return false }
        var first = true
        for (r in ranges) {
            if (r.hLo <= r.hHi) {
                Core.inRange(hsv, Scalar(r.hLo.toDouble(), r.sLo.toDouble(), r.vLo.toDouble()), Scalar(r.hHi.toDouble(), r.sHi.toDouble(), r.vHi.toDouble()), m1)
            } else {   // hue melingkar (merah): [hLo..179] atau [0..hHi]
                Core.inRange(hsv, Scalar(r.hLo.toDouble(), r.sLo.toDouble(), r.vLo.toDouble()), Scalar(179.0, r.sHi.toDouble(), r.vHi.toDouble()), m1)
                Core.inRange(hsv, Scalar(0.0, r.sLo.toDouble(), r.vLo.toDouble()), Scalar(r.hHi.toDouble(), r.sHi.toDouble(), r.vHi.toDouble()), m2)
                Core.bitwise_or(m1, m2, m1)
            }
            if (first) { m1.copyTo(out); first = false } else Core.bitwise_or(out, m1, out)
        }
        return true
    }

    /** Deteksi satu kelas (maks 3 terbesar). Mask akhir tertinggal di mk untuk lastMaskBitmap(). */
    fun detectClass(c: ClassCfg): List<Det> {
        buildMask(c.ranges, mk)
        Imgproc.morphologyEx(mk, mk, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(mk, mk, Imgproc.MORPH_CLOSE, kernel)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mk, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val frameArea = (w * h).toDouble().coerceAtLeast(1.0)
        val found = ArrayList<Det>()
        for (ct in contours) {
            val a = (Imgproc.contourArea(ct) / frameArea).toFloat()
            if (a >= c.minArea) {
                val r = Imgproc.boundingRect(ct)
                val asp = r.width.toFloat() / r.height.coerceAtLeast(1)
                // blok: bentuk mendekati persegi (blob terisi >= 60% bounding box)
                val fillOk = c.def.kind != Kind.BLOCK || a * frameArea / (r.width * r.height).coerceAtLeast(1) >= 0.6
                if (fillOk && asp >= c.def.minAspect && asp <= c.def.maxAspect) {
                    found.add(Det(c.def.id, c.def.kind, r.x / w.toFloat(), r.y / h.toFloat(), r.width / w.toFloat(), r.height / h.toFloat(), a))
                }
            }
            ct.release()
        }
        found.sortByDescending { it.area }
        return if (found.size > 3) found.subList(0, 3).toList() else found
    }

    /** Semua kelas aktif. Blok: blob per warna -> klasifikasi Earth/Sky (FR2); zona yang tertutup blok ditekan; lalu pencocokan landmark FR11. */
    fun detect(p: Profile): List<Det> {
        val zones = ArrayList<Det>()
        val blobs = ArrayList<Det>()
        for (c in p.cls.values) {
            if (!c.enabled || c.ranges.isEmpty()) continue
            if (c.def.id == "BLOCK_RED" || c.def.id == "BLOCK_BLUE") blobs.addAll(detectClass(c)) else zones.addAll(detectClass(c))
        }
        val blocks = Blocks.classify(blobs, p.soloBlock)
        val res = ArrayList<Det>(blocks)
        res.addAll(Blocks.suppressZones(zones.filter { it.kind == Kind.LANDMARK }, blocks))
        res.addAll(zones.filter { it.kind != Kind.LANDMARK })
        return LmMatch.annotate(res, p)
    }

    /** Mask terakhir (dari detectClass) sebagai bitmap hijau transparan untuk overlay. Dua buffer bergantian. */
    fun lastMaskBitmap(): Bitmap {
        color.create(h, w, CvType.CV_8UC4)
        color.setTo(Scalar(0.0, 0.0, 0.0, 0.0))
        color.setTo(Scalar(0.0, 255.0, 80.0, 150.0), mk)
        bi = 1 - bi
        var b = bmps[bi]
        if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmps[bi] = b }
        Utils.matToBitmap(color, b)
        return b!!
    }

    /** Rata-rata HSV patch (2*rad+1)^2 di titik normalisasi. Hue dirata-ratakan melingkar. null bila belum ada frame. */
    fun sample(nx: Float, ny: Float, rad: Int = 4): IntArray? {
        if (hsv.empty() || w < 2 * rad + 2 || h < 2 * rad + 2) return null
        val cx = (nx * w).toInt().coerceIn(rad, w - rad - 1)
        val cy = (ny * h).toInt().coerceIn(rad, h - rad - 1)
        val roi = hsv.submat(cy - rad, cy + rad + 1, cx - rad, cx + rad + 1)
        val n = roi.rows() * roi.cols()
        val buf = ByteArray(n * 3)
        roi.get(0, 0, buf)
        roi.release()
        var sx = 0.0; var sy = 0.0; var ss = 0L; var sv = 0L
        for (i in 0 until n) {
            val ang = (buf[i * 3].toInt() and 0xFF) * 2.0 * Math.PI / 180.0
            sx += cos(ang); sy += sin(ang)
            ss += buf[i * 3 + 1].toInt() and 0xFF
            sv += buf[i * 3 + 2].toInt() and 0xFF
        }
        var deg = Math.toDegrees(atan2(sy, sx))
        if (deg < 0) deg += 360.0
        return intArrayOf((deg / 2.0).toInt().coerceIn(0, 179), (ss / n).toInt(), (sv / n).toInt())
    }

    /** HSV dari seluruh piksel mask di dalam r: persentil 5-95 (hue melingkar) + margin. null bila piksel terlalu sedikit. */
    private fun regionRange(mask: Mat, r: Rect): HsvRange? {
        val hs = hsv.submat(r); val ms = mask.submat(r)
        val n = r.width * r.height
        val hb = ByteArray(n * 3); val mb = ByteArray(n)
        hs.get(0, 0, hb); ms.get(0, 0, mb)
        hs.release(); ms.release()
        val hl = ArrayList<Int>(); val sl = ArrayList<Int>(); val vl = ArrayList<Int>()
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) {
            if (mb[i].toInt() == 0) continue
            val hh = hb[i * 3].toInt() and 0xFF
            hl.add(hh); sl.add(hb[i * 3 + 1].toInt() and 0xFF); vl.add(hb[i * 3 + 2].toInt() and 0xFF)
            val ang = hh * 2.0 * Math.PI / 180.0
            sx += cos(ang); sy += sin(ang)
        }
        val cnt = hl.size
        if (cnt < 80) return null
        var md = Math.toDegrees(atan2(sy, sx)); if (md < 0) md += 360.0
        val meanH = (md / 2.0).toInt()
        val rel = hl.map { var d = it - meanH; if (d < -90) d += 180; if (d > 89) d -= 180; d }.sorted()
        val s5 = sl.sorted(); val v5 = vl.sorted()
        val lo = (cnt * 0.05).toInt(); val hi = (cnt * 0.95).toInt().coerceAtMost(cnt - 1)
        val span = rel[hi] - rel[lo] + 6
        val hLo: Int; val hHi: Int
        if (span >= 178) { hLo = 0; hHi = 179 } else {
            hLo = ((meanH + rel[lo] - 3) % 180 + 180) % 180
            hHi = ((meanH + rel[hi] + 3) % 180 + 180) % 180
        }
        return HsvRange(hLo, hHi, (s5[lo] - 15).coerceAtLeast(0), (s5[hi] + 15).coerceAtMost(255),
            (v5[lo] - 15).coerceAtLeast(0), (v5[hi] + 15).coerceAtMost(255))
    }

    /** FR11: coretan bebas (x0,y0,x1,y1,... normalisasi) -> hull cembung = region; sampling HSV dari seluruh piksel region. Hasil: rentang + kotak [x,y,w,h] normalisasi. */
    fun regionFromStroke(pts: FloatArray): Pair<HsvRange, FloatArray>? {
        val n = pts.size / 2
        if (n < 8 || w < 2 || h < 2) return null
        val pl = List(n) { Point((pts[2 * it] * w).toDouble().coerceIn(0.0, w - 1.0), (pts[2 * it + 1] * h).toDouble().coerceIn(0.0, h - 1.0)) }
        val mp = MatOfPoint(); mp.fromList(pl)
        val idx = MatOfInt(); Imgproc.convexHull(mp, idx)
        val hp = idx.toArray().map { pl[it] }
        val hm = MatOfPoint(); hm.fromList(hp)
        val mask = Mat.zeros(h, w, CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, hm, Scalar(255.0))
        val r = Imgproc.boundingRect(hm)
        val range = if (r.width >= 4 && r.height >= 4) regionRange(mask, r) else null
        mask.release(); mp.release(); idx.release(); hm.release()
        if (range == null) return null
        return Pair(range, floatArrayOf(r.x / w.toFloat(), r.y / h.toFloat(), r.width / w.toFloat(), r.height / h.toFloat()))
    }
}
