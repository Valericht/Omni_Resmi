package com.omni.vision

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Satu rentang HSV (skala OpenCV). hLo > hHi = hue melingkar. */
class HsvRange(var hLo: Int, var hHi: Int, var sLo: Int, var sHi: Int, var vLo: Int, var vHi: Int) {
    fun toJson(): JSONArray = JSONArray(listOf(hLo, hHi, sLo, sHi, vLo, vHi))
    override fun toString() = "H $hLo-$hHi S $sLo-$sHi V $vLo-$vHi"
    companion object {
        fun fromJson(a: JSONArray) = HsvRange(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3), a.getInt(4), a.getInt(5))
    }
}

/** Konfigurasi satu kelas di profil: banyak rentang (sampling multi-tempat, FR10). */
class ClassCfg(val def: ClassDef) {
    val ranges = ArrayList<HsvRange>()
    var enabled = false
    var minArea = def.minArea
    var arrive = def.arrive
}

/** FR11: satu coretan = satu region dari satu posisi kamera. Koordinat 0..1 terhadap frame; pts = coretan asli (x0,y0,x1,y1,...) maks ~40 titik. */
class Region(val x: Float, val y: Float, val w: Float, val h: Float, val pts: FloatArray, val pose: FloatArray?) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("box", JSONArray(listOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())))
        o.put("pts", JSONArray(pts.map { it.toDouble() }))
        if (pose != null) o.put("pose", JSONArray(pose.map { it.toDouble() }))
        return o
    }
    companion object {
        fun fromJson(o: JSONObject): Region {
            val b = o.getJSONArray("box"); val pa = o.getJSONArray("pts")
            val pts = FloatArray(pa.length()) { pa.getDouble(it).toFloat() }
            val po = o.optJSONArray("pose")
            val pose = if (po != null && po.length() == 3) FloatArray(3) { po.getDouble(it).toFloat() } else null
            return Region(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat(), pts, pose)
        }
    }
}

/** FR11: landmark bernama = kelas + region dari >= LM_MIN_POS posisi kamera robot. */
class Landmark(val name: String, val classId: String) {
    val regions = ArrayList<Region>()
    val valid get() = regions.size >= Tuning.LM_MIN_POS
    companion object {
        /** pose = (x cm, y cm, yaw deg). Beda bila bergeser atau berputar cukup jauh. */
        fun farEnough(a: FloatArray, b: FloatArray): Boolean {
            var dy = (b[2] - a[2]) % 360f
            if (dy > 180f) dy -= 360f
            if (dy < -180f) dy += 360f
            return Math.hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()) >= Tuning.LM_MOVE_CM || Math.abs(dy) >= Tuning.LM_MOVE_DEG
        }
    }
}

/** FR9: profil lapangan per warna tim. */
class Profile(val team: String) {
    val cls = LinkedHashMap<String, ClassCfg>()
    var kp = Tuning.KP
    var forwardRpm = Tuning.FORWARD_RPM
    var maxStrafe = Tuning.MAX_STRAFE_RPM
    var deadband = Tuning.DEADBAND
    var obsSlow = Tuning.OBS_SLOW
    var obsStop = Tuning.OBS_STOP
    var tol = Tuning.SAMPLE_TOL
    var lmMatch = Tuning.LM_MATCH
    var soloBlock = "EARTH"                  // blob blok tanpa pasangan sebanding di frame diperlakukan sebagai ini (EARTH/SKY)
    val landmarks = ArrayList<Landmark>()

    init {
        for (d in Classes.all) {
            val c = ClassCfg(d)
            d.rgb?.let { c.ranges.add(Classes.defaultRange(it)); c.enabled = true }
            cls[d.id] = c
        }
    }

