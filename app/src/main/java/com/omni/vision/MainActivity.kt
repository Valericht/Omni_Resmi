package com.omni.vision

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.util.Size as ASize
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Mode { MANUAL, ASSIST, AUTO, TRAINING }

/**
 * OmniVision (PRD): FR7 monitoring, FR8 kontrol mode, FR9 profil, FR10 training, FR1-FR5 deteksi + navigasi, FR6 protokol dua arah.
 * Thread: UI | analisis kamera (exec) | pengirim 20 Hz (ticker) | pembaca serial (di SerialLink).
 * Perubahan profil dari UI selalu lewat antrean edits dan dikerjakan di thread analisis (tanpa kunci).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var txtTelem: TextView
    private lateinit var txtLog: TextView
    private lateinit var txtRange: TextView
    private lateinit var txtLm: TextView
    private lateinit var etLm: EditText
    private lateinit var btnScribble: Button
    private lateinit var joyMove: JoystickView
    private lateinit var joyTurn: JoystickView
    private lateinit var panelTrain: View
    private lateinit var panelAuto: View
    private lateinit var panelManual: View
    private lateinit var btnUsb: Button
    private lateinit var btnManual: Button
    private lateinit var btnAssist: Button
    private lateinit var btnAuto: Button
    private lateinit var btnTrain: Button
    private lateinit var btnTeam: Button
    private lateinit var btnCaps: Button
    private lateinit var btnRun: Button
    private lateinit var btnConfirm: Button
    private lateinit var btnStop: Button
    private lateinit var btnSample: Button
    private lateinit var btnTest: Button
    private lateinit var spClass: Spinner
    private lateinit var spTarget: Spinner
    private lateinit var spMission: Spinner
    private lateinit var sbs: List<SeekBar>      // hLo, hHi, sLo, sHi, vLo, vHi
    private lateinit var sbTol: SeekBar

    private lateinit var link: SerialLink
    private val detector by lazy { Detector() }
    private val watch by lazy { ObstacleWatch() }
    private val mission = Mission()
    private val exec = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var mode = Mode.MANUAL
    @Volatile private var team = "RED"
    @Volatile private var running = false
    @Volatile private var estop = false
    @Volatile private var caps = true
    @Volatile private var profile = Profile("RED")
    @Volatile private var telem: HpTelemetry? = null
    @Volatile private var autoCmd = Cmd.ZERO
    @Volatile private var autoCmdMs = 0L
    @Volatile private var autoNote = ""
    @Volatile private var obs = Obs(ObsLevel.NONE, 0f, 0f, 0f)
    @Volatile private var obsMs = 0L
    @Volatile private var trainClass = Classes.all[0].id
    @Volatile private var sampling = false
    @Volatile private var showAll = false
    @Volatile private var tapReq: FloatArray? = null
    @Volatile private var arriveReq = false
    @Volatile private var targetKey = "TRANSFER"
    @Volatile private var missionFull = true
    @Volatile private var fps = 0f
    private var uiSync = false
    private var lastFrameMs = 0L
    private var lastKeep = 0L
    private var lastConnTry = 0L
    private var active = false
    private val edits = ConcurrentLinkedQueue<(Profile) -> Unit>()
    private val logLines = ArrayDeque<String>()

    private val baseKeys = listOf("STORAGE", "TRANSFER", "SPOT", "BLOCK")
    private val baseLabels = listOf("Target: Storage", "Target: Transfer", "Target: Building Spot", "Target: blok terdekat")
    @Volatile private var targetKeys: List<String> = baseKeys      // + "LM:<nama>" untuk landmark valid (FR11)

    /** FR11: coretan dari UI, diproses di thread analisis (butuh frame HSV terakhir). */
    private class StrokeReq(val name: String, val cls: String, val pts: FloatArray, val pose: FloatArray?)
    @Volatile private var strokeReq: StrokeReq? = null

    private val usbAction = "com.omni.vision.USB_PERMISSION"
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { connectUsb() }
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else log("izin kamera ditolak")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        preview.scaleType = PreviewView.ScaleType.FIT_CENTER
        link = SerialLink(this)
        link.onTelemetry = { telem = it }
        if (!OpenCVLoader.initLocal()) log("OpenCV gagal dimuat")

        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(usbAction), ContextCompat.RECEIVER_NOT_EXPORTED)

        team = ProfileStore.lastTeam(this)
        profile = ProfileStore.load(this, team)          // FR9: profil terakhir dimuat otomatis
        btnTeam.text = teamText()
        log("profil ${teamText()} dimuat")

        setupButtons()
        setupSpinners()
        setupTraining()
        refreshLandmarks()
        setMode(Mode.MANUAL)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else camPerm.launch(Manifest.permission.CAMERA)

        ticker.scheduleAtFixedRate({ tick() }, 100L, Tuning.SEND_PERIOD_MS, TimeUnit.MILLISECONDS)
        ui.post(object : Runnable { override fun run() { updateText(); ui.postDelayed(this, 200L) } })
    }

    // ---------------------------------------------------------------- tampilan
    private fun bindViews() {
        preview = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        txtTelem = findViewById(R.id.txtTelem)
        txtLog = findViewById(R.id.txtLog)
        txtRange = findViewById(R.id.txtRange)
        txtLm = findViewById(R.id.txtLm)
        etLm = findViewById(R.id.etLm)
        btnScribble = findViewById(R.id.btnScribble)
        joyMove = findViewById(R.id.joyMove)
        joyTurn = findViewById(R.id.joyTurn)
        joyTurn.lockY = true
        panelTrain = findViewById(R.id.panelTrain)
        panelAuto = findViewById(R.id.panelAuto)
        panelManual = findViewById(R.id.panelManual)
        btnUsb = findViewById(R.id.btnUsb)
        btnManual = findViewById(R.id.btnManual)
        btnAssist = findViewById(R.id.btnAssist)
        btnAuto = findViewById(R.id.btnAuto)
        btnTrain = findViewById(R.id.btnTrain)
        btnTeam = findViewById(R.id.btnTeam)
        btnCaps = findViewById(R.id.btnCaps)
        btnRun = findViewById(R.id.btnRun)
        btnConfirm = findViewById(R.id.btnConfirm)
        btnStop = findViewById(R.id.btnStop)
        btnSample = findViewById(R.id.btnSample)
        btnTest = findViewById(R.id.btnTest)
        spClass = findViewById(R.id.spClass)
        spTarget = findViewById(R.id.spTarget)
        spMission = findViewById(R.id.spMission)
        sbs = listOf(R.id.sbHLo, R.id.sbHHi, R.id.sbSLo, R.id.sbSHi, R.id.sbVLo, R.id.sbVHi).map { findViewById<SeekBar>(it) }
        sbTol = findViewById(R.id.sbTol)
    }

    private fun teamText() = if (team == "RED") "TIM: MERAH" else "TIM: BIRU"

    private fun setupButtons() {
        btnUsb.setOnClickListener { connectUsb() }
        btnManual.setOnClickListener { setMode(Mode.MANUAL) }
        btnAssist.setOnClickListener { setMode(Mode.ASSIST) }
        btnAuto.setOnClickListener { setMode(Mode.AUTO) }
        btnTrain.setOnClickListener { setMode(Mode.TRAINING) }
        btnTeam.setOnClickListener { switchTeam() }
        btnCaps.setOnClickListener { caps = !caps; btnCaps.text = if (caps) "CAPS: ON" else "CAPS: OFF" }
        btnRun.setOnClickListener { toggleRun() }
        btnConfirm.setOnClickListener { mission.confirm(); log("konfirmasi operator") }
        // FR8: STOP darurat. Ketuk = STOP dan tahan; tahan lama = lepas.
        btnStop.setOnClickListener {
            if (!estop) engageStop() else log("STOP aktif: tahan lama tombol STOP untuk melepas")
        }
        btnStop.setOnLongClickListener { if (estop) releaseStop(); true }
    }

    private fun setupSpinners() {
        spMission.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Misi TR penuh", "Ke target saja"))
        spMission.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { missionFull = pos == 0; running = false; mission.reset() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, baseLabels)
        spTarget.setSelection(1)
        spTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { targetKeys.getOrNull(pos)?.let { targetKey = it } }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spClass.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Classes.all.map { it.label })
        spClass.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { trainClass = Classes.all[pos].id; refreshSliders() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** FR10: sampling sentuh, slider H/S/V, preview mask, uji deteksi, set area tiba, simpan profil. */
    private fun setupTraining() {
        overlay.onTap = { nx, ny -> if (mode == Mode.TRAINING && sampling) tapReq = floatArrayOf(nx, ny) }
        btnSample.setOnClickListener {
            sampling = !sampling
            if (sampling) setScribble(false)
            btnSample.text = if (sampling) "SAMPLING: ON (ketuk layar)" else "SAMPLING: OFF"
        }
        // FR11: coretan bebas di atas area -> region + sampling HSV seluruh piksel -> landmark bernama
        btnScribble.setOnClickListener { setScribble(!overlay.scribble) }
        overlay.onStroke = { pts ->
            if (mode == Mode.TRAINING) {
                val t = telem
                val pose = if (t != null && System.currentTimeMillis() - t.rxMs <= 1000L) floatArrayOf(t.x, t.y, t.yaw) else null
                strokeReq = StrokeReq(lmName(), trainClass, pts, pose)
            }
        }
        findViewById<Button>(R.id.btnLmDel).setOnClickListener {
            val name = lmName()
            edits.add { p -> log(if (p.landmarks.removeAll { it.name == name }) "landmark $name dihapus" else "landmark $name tidak ada"); refreshLandmarks() }
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            val id = trainClass
            edits.add { p -> p.cls[id]?.let { it.ranges.clear(); it.enabled = false }; refreshSliders() }
        }
        btnTest.setOnClickListener {
            showAll = !showAll
            btnTest.text = if (showAll) "UJI DETEKSI: ON" else "UJI DETEKSI: OFF"
        }
        findViewById<Button>(R.id.btnArrive).setOnClickListener { arriveReq = true }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            edits.add { p -> log(if (ProfileStore.save(this, p)) "profil ${p.team} tersimpan" else "GAGAL menyimpan profil") }
        }
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser && !uiSync) applySliders() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sbs.forEach { it.setOnSeekBarChangeListener(l) }
        sbTol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser && !uiSync) edits.add { p -> p.tol = v } }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun lmName(): String = etLm.text.toString().replace(Regex("[^A-Za-z0-9_-]"), "").take(16).ifEmpty { "LM1" }

    private fun setScribble(on: Boolean) {
        overlay.scribble = on
        if (on) { sampling = false; btnSample.text = "SAMPLING: OFF" }
        btnScribble.text = if (on) "CORET: ON (gambar di layar)" else "CORET: OFF"
    }

    /** Ringkasan landmark di panel Training + daftar target AUTO (landmark valid saja). Aman dipanggil dari thread mana pun. */
    private fun refreshLandmarks() {
        val p = profile
        val lms = ArrayList(p.landmarks)
        val text = if (lms.isEmpty()) "(belum ada landmark)\nsampling >= ${Tuning.LM_MIN_POS} posisi robot" else
            lms.joinToString("\n") { "${it.name} [${it.classId}] ${it.regions.size}/${Tuning.LM_MIN_POS}" + if (it.valid) "" else " belum valid" }
        val keys = ArrayList(baseKeys); val labels = ArrayList(baseLabels)
        for (l in lms) if (l.valid) { keys.add("LM:${l.name}"); labels.add("Landmark: ${l.name}") }
        runOnUiThread {
            txtLm.text = text
            targetKeys = keys
            spTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val i = keys.indexOf(targetKey)
            spTarget.setSelection(if (i >= 0) i else 1)
        }
    }

    private fun applySliders() {
        val v = sbs.map { it.progress }
        val id = trainClass
        edits.add { p ->
            p.cls[id]?.let { c ->
                if (c.ranges.isEmpty()) c.ranges.add(HsvRange(0, 179, 0, 255, 0, 255))
                val r = c.ranges.last()
                r.hLo = v[0]; r.hHi = v[1]; r.sLo = v[2]; r.sHi = v[3]; r.vLo = v[4]; r.vHi = v[5]
                c.enabled = true
            }
            refreshSliders()
        }
    }

    private fun refreshSliders() = runOnUiThread {
        val c = profile.cls[trainClass] ?: return@runOnUiThread
        val r = c.ranges.lastOrNull()
        val vals = if (r != null) intArrayOf(r.hLo, r.hHi, r.sLo, r.sHi, r.vLo, r.vHi) else IntArray(6)
        uiSync = true
        sbs.forEachIndexed { i, sb -> sb.progress = vals[i] }
        sbTol.progress = profile.tol
        uiSync = false
        txtRange.text = String.format(Locale.US, "%s  %s\nrentang: %d  %s  tiba: %.0f%%\n%s",
            c.def.label, c.def.pasal, c.ranges.size, if (c.enabled) "AKTIF" else "nonaktif", c.arrive * 100f,
            r?.toString() ?: "(kosong: SAMPLING ON lalu ketuk objek)")
    }

    private fun setMode(m: Mode) {
        if (m != mode) { running = false; mission.reset(); link.send("STOP") }
        mode = m
        sampling = false; btnSample.text = "SAMPLING: OFF"
        setScribble(false)
        panelTrain.visibility = if (m == Mode.TRAINING) View.VISIBLE else View.GONE
        panelManual.visibility = if (m == Mode.MANUAL || m == Mode.ASSIST) View.VISIBLE else View.GONE
        panelAuto.visibility = if (m == Mode.AUTO) View.VISIBLE else View.GONE
        overlay.zone = if (m == Mode.ASSIST || m == Mode.AUTO) floatArrayOf(watch.zoneX0, watch.zoneX1, watch.zoneY0) else null
        btnManual.alpha = if (m == Mode.MANUAL) 1f else 0.5f
        btnAssist.alpha = if (m == Mode.ASSIST) 1f else 0.5f
        btnAuto.alpha = if (m == Mode.AUTO) 1f else 0.5f
        btnTrain.alpha = if (m == Mode.TRAINING) 1f else 0.5f
        if (m == Mode.TRAINING) refreshSliders()
        refreshRun()
    }

    private fun switchTeam() {
        running = false; mission.reset(); link.send("STOP")
        team = if (team == "RED") "BLUE" else "RED"
        profile = ProfileStore.load(this, team)
        ProfileStore.setLastTeam(this, team)
        btnTeam.text = teamText()
        refreshSliders()
        refreshLandmarks()
        log("profil ${teamText()} dimuat")
    }

    private fun refreshRun() = runOnUiThread {
        btnRun.text = if (running) "BERHENTI" else "MULAI"
        btnStop.text = if (estop) "STOP AKTIF" else "STOP"
    }

    private fun engageStop() { estop = true; running = false; mission.reset(); link.send("STOP"); log("STOP DARURAT aktif"); refreshRun() }
    private fun releaseStop() { estop = false; log("STOP dilepas"); refreshRun() }

    private fun toggleRun() {
        if (mode != Mode.AUTO) return
        if (estop) { log("STOP aktif: tahan lama tombol STOP untuk melepas"); return }
        if (!running) {
            if (!link.connected) log("peringatan: USB belum tersambung")
            mission.reset()
            if (missionFull) mission.start(System.currentTimeMillis())
            autoCmd = Cmd.ZERO; autoCmdMs = 0L; autoNote = ""
            running = true
        } else {
            running = false; mission.reset(); link.send("STOP")
        }
        refreshRun()
    }

    private fun log(s: String) = synchronized(logLines) {
        if (logLines.lastOrNull() == s) return@synchronized
        logLines.addLast(s)
        while (logLines.size > 6) logLines.removeFirst()
        val text = logLines.joinToString("\n")
        runOnUiThread { txtLog.text = text }
    }

    private fun updateText() {
        val now = System.currentTimeMillis()
        val t = telem
        val fresh = t != null && now - t.rxMs <= 1000
        val sb = StringBuilder()
        sb.append(if (!link.connected) "USB: BELUM" else if (fresh) "USB: OK" else "USB: OK (tanpa telemetri)").append('\n')
        sb.append("Mode ").append(mode.name).append(" | ").append(teamText().removePrefix("TIM: ")).append(if (caps) " | CAPS" else " | caps-off").append('\n')
        if (t != null && fresh) {
            sb.append(String.format(Locale.US, "Pose %.0f, %.0f cm  %.0f\u00B0\n", t.x, t.y, t.yaw))
            fun u(v: Int) = if (v >= 999) "--" else v.toString()
            sb.append("US ").append(u(t.us[0])).append(" / ").append(u(t.us[1])).append(" / ").append(u(t.us[2])).append(" cm\n")
            sb.append(String.format(Locale.US, "Aman: %s%s  Batt %s\n", if (t.safety == 0) "normal" else "KODE ${t.safety}", if (t.poseLost) "  POSE HILANG" else "",
                if (t.batt < 0) "--" else String.format(Locale.US, "%.1fV", t.batt)))
        }
        if (mode == Mode.AUTO) {
            if (missionFull) {
                sb.append(String.format(Locale.US, "Fase %s %ds | total %d/%ds\nBlok %d/3 | kargo %d\n", mission.phase.label, mission.phaseMs(now) / 1000,
                    mission.totalMs(now) / 1000, Tuning.MATCH_MS / 1000, mission.blocks, if (mission.carrying) 1 else 0))
            } else sb.append("Target ").append(targetKey).append(if (running) " (jalan)\n" else "\n")
            if (autoNote.isNotEmpty() && running) sb.append(autoNote).append('\n')
        }
        if (mode != Mode.TRAINING) sb.append(String.format(Locale.US, "Halangan %s (%.2f)\n", obs.level.name, obs.score))
        txtTelem.text = sb.toString()
    }

    // ---------------------------------------------------------------- USB
    private fun connectUsb() {
        if (!link.hasPermission()) {
            val dev = link.deviceOrNull() ?: return log("perangkat USB tidak ditemukan")
            val mgr = getSystemService(USB_SERVICE) as UsbManager
            val pi = PendingIntent.getBroadcast(this, 0, Intent(usbAction).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            mgr.requestPermission(dev, pi)
            return
        }
        log(link.open() ?: "USB tersambung @ ${Tuning.BAUD}")
    }

    // ---------------------------------------------------------------- pengirim 20 Hz (FR6) + watchdog
    private fun fwMode() = when (mode) { Mode.ASSIST -> "ASSIST"; Mode.AUTO -> "AUTO"; else -> "MANUAL" }

    private fun manualCmd() = Cmd(
        joyMove.vy * Tuning.MANUAL_MAX_RPM,
        joyMove.vx * Tuning.MANUAL_MAX_RPM,
        -joyTurn.vx * Tuning.MANUAL_MAX_RPM * 0.5f      // joystick ke kanan = putar searah jarum jam = W negatif (W + = CCW)
    )

    /** FR4 lapis 2: SLOW/STOP dari perubahan frame zona depan. Lapis 1 (ultrasonik) tetap di firmware. */
    private fun guard(c: Cmd, now: Long): Cmd {
        if (!caps || now - obsMs > 600L) return c
        return when (obs.level) {
            ObsLevel.STOP -> Cmd.ZERO
            ObsLevel.SLOW -> c.scaled(Tuning.SLOW_SCALE)
            ObsLevel.NONE -> c
        }
    }

    private fun tick() {
        try {
            val now = System.currentTimeMillis()
            if (!link.connected && now - lastConnTry > 2000L) {      // sambung ulang otomatis bila izin sudah ada
                lastConnTry = now
                if (link.deviceOrNull() != null && link.hasPermission() && link.open() == null) log("USB tersambung @ ${Tuning.BAUD}")
            }
            if (estop) { link.send("STOP"); active = false; return }
            val cmd: Cmd? = when (mode) {
                Mode.MANUAL -> manualCmd()
                Mode.ASSIST -> guard(manualCmd(), now)
                Mode.AUTO -> if (running) (if (now - autoCmdMs <= Tuning.CMD_STALE_MS) guard(autoCmd, now) else Cmd.ZERO) else null
                Mode.TRAINING -> null
            }
            if (now - lastKeep >= Tuning.KEEPALIVE_MS) {
                lastKeep = now
                link.send("MODE:" + fwMode())
                link.send("TARGET:" + if (targetKey.startsWith("LM:")) "LM" else targetKey)
                link.send("CAPS:" + if (caps) "ON" else "OFF")
            }
            if (cmd != null) { link.send(cmd.text()); active = true }
            else if (active) { link.send("STOP"); active = false }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- kamera + pipeline
    private fun startCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val an = ImageAnalysis.Builder()
                .setTargetResolution(ASize(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            an.setAnalyzer(exec) { analyze(it) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val bmp = proxy.toBitmap()
        val rot = proxy.imageInfo.rotationDegrees
        proxy.close()
        val full = Mat(); val small = Mat()
        try {
            Utils.bitmapToMat(bmp, full)
            when (rot) {
                90 -> Core.rotate(full, full, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(full, full, Core.ROTATE_180)
                270 -> Core.rotate(full, full, Core.ROTATE_90_COUNTERCLOCKWISE)
            }
            val scale = Tuning.PROC_WIDTH.toDouble() / full.cols()
            Imgproc.resize(full, small, org.opencv.core.Size(), scale, scale, Imgproc.INTER_AREA)
            process(small)
        } catch (e: Exception) {
            log("analisis: ${e.message}")
        } finally {
            full.release(); small.release()
        }
    }

    private fun process(small: Mat) {
        val now = System.currentTimeMillis()
        if (lastFrameMs != 0L && now > lastFrameMs) fps = 0.9f * fps + 0.1f * (1000f / (now - lastFrameMs))
        lastFrameMs = now

        while (true) { val e = edits.poll() ?: break; e(profile) }    // perubahan profil dari UI
        val p = profile
        val m = mode
        detector.prepare(small)

        val tap = tapReq
        if (tap != null) {                                            // FR10: sampling sentuh, multi-tempat
            tapReq = null
            detector.sample(tap[0], tap[1])?.let { s ->
                p.cls[trainClass]?.let { c -> c.ranges.add(Classes.rangeFrom(s, p.tol)); c.enabled = true }
                log("sampel H${s[0]} S${s[1]} V${s[2]} -> ${trainClass}")
                refreshSliders()
            }
        }

        val sr = strokeReq
        if (sr != null) { strokeReq = null; handleStroke(sr, p) }

        var dets: List<Det> = emptyList()
        var mask: Bitmap? = null
        if (m == Mode.TRAINING) {
            val c = p.cls[trainClass]
            if (showAll) dets = detector.detect(p)
            else if (c != null) { dets = detector.detectClass(c); mask = detector.lastMaskBitmap() }   // preview mask realtime
            if (arriveReq) {
                arriveReq = false
                val best = dets.filter { it.cls == trainClass }.maxByOrNull { it.area }
                if (best != null && c != null) { c.arrive = best.area; log(String.format(Locale.US, "area tiba %s = %.1f%%", trainClass, best.area * 100f)); refreshSliders() }
                else log("kelas $trainClass tidak terlihat")
            }
        } else {
            dets = detector.detect(p)
            obs = watch.update(small, dets, p.obsSlow, p.obsStop)
            obsMs = now
        }

        if (m == Mode.AUTO && running && !estop) { autoCmd = decideAuto(dets, now, p); autoCmdMs = now }

        overlay.fw = small.cols(); overlay.fh = small.rows()
        val lmDraw = ArrayList<LmDraw>()
        if (m == Mode.TRAINING) for (l in p.landmarks) for (r in l.regions) lmDraw.add(LmDraw(l.name, r.pts, false))
        for (d in dets) { val r = d.lmRegion; val n = d.landmark; if (r != null && n != null) lmDraw.add(LmDraw(n, r.pts, true)) }
        overlay.lms = lmDraw
        overlay.dets = dets; overlay.mask = mask; overlay.obs = obs; overlay.fps = fps
        overlay.postInvalidate()
    }

    /** FR11: region coretan -> sampling HSV ke kelas terpilih + simpan sebagai sampel landmark (posisi kamera ke-n). */
    private fun handleStroke(sr: StrokeReq, p: Profile) {
        val res = detector.regionFromStroke(sr.pts)
        if (res == null) { log("coretan terlalu kecil / piksel kurang"); return }
        val c = p.cls[sr.cls] ?: return
        var lm = p.landmarks.firstOrNull { it.name == sr.name }
        if (lm != null && lm.classId != sr.cls) { log("nama ${sr.name} sudah dipakai kelas ${lm.classId}"); return }
        val pose = sr.pose
        if (lm != null && pose != null && lm.regions.any { it.pose != null && !Landmark.farEnough(it.pose, pose) }) {
            log("posisi robot sama dengan sampel lama: geser >= ${Tuning.LM_MOVE_CM.toInt()} cm atau putar >= ${Tuning.LM_MOVE_DEG.toInt()}\u00B0"); return
        }
        if (lm == null) { lm = Landmark(sr.name, sr.cls); p.landmarks.add(lm) }
        val (range, box) = res
        c.ranges.add(range); c.enabled = true
        // simpan coretan asli, dikurangi ke maks ~40 titik
        val n = sr.pts.size / 2
        val step = maxOf(1, n / 40)
        val keep = ArrayList<Float>()
        var i = 0
        while (i < n) { keep.add(sr.pts[2 * i]); keep.add(sr.pts[2 * i + 1]); i += step }
        lm.regions.add(Region(box[0], box[1], box[2], box[3], keep.toFloatArray(), pose))
        log("landmark ${lm.name}: posisi ${lm.regions.size}/${Tuning.LM_MIN_POS}" + if (lm.valid) " (valid)" else " (coret lagi dari posisi lain)")
        refreshSliders(); refreshLandmarks()
    }

    private fun targetIds(key: String, t: String): Set<String> = when (key) {
        "STORAGE" -> setOf("GROUND_$t")
        "TRANSFER" -> setOf("TRANSFER_$t")
        "SPOT" -> setOf("SPOT")
        else -> setOf("EARTH_$t", "SKY_$t")
    }

    /** FR5: misi TR penuh, atau navigasi sederhana ke target terpilih (M2). */
    private fun decideAuto(dets: List<Det>, now: Long, p: Profile): Cmd {
        if (missionFull) {
            val o = mission.step(now, dets, team, p)
            autoNote = o.note
            if (mission.phase == Phase.DONE) { running = false; log("misi selesai, ${mission.blocks} blok"); refreshRun() }
            return o.cmd
        }
        val d = if (targetKey.startsWith("LM:")) Nav.pickLandmark(dets, targetKey.substring(3)) else Nav.pick(dets, targetIds(targetKey, team))
        val arrive = d?.let { p.arriveOf(it.cls) } ?: Tuning.STOP_AREA
        val s = Nav.goTo(d, arrive, p)
        autoNote = if (s.visible) "" else "target tidak terlihat"
        if (s.arrived) { running = false; log("tiba di target $targetKey"); refreshRun() }
        return s.cmd
    }

    override fun onDestroy() {
        running = false
        ticker.shutdownNow()
        link.send("STOP"); link.close()
        unregisterReceiver(usbReceiver)
        exec.shutdown()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
