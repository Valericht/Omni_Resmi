package com.omni.vision

/**
 * FR2: klasifikasi Earth/Sky + NMS antar kelas. Murni logika (tanpa OpenCV).
 * Earth 350 mm vs Sky 200 mm (§2, +-5% §13.2): pada jarak sama luas Sky ~ (200/350)^2 = 0,33 luas Earth.
 * "Jarak sama" didekati dengan tepi bawah blob yang sejajar di frame (lantai datar). Blob tanpa pasangan sebanding -> Profile.soloBlock
 * (asumsi agent). Sisi warna Sky = warna blob yang terlihat (merah/biru).
 */
object Blocks {
    const val SKY_RATIO = 0.33f
    const val PAIR_LO = 0.2f          // rasio luas kecil/besar yang dianggap pasangan Sky/Earth
    const val PAIR_HI = 0.5f
    const val SAME_DIST = 0.12f       // selisih tepi bawah (fraksi tinggi frame) agar dianggap jarak sama
    const val NMS_OVERLAP = 0.5f      // irisan / luas bbox terkecil

    private fun inter(a: Det, b: Det): Float {
        val ix = maxOf(0f, minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x))
        val iy = maxOf(0f, minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y))
        return ix * iy
    }

    fun overlap(a: Det, b: Det): Float {
        val d = minOf(a.w * a.h, b.w * b.h)
        return if (d <= 0f) 0f else inter(a, b) / d
    }

    /** blobs = keluaran detectClass untuk BLOCK_RED / BLOCK_BLUE (cls = id blob). Hasil: EARTH_x / SKY_x, satu blob satu kelas, tanpa tumpang tindih antar blok. */
    fun classify(blobs: List<Det>, solo: String): List<Det> {
        val label = HashMap<Det, String>()
        for (base in blobs.map { it.base }.distinct()) {
            val g = blobs.filter { it.base == base }.sortedByDescending { it.area }
            for (i in g.indices) for (j in i + 1 until g.size) {
                if (Math.abs((g[i].y + g[i].h) - (g[j].y + g[j].h)) > SAME_DIST) continue
                val r = g[j].area / g[i].area
                if (r >= PAIR_LO && r <= PAIR_HI) {
                    if (label[g[i]] == null) label[g[i]] = "EARTH"
                    label[g[j]] = "SKY"
                }
            }
        }
        val out = ArrayList<Det>()
        for (b in blobs) {
            val kind = label[b] ?: solo
            val color = if (b.base.endsWith("RED")) "RED" else "BLUE"
            out.add(Det(kind + "_" + color, Kind.BLOCK, b.x, b.y, b.w, b.h, b.area, b.base))
        }
        // satu blob = satu kelas: bila dua keluaran menumpuk, Earth menang, lalu yang lebih luas
        val ordered = out.sortedWith(compareBy<Det>({ if (it.cls.startsWith("EARTH")) 0 else 1 }, { -it.area }))
        val keep = ArrayList<Det>()
        for (d in ordered) if (keep.none { overlap(it, d) >= NMS_OVERLAP }) keep.add(d)
        return keep
    }

    /** Prioritas blok > zona: zona yang sebagian besar tertutup blok (mis. START_RED di atas kotak Earth) dibuang. */
    fun suppressZones(zones: List<Det>, blocks: List<Det>): List<Det> =
        zones.filter { z ->
            val za = z.w * z.h
            za <= 0f || blocks.none { b -> inter(z, b) / za >= NMS_OVERLAP }
        }
}

/** FR11: kenali deteksi sebagai landmark tersimpan bila menempati region tersimpan (kelas sama, landmark valid >= 2 posisi). */
object LmMatch {
    private fun overlap(d: Det, r: Region): Float {
        val ix = maxOf(0f, minOf(d.x + d.w, r.x + r.w) - maxOf(d.x, r.x))
        val iy = maxOf(0f, minOf(d.y + d.h, r.y + r.h) - maxOf(d.y, r.y))
        val m = minOf(d.w * d.h, r.w * r.h)
        return if (m <= 0f) 0f else ix * iy / m
    }

    /** Mengisi Det.landmark / Det.lmRegion (region dengan irisan terbesar >= p.lmMatch). Mengembalikan daftar yang sama. */
    fun annotate(dets: List<Det>, p: Profile): List<Det> {
        if (p.landmarks.none { it.valid }) return dets
        for (d in dets) {
            var best = p.lmMatch
            for (lm in p.landmarks) {
                if (!lm.valid || lm.classId != d.base) continue
                for (r in lm.regions) {
                    val o = overlap(d, r)
                    if (o >= best) { best = o; d.landmark = lm.name; d.lmRegion = r }
                }
            }
        }
        return dets
    }
}