    /** Luas tiba untuk kelas keluaran. Earth/Sky memakai kalibrasi blob warnanya; Sky = (200/350)^2 kali Earth (§2). */
    fun arriveOf(id: String): Float {
        val blob = when (id) { "EARTH_RED", "SKY_RED" -> "BLOCK_RED"; "EARTH_BLUE", "SKY_BLUE" -> "BLOCK_BLUE"; else -> id }
        val a = cls[blob]?.arrive ?: Tuning.STOP_AREA
        return if (id.startsWith("SKY_")) a * Blocks.SKY_RATIO else a
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", 2); o.put("team", team)
        o.put("tuning", JSONObject()
            .put("kp", kp.toDouble()).put("forwardRpm", forwardRpm.toDouble()).put("maxStrafe", maxStrafe.toDouble())
            .put("deadband", deadband.toDouble()).put("obsSlow", obsSlow.toDouble()).put("obsStop", obsStop.toDouble()).put("tol", tol)
            .put("lmMatch", lmMatch.toDouble()).put("soloBlock", soloBlock))
        val cs = JSONObject()
        for ((id, c) in cls) {
            val ra = JSONArray(); c.ranges.forEach { ra.put(it.toJson()) }
            cs.put(id, JSONObject().put("enabled", c.enabled).put("minArea", c.minArea.toDouble()).put("arrive", c.arrive.toDouble()).put("ranges", ra))
        }
        o.put("classes", cs)
        val la = JSONArray()
        for (l in landmarks) {
            val ra = JSONArray(); l.regions.forEach { ra.put(it.toJson()) }
            la.put(JSONObject().put("name", l.name).put("class", l.classId).put("regions", ra))
        }
        o.put("landmarks", la)
        return o
    }

    /** Gabungkan dari JSON: kelas yang tidak ada di file tetap bawaan. */
    fun load(o: JSONObject) {
        o.optJSONObject("tuning")?.let { t ->
            kp = t.optDouble("kp", kp.toDouble()).toFloat()
            forwardRpm = t.optDouble("forwardRpm", forwardRpm.toDouble()).toFloat()
            maxStrafe = t.optDouble("maxStrafe", maxStrafe.toDouble()).toFloat()
            deadband = t.optDouble("deadband", deadband.toDouble()).toFloat()
            obsSlow = t.optDouble("obsSlow", obsSlow.toDouble()).toFloat()
            obsStop = t.optDouble("obsStop", obsStop.toDouble()).toFloat()
            tol = t.optInt("tol", tol)
            lmMatch = t.optDouble("lmMatch", lmMatch.toDouble()).toFloat()
            soloBlock = t.optString("soloBlock", soloBlock)
        }
        landmarks.clear()
        o.optJSONArray("landmarks")?.let { la ->
            for (i in 0 until la.length()) try {
                val j = la.getJSONObject(i)
                val l = Landmark(j.getString("name"), j.getString("class"))
                val ra = j.getJSONArray("regions")
                for (k in 0 until ra.length()) l.regions.add(Region.fromJson(ra.getJSONObject(k)))
                landmarks.add(l)
            } catch (_: Exception) { }
        }
        val cs = o.optJSONObject("classes") ?: return
        for ((id, c) in cls) {
            val j = cs.optJSONObject(id) ?: continue
            c.minArea = j.optDouble("minArea", c.minArea.toDouble()).toFloat()
            c.arrive = j.optDouble("arrive", c.arrive.toDouble()).toFloat()
            val ra = j.optJSONArray("ranges")
            if (ra != null) {
                c.ranges.clear()
                for (i in 0 until ra.length()) c.ranges.add(HsvRange.fromJson(ra.getJSONArray(i)))
            }
            c.enabled = j.optBoolean("enabled", c.ranges.isNotEmpty()) && c.ranges.isNotEmpty()
        }
    }
}

object ProfileStore {
    private fun file(ctx: Context, team: String) = File(ctx.filesDir, "profile_${team.lowercase()}.json")

    fun load(ctx: Context, team: String): Profile {
        val p = Profile(team)
        val f = file(ctx, team)
        if (f.exists()) try { p.load(JSONObject(f.readText())) } catch (_: Exception) { }
        return p
    }

    /** Tulis ke file sementara lalu ganti nama: profil lama tidak rusak bila proses terputus. */
    fun save(ctx: Context, p: Profile): Boolean = try {
        val f = file(ctx, p.team); val tmp = File(f.path + ".tmp")
        tmp.writeText(p.toJson().toString())
        if (f.exists()) f.delete()
        tmp.renameTo(f)
        ctx.getSharedPreferences("omni", Context.MODE_PRIVATE).edit().putString("team", p.team).apply()
        true
    } catch (_: Exception) { false }

    fun lastTeam(ctx: Context): String =
        ctx.getSharedPreferences("omni", Context.MODE_PRIVATE).getString("team", "RED") ?: "RED"

    fun setLastTeam(ctx: Context, team: String) {
        ctx.getSharedPreferences("omni", Context.MODE_PRIVATE).edit().putString("team", team).apply()
    }
}
