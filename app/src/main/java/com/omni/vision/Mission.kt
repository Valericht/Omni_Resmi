package com.omni.vision

import kotlin.math.abs

class Cmd(val vx: Float, val vy: Float, val w: Float) {
    fun text() = "VX:${vx.toInt()},VY:${vy.toInt()},W:${w.toInt()}"
    fun scaled(k: Float) = Cmd(vx * k, vy * k, w * k)
    companion object { val ZERO = Cmd(0f, 0f, 0f) }
}

/** Navigasi P-controller menuju satu deteksi. Heading dijaga firmware (heading lock IMU aktif saat W = 0 dan robot bergerak). */
object Nav {
    class Step(val cmd: Cmd, val visible: Boolean, val arrived: Boolean)

    /** Terbesar di antara kelas yang dicari; deteksi yang dikenali sebagai landmark tersimpan (FR11) didahulukan. */
    fun pick(dets: List<Det>, ids: Set<String>): Det? {
        val c = dets.filter { it.cls in ids }
        return c.filter { it.landmark != null }.maxByOrNull { it.area } ?: c.maxByOrNull { it.area }
    }

    /** Landmark bernama (FR11). */
    fun pickLandmark(dets: List<Det>, name: String): Det? = dets.filter { it.landmark == name }.maxByOrNull { it.area }

    /** galat horizontal -> VY; luas (proksi jarak) -> VX; tiba bila luas >= arrive dan target hampir di tengah. */
    fun goTo(t: Det?, arrive: Float, p: Profile): Step {
        if (t == null) return Step(Cmd.ZERO, false, false)
        val e = if (abs(t.errX) < p.deadband) 0f else t.errX
        val vy = (p.kp * e).coerceIn(-p.maxStrafe, p.maxStrafe)
        if (t.area >= arrive) return Step(Cmd(0f, vy, 0f), true, abs(t.errX) < 0.2f)
        val slow = (1f - t.area / arrive).coerceIn(0.25f, 1f)
        val align = if (abs(e) > 0.4f) 0.3f else 1f
        return Step(Cmd(p.forwardRpm * slow * align, vy, 0f), true, false)
    }
}

enum class Phase(val label: String) {
    IDLE("IDLE"), GOTO_STORAGE("GOTO_STORAGE"), PICK("PICK"), GOTO_TRANSFER("GOTO_TRANSFER"), PARK("PARK"), DONE("SELESAI")
}

/**
 * FR5: state machine misi TR. IDLE -> GOTO_STORAGE -> PICK (konfirmasi grip) -> GOTO_TRANSFER -> PARK -> loop, maks 3 blok (§3.4.3).
 * Protokol gripper belum ada: PICK dan PARK menunggu tombol KONFIRMASI dari operator (asumsi agent).
 * Waktu pertandingan 3 menit (§4.2.2): lewat itu -> SELESAI.
 */
class Mission(val maxBlocks: Int = 3) {
    class Out(val cmd: Cmd, val note: String)

    @Volatile var phase = Phase.IDLE; private set
    @Volatile var blocks = 0; private set
    @Volatile private var phaseStart = 0L
    @Volatile private var missionStart = 0L
    @Volatile private var confirmed = false
    val carrying get() = phase == Phase.GOTO_TRANSFER || phase == Phase.PARK

    fun start(now: Long) { blocks = 0; missionStart = now; enter(Phase.GOTO_STORAGE, now) }
    fun reset() { phase = Phase.IDLE; blocks = 0; confirmed = false }
    fun confirm() { confirmed = true }
    fun phaseMs(now: Long) = if (phase == Phase.IDLE) 0L else now - phaseStart
    fun totalMs(now: Long) = if (phase == Phase.IDLE) 0L else now - missionStart

    private fun enter(p: Phase, now: Long) { phase = p; phaseStart = now; confirmed = false }

    private fun go(dets: List<Det>, id: String, p: Profile): Nav.Step {
        val d = Nav.pick(dets, setOf(id))
        return Nav.goTo(d, d?.let { p.arriveOf(it.cls) } ?: Tuning.STOP_AREA, p)
    }

    fun step(now: Long, dets: List<Det>, team: String, p: Profile): Out {
        if (phase != Phase.IDLE && phase != Phase.DONE && totalMs(now) >= Tuning.MATCH_MS) {
            enter(Phase.DONE, now); return Out(Cmd.ZERO, "waktu 3 menit habis")
        }
        return when (phase) {
            Phase.IDLE, Phase.DONE -> Out(Cmd.ZERO, "")
            Phase.GOTO_STORAGE -> {
                val s = go(dets, "GROUND_$team", p)
                if (s.arrived) enter(Phase.PICK, now)
                Out(s.cmd, if (s.visible) "" else "Storage tidak terlihat")
            }
            Phase.PICK -> {
                if (confirmed) enter(Phase.GOTO_TRANSFER, now)
                Out(Cmd.ZERO, "tunggu KONFIRMASI grip")
            }
            Phase.GOTO_TRANSFER -> {
                val s = go(dets, "TRANSFER_$team", p)
                if (s.arrived) enter(Phase.PARK, now)
                Out(s.cmd, if (s.visible) "" else "Transfer tidak terlihat")
            }
            Phase.PARK -> {
                if (confirmed) {
                    blocks++
                    if (blocks >= maxBlocks) enter(Phase.DONE, now) else enter(Phase.GOTO_STORAGE, now)
                }
                Out(Cmd.ZERO, "tunggu KONFIRMASI blok dilepas")
            }
        }
    }
}
