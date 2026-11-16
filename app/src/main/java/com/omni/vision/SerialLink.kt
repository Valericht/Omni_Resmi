package com.omni.vision

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject

/** Telemetri ringkas firmware -> HP (FR6): {"pose":[x,y,yaw],"us":[a,b,c],"spd":..,"safety":..,"batt":..,"pl":0|1}. us 999 = tak terbaca. */
class HpTelemetry(
    val x: Float, val y: Float, val yaw: Float, val us: IntArray,
    val spd: Float, val safety: Int, val batt: Float, val poseLost: Boolean, val rxMs: Long
) {
    companion object {
        fun parse(line: String): HpTelemetry? = try {
            val o = JSONObject(line)
            val p = o.getJSONArray("pose"); val u = o.getJSONArray("us")
            HpTelemetry(
                p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat(),
                intArrayOf(u.getInt(0), u.getInt(1), u.getInt(2)),
                o.optDouble("spd", 0.0).toFloat(), o.optInt("safety", 0), o.optDouble("batt", -1.0).toFloat(),
                o.optInt("pl", 0) != 0, System.currentTimeMillis()
            )
        } catch (_: Exception) { null }
    }
}

class SerialLink(private val ctx: Context) {
    @Volatile private var port: UsbSerialPort? = null
    val connected get() = port != null
    @Volatile var onTelemetry: ((HpTelemetry) -> Unit)? = null

    // Mengembalikan null bila siap, atau pesan galat
    fun open(): String? {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).firstOrNull()
            ?: return "perangkat USB tidak ditemukan"
        val conn = mgr.openDevice(driver.device) ?: return "izin USB belum diberikan"
        return try {
            val p = driver.ports[0]
            p.open(conn)
            p.setParameters(Tuning.BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            startReader(p)
            null
        } catch (e: Exception) { port = null; e.message ?: "gagal membuka port" }
    }

    /** Baca baris dari firmware; baris '{' = telemetri ringkas. Berhenti bila port diganti/ditutup/dicabut. */
    private fun startReader(p: UsbSerialPort) {
        Thread {
            val buf = ByteArray(512)
            val sb = StringBuilder()
            while (port === p) {
                try {
                    val n = p.read(buf, 100)
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '\n') {
                            if (sb.isNotEmpty() && sb[0] == '{') HpTelemetry.parse(sb.toString())?.let { onTelemetry?.invoke(it) }
                            sb.setLength(0)
                        } else if (c != '\r' && sb.length < 512) sb.append(c)
                    }
                } catch (_: Exception) { if (port === p) port = null; break }
            }
        }.also { it.isDaemon = true; it.name = "serialRx" }.start()
    }

    fun hasPermission(): Boolean {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val d = UsbSerialProber.getDefaultProber().findAllDrivers(mgr).firstOrNull() ?: return false
        return mgr.hasPermission(d.device)
    }

    fun deviceOrNull() = (ctx.getSystemService(Context.USB_SERVICE) as UsbManager)
        .let { UsbSerialProber.getDefaultProber().findAllDrivers(it).firstOrNull()?.device }

    @Synchronized
    fun send(cmd: String) {
        try { port?.write((cmd + "\n").toByteArray(), 50) } catch (_: Exception) { port = null }
    }

    fun close() { val p = port; port = null; try { p?.close() } catch (_: Exception) {} }
}
