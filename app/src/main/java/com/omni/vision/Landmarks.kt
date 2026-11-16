package com.omni.vision

import android.graphics.Color

enum class Kind { LANDMARK, BLOCK, BALL, ROBOT }

/** Definisi kelas deteksi. rgb = null -> warna belum ditetapkan rulebook ("per Appendix, belum tersedia"): wajib dikalibrasi di mode Training. */
class ClassDef(
    val id: String, val label: String, val kind: Kind, val rgb: IntArray?, val pasal: String,
    val minArea: Float, val minAspect: Float, val maxAspect: Float,   // rasio luas layar; aspek = lebar/tinggi bounding box
    val arrive: Float,                                                // rasio luas saat dianggap tiba (bawaan, dikalibrasi lewat "Set area tiba")
    val color: Int
)

object Classes {
    val all: List<ClassDef> = listOf(
        ClassDef("GROUND_RED", "Ground/Storage Merah", Kind.LANDMARK, intArrayOf(240, 210, 210), "§14", 0.03f, 0f, 99f, 0.30f, Color.rgb(255, 130, 130)),
        ClassDef("GROUND_BLUE", "Ground/Storage Biru", Kind.LANDMARK, intArrayOf(170, 210, 230), "§14", 0.03f, 0f, 99f, 0.30f, Color.rgb(130, 190, 255)),
        ClassDef("TRANSFER_RED", "Transfer Area Merah", Kind.LANDMARK, intArrayOf(245, 170, 60), "§14", 0.01f, 0f, 99f, 0.25f, Color.rgb(255, 170, 40)),
        ClassDef("TRANSFER_BLUE", "Transfer Area Biru", Kind.LANDMARK, intArrayOf(60, 170, 245), "§14", 0.01f, 0f, 99f, 0.25f, Color.rgb(40, 170, 255)),
        ClassDef("SPOT", "Building Spot", Kind.LANDMARK, intArrayOf(40, 100, 50), "§14", 0.005f, 0f, 99f, 0.25f, Color.rgb(60, 200, 90)),
        ClassDef("SHARED", "Shared Area", Kind.LANDMARK, intArrayOf(245, 240, 200), "§14", 0.02f, 0f, 99f, 0.30f, Color.rgb(250, 240, 150)),
        ClassDef("START_RED", "Start/Retry Merah", Kind.LANDMARK, intArrayOf(223, 34, 34), "§14", 0.01f, 0f, 99f, 0.25f, Color.rgb(230, 40, 40)),
        ClassDef("START_BLUE", "Start/Retry Biru", Kind.LANDMARK, intArrayOf(50, 0, 255), "§14", 0.01f, 0f, 99f, 0.25f, Color.rgb(80, 60, 255)),
        // Properti (FR2): blob per warna, lalu diklasifikasi Earth/Sky (Blocks.kt). Warna per Appendix, belum tersedia -> tanpa HSV bawaan, kalibrasi di Training
        ClassDef("BLOCK_RED", "Blok merah (Earth/Sky)", Kind.BLOCK, null, "§14: per Appendix", 0.003f, 0.6f, 1.7f, 0.20f, Color.rgb(255, 80, 80)),
        ClassDef("BLOCK_BLUE", "Blok biru (Earth/Sky)", Kind.BLOCK, null, "§14: per Appendix", 0.003f, 0.6f, 1.7f, 0.20f, Color.rgb(80, 120, 255)),
        ClassDef("MUSTIKA", "Mustika (bola voli)", Kind.BALL, null, "§14: warna asli bola", 0.003f, 0.7f, 1.4f, 0.20f, Color.rgb(255, 230, 60)),
        // FR3: robot lawan, hanya informasi penghindaran. Bentuk: blob besar (bukan kubus kecil) - (asumsi agent)
        ClassDef("ROBOT_LAWAN", "Robot lawan", Kind.ROBOT, null, "FR3", 0.03f, 0f, 99f, 0f, Color.rgb(255, 0, 255))
    )
    /** Kelas keluaran klasifikasi FR2 (tanpa HSV sendiri; hanya label/warna overlay). */
    val outputs: List<ClassDef> = listOf(
        ClassDef("EARTH_RED", "Earth Block merah", Kind.BLOCK, null, "§2 350 mm", 0f, 0f, 99f, 0.20f, Color.rgb(255, 120, 60)),
        ClassDef("EARTH_BLUE", "Earth Block biru", Kind.BLOCK, null, "§2 350 mm", 0f, 0f, 99f, 0.20f, Color.rgb(60, 140, 255)),
        ClassDef("SKY_RED", "Sky Block sisi merah", Kind.BLOCK, null, "§2 200 mm", 0f, 0f, 99f, 0.07f, Color.rgb(255, 60, 120)),
        ClassDef("SKY_BLUE", "Sky Block sisi biru", Kind.BLOCK, null, "§2 200 mm", 0f, 0f, 99f, 0.07f, Color.rgb(60, 220, 255))
    )
    private val map = (all + outputs).associateBy { it.id }
    fun get(id: String): ClassDef? = map[id]

    /** RGB 0..255 -> HSV skala OpenCV (H 0..179, S/V 0..255). */
    fun rgbToCvHsv(r: Int, g: Int, b: Int): IntArray {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = (mx - mn).toFloat()
        var h = 0f
        if (d > 0f) {
            h = when (mx) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
        }
        if (h < 0f) h += 360f
        val s = if (mx == 0) 0f else d / mx * 255f
        return intArrayOf((h / 2f).toInt().coerceIn(0, 179), s.toInt().coerceIn(0, 255), mx)
    }

    /** Titik awal dari RGB rulebook. Nilai final ditetapkan lewat Training (FR10). */
    fun defaultRange(rgb: IntArray): HsvRange {
        val (h, s, v) = rgbToCvHsv(rgb[0], rgb[1], rgb[2])
        val th = if (s < 40) 15 else 10
        var lo = h - th; var hi = h + th
        if (lo < 0) lo += 180
        if (hi > 179) hi -= 180
        val sLo = if (s < 80) maxOf(8, s - 25) else maxOf(0, s - 70)
        return HsvRange(lo, hi, sLo, minOf(255, s + 70), maxOf(0, v - 70), 255)
    }

    /** Range dari sampel HSV (h, s, v). tol = toleransi S/V; toleransi H = tol/4. hLo > hHi berarti hue melingkar (merah). */
    fun rangeFrom(s: IntArray, tol: Int): HsvRange {
        val th = (tol / 4).coerceIn(5, 20)
        var lo = s[0] - th; var hi = s[0] + th
        if (lo < 0) lo += 180
        if (hi > 179) hi -= 180
        return HsvRange(lo, hi, (s[1] - tol).coerceAtLeast(0), (s[1] + tol).coerceAtMost(255), (s[2] - tol).coerceAtLeast(0), (s[2] + tol).coerceAtMost(255))
    }
}
