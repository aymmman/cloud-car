package com.cc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : AppCompatActivity() {

    // ── Car identity ───────────────────────────────────────────
    // The car's web server answers with this signature (realm="RakCam")
    private val CAR_SIGNATURE = "RakCam"
    private val PREFS    = "CloudCar"
    private val PREF_IP  = "car_ip"

    // ── Motor map ──────────────────────────────────────────────
    private val motors = mapOf(1 to (1 to 2), 3 to (1 to 1), 5 to (2 to 1), 7 to (2 to 2))
    private val diag   = mapOf(2 to (3 to 1), 4 to (3 to 5), 6 to (7 to 5), 8 to (7 to 1))

    // ── Volatile state shared with control loop ────────────────
    @Volatile private var dir   = 0
    @Volatile private var light = 0
    @Volatile private var cam   = 0
    @Volatile private var carIp: String? = null
    @Volatile private var talking = false
    private var muted = false
    private val AUTH = "YWRtaW46YWRtaW4="   // base64 of admin:admin

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var player: ExoPlayer

    // ── Views ──────────────────────────────────────────────────
    private lateinit var layoutDiscovery: View
    private lateinit var tvDiscovery: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvBattery: TextView

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        layoutDiscovery = findViewById(R.id.layoutDiscovery)
        tvDiscovery     = findViewById(R.id.tvDiscovery)
        progressBar     = findViewById(R.id.progressBar)
        btnRetry        = findViewById(R.id.btnRetry)
        tvStatus        = findViewById(R.id.tvStatus)
        tvBattery       = findViewById(R.id.tvBattery)

        // Prepare player (not started until car is found)
        player = ExoPlayer.Builder(this).build()
        findViewById<PlayerView>(R.id.video).apply {
            player = this@MainActivity.player
            useController = false
        }

        setupControls()
        btnRetry.setOnClickListener { startDiscovery() }
        startDiscovery()
    }

    // ── Discovery ──────────────────────────────────────────────

    private fun startDiscovery() {
        btnRetry.visibility  = View.GONE
        progressBar.visibility = View.VISIBLE
        tvDiscovery.text     = "Searching for car..."

        scope.launch {
            val prefs  = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = prefs.getString(PREF_IP, null)

            // 1. Try last known IP first (fast path – < 500 ms)
            if (cached != null) {
                withContext(Dispatchers.Main) {
                    tvDiscovery.text = "Trying last address ($cached)..."
                }
                if (reachable(cached)) {
                    onCarFound(cached)
                    return@launch
                }
            }

            // 2. Full network scan
            withContext(Dispatchers.Main) {
                tvDiscovery.text = "Scanning network..."
            }

            val ip = scanNetwork()
            if (ip != null) {
                prefs.edit().putString(PREF_IP, ip).apply()
                onCarFound(ip)
            } else {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    tvDiscovery.text =
                        "Car not found.\n\nMake sure the car is on and your phone\nis on the same Wi-Fi (or the car's CC_ hotspot)."
                    btnRetry.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Port 554 open AND web server identifies itself as RakCam */
    private fun reachable(ip: String): Boolean = portOpen(ip, 554, 600) && isCar(ip)

    private fun portOpen(ip: String, port: Int, timeout: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeout) }
        true
    } catch (_: Exception) { false }

    /** Ask the device's web server who it is. The car replies realm="RakCam". */
    private fun isCar(ip: String): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 80), 800)
            s.soTimeout = 1500
            s.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".toByteArray())
            val buf = ByteArray(1024)
            val n = s.getInputStream().read(buf)
            n > 0 && String(buf, 0, n).contains(CAR_SIGNATURE)
        }
    } catch (_: Exception) { false }

    /**
     * Scan all 254 hosts on the phone's current Wi-Fi subnet in parallel.
     * Works on any router, and on the car's own hotspot (192.168.100.x).
     */
    private suspend fun scanNetwork(): String? {
        val subnet = localSubnet() ?: return null
        val candidates = coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val ip = "$subnet.$i"
                    if (portOpen(ip, 554, 400)) ip else null
                }
            }.awaitAll().filterNotNull()
        }
        return candidates.firstOrNull { isCar(it) }
    }

    /** Get the local subnet prefix e.g. "192.168.0" */
    private fun localSubnet(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast(".")
    } catch (_: Exception) { null }

    // ── Car found ──────────────────────────────────────────────

    private suspend fun onCarFound(ip: String) {
        withContext(Dispatchers.Main) {
            // Fade out discovery screen
            layoutDiscovery.animate().alpha(0f).setDuration(400).withEndAction {
                layoutDiscovery.visibility = View.GONE
            }

            // Show controller
            carIp = ip
            listOf(R.id.video, R.id.hud, R.id.dpad, R.id.sideControls, R.id.audioRow)
                .forEach { findViewById<View>(it).visibility = View.VISIBLE }

            // Start video
            // RTP over TCP: no lost packets on Wi-Fi, so the picture does not freeze
            val rtspFactory = RtspMediaSource.Factory().setForceUseRtpTcp(true)
            player.setMediaSource(rtspFactory.createMediaSource(
                MediaItem.fromUri("rtsp://admin:admin@$ip:554/cam1/h264")))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                        layoutDiscovery.postDelayed({ player.prepare() }, 1000)
                    }
                }
            })
            player.prepare()
            player.play()
        }
        controlLoop(ip)
    }

    // ── Control loop ───────────────────────────────────────────

    private fun controlLoop(ip: String) {
        scope.launch {
            var step = 0
            while (isActive) {
                try {
                    Socket(ip, 80).use { s ->
                        s.tcpNoDelay = true  // one command per TCP segment: the car only reads the first command in each segment
                        s.soTimeout = 100
                        val os  = s.getOutputStream()
                        val inp = s.getInputStream()
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "● Connected  $ip"
                        }
                        val rx = ByteArray(64)
                        var acc = ByteArray(0)
                        var lastReply = System.currentTimeMillis()
                        var boardOk: Boolean? = null
                        while (isActive) {
                            os.write(pkt(dir, light, cam, step))
                            if (dir in diag) step++ else step = 0
                            try {
                                val n = inp.read(rx)
                                if (n > 0) acc += rx.copyOf(n)
                            } catch (_: IOException) {}
                            // The car answers with 01 55 92 07 01 <battery %> .. .. <checksum>
                            var i = 0
                            var bat = -1
                            while (i + 8 < acc.size) {
                                if (acc[i] == 1.toByte() && acc[i + 1] == 0x55.toByte() &&
                                    acc[i + 2] == 0x92.toByte() && acc[i + 3] == 7.toByte() &&
                                    acc[i + 4] == 1.toByte()) {
                                    bat = acc[i + 5].toInt() and 0xFF
                                    i += 9
                                } else i++
                            }
                            if (acc.size > 8) acc = acc.copyOfRange(acc.size - 8, acc.size)
                            val now = System.currentTimeMillis()
                            if (bat >= 0) {
                                lastReply = now
                                withContext(Dispatchers.Main) { tvBattery.text = "Battery $bat%" }
                            }
                            val ok = now - lastReply < 3000
                            if (ok != boardOk) {
                                boardOk = ok
                                withContext(Dispatchers.Main) {
                                    tvStatus.text = if (ok) "● Connected  $ip"
                                                    else "● Car not answering (charging?)"
                                }
                            }
                            delay(150)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { tvStatus.text = "○ Reconnecting…" }
                    delay(2000)
                }
            }
        }
    }

    // ── Packet builder ─────────────────────────────────────────

    private fun pkt(d: Int, l: Int, c: Int, step: Int): ByteArray {
        var d2 = d
        diag[d]?.let { (a, b) -> d2 = if (step % 4 <= 2) a else b }
        val (lm, rm) = motors.getOrDefault(d2, 0 to 0)
        val m = byteArrayOf(1, 85, 0x92.toByte(), 9, 1,
                            lm.toByte(), c.toByte(), rm.toByte(), 0, l.toByte(), 0)
        m[10] = (3..9).fold(0) { acc, i -> acc xor m[i].toInt() }.toByte()
        return m
    }

    // ── Controls setup ─────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls() {
        mapOf(R.id.dFL to 4, R.id.dF to 3, R.id.dFR to 2,
              R.id.dL  to 5,               R.id.dR  to 1,
              R.id.dBL to 6, R.id.dB to 7, R.id.dBR to 8
        ).forEach { (id, d) -> hold(id, { dir = d }, { dir = 0 }) }

        hold(R.id.btnCU, { cam = 1 }, { cam = 0 })
        hold(R.id.btnCD, { cam = 2 }, { cam = 0 })

        // Car sound on/off (the car microphone comes with the video stream)
        val btnMute = findViewById<ImageButton>(R.id.btnMute)
        btnMute.setOnClickListener {
            muted = !muted
            player.volume = if (muted) 0f else 1f
            btnMute.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
        }

        // Hold to talk: record while pressed, send to the car speaker on release
        val btnTalk = findViewById<Button>(R.id.btnTalk)
        btnTalk.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; startTalk(btnTalk) }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> { v.isPressed = false; talking = false }
            }
            true
        }

        val btnLight = findViewById<View>(R.id.btnLight)
        btnLight.setOnClickListener {
            light = if (light == 0) 1 else 0
            btnLight.isSelected = light == 1
        }
    }

    // ── Talk through the car speaker ───────────────────────────

    @SuppressLint("MissingPermission")
    private fun startTalk(btn: Button) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val ip = carIp ?: return
        if (talking) return
        talking = true
        btn.text = "Recording... release to send"
        scope.launch {
            val out = ByteArrayOutputStream()
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(8000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, 8000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
                val buf = ShortArray(min)
                rec.startRecording()
                while (talking && out.size() < 8000 * 30) {   // max 30 seconds
                    val n = rec.read(buf, 0, buf.size)
                    for (i in 0 until n) out.write(ulaw(buf[i].toInt()).toInt())
                }
            } catch (_: Exception) {
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                talking = false
            }
            val data = out.toByteArray()
            val ok = data.size > 1600 && sendAudio(ip, data)   // ignore taps under 0.2 s
            withContext(Dispatchers.Main) { btn.text = if (ok) "Sent ✓" else "Hold to talk" }
            if (ok) {
                delay(1200)
                withContext(Dispatchers.Main) { btn.text = "Hold to talk" }
            }
        }
    }

    /** Same request the original app used: POST /audio.input with 8 kHz mu-law audio */
    private fun sendAudio(ip: String, data: ByteArray): Boolean = try {
        Socket(ip, 80).use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 3000
            val out = s.getOutputStream()
            val header = "POST /audio.input HTTP/1.1\r\nHost: $ip\r\n" +
                "Authorization: Basic $AUTH\r\nContent-Type: audio/wav\r\n" +
                "Content-Length: ${data.size}\r\nAccept: */*\r\n\r\n"
            out.write(header.toByteArray())
            out.flush()
            Thread.sleep(100)
            out.write(data)
            out.flush()
            try { s.getInputStream().read(ByteArray(256)) } catch (_: Exception) {}
        }
        true
    } catch (_: Exception) { false }

    /** 16-bit PCM sample to G.711 mu-law */
    private fun ulaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > 32635) s = 32635
        s += 0x84
        var e = 7
        var m = 0x4000
        while (e > 0 && (s and m) == 0) { e--; m = m shr 1 }
        val mant = (s shr (e + 3)) and 0x0F
        return ((sign or (e shl 4) or mant).inv() and 0xFF).toByte()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun hold(id: Int, onDown: () -> Unit, onUp: () -> Unit) {
        val v = findViewById<View>(id)
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { onDown(); v.isPressed = true }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> { onUp(); v.isPressed = false }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        player.release()
    }
}
