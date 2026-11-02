package io.github.bigtimer_dev.ircam.thermal

import android.net.Network
import io.github.bigtimer_dev.ircam.net.Irw1
import io.github.bigtimer_dev.ircam.net.Irw1Assembler
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Link quality of a wireless source, for the status line. */
data class LinkStats(val loss: Double, val latencyMs: Double?, val rssiDbm: Int?, val batteryPct: Double?, val cameraOk: Boolean)

/**
 * The P2 Pro on the wireless bridge (Raspberry Pi running ircam_bridge.py). The bridge sends the
 * temperature half; the 256x384 camera frame is rebuilt here, so the rest of the app treats it
 * exactly like the USB camera.
 *
 * [network] is the WiFi network to use. Sockets are bound to it so Android doesn't route the
 * stream over mobile data when the bridge's hotspot has no internet.
 */
class NetworkSource(hostSpec: String, private val network: Network?) : FrameSource() {
    override val name = "InfiRay P2 Pro (Wireless)"
    override var canSwitchRange = false
        private set

    private val host: String
    private val port: Int
    init {
        val t = hostSpec.trim().ifEmpty { Irw1.DEFAULT_HOST }
        val i = t.lastIndexOf(':')
        if (i > 0 && t.count { it == ':' } == 1) { host = t.substring(0, i); port = t.substring(i + 1).toIntOrNull() ?: Irw1.CTRL_PORT }
        else { host = t; port = Irw1.CTRL_PORT }
    }

    private var ctrl: Socket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var alive = false
    @Volatile private var dead: String? = null
    private val replies = ConcurrentHashMap<Int, ArrayBlockingQueue<JSONObject>>()
    private val nextId = AtomicInteger()
    private val writeLock = Any()
    private lateinit var token: String
    private val asm = Irw1Assembler(Irw1.FRAME_ROWS - Irw1.TEMP_ROW0)

    private val frameLock = Object()
    private var latest: ByteArray? = null
    private var seq = 0L
    private var readSeq = 0L
    private val out = ByteArray(Irw1.WIDTH * Irw1.FRAME_ROWS * 2)

    private val pings = ArrayDeque<Pair<Long, Long>>()   // (rtt us, offset us)
    @Volatile private var offsetUs: Long? = null
    @Volatile private var latencyMs: Double? = null
    @Volatile private var status = JSONObject()

    // ------------------------------------------------------------------ lifecycle
    override fun open() {
        val addr = resolve()
        val s = network?.socketFactory?.createSocket() ?: Socket()
        try {
            s.connect(InetSocketAddress(addr, port), 5000)
        } catch (e: IOException) {
            s.close()
            throw IOException("Cannot reach the wireless bridge at ${addr.hostAddress}:$port (${e.message}). Is it on and on the same network?")
        }
        s.tcpNoDelay = true
        ctrl = s
        alive = true
        Thread(::ctrlLoop, "irw-ctrl").apply { isDaemon = true; start() }
        try {
            val hello = call("hello")
            if (hello.optInt("proto") != Irw1.VERSION) throw IOException("Unsupported bridge protocol ${hello.optInt("proto")}")
            canSwitchRange = hello.optBoolean("high_range")
            token = hello.getString("token")
            cam.setGeometry(Pools.POOL2_W, Pools.POOL2_H, Pools.POOL_2)
            defaultConfig()
            Thermal.generateLut(cam)
            val u = DatagramSocket(null)
            u.receiveBufferSize = 1 shl 21
            u.bind(null)
            network?.bindSocket(u)
            u.connect(InetSocketAddress(addr, hello.optInt("data_port", Irw1.DATA_PORT)))
            u.soTimeout = 500
            udp = u
            subscribe()
            Thread(::rxLoop, "irw-rx").apply { isDaemon = true; start() }
            Thread(::keepaliveLoop, "irw-keepalive").apply { isDaemon = true; start() }
            ping()
            call("start", JSONObject().put("codec", Irw1.CODEC_ZLIB).put("full_frame", false))
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e.message ?: e.toString())
        }
    }

    /** IP literal, normal DNS, then our own mDNS query for *.local names. */
    private fun resolve(): InetAddress {
        if (host.matches(Regex("^[0-9.]+$")) || host.contains(':')) return InetAddress.getByName(host)
        if (host.endsWith(".local", ignoreCase = true)) mdnsResolve(host)?.let { return it }
        try {
            return (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).first { it is Inet4Address }
        } catch (e: Exception) {
            throw IOException("Cannot find '$host'. Enter the bridge's IP address instead (on its hotspot: ${Irw1.HOTSPOT_IP}).")
        }
    }

    private fun mdnsResolve(name: String): InetAddress? = try {
        MulticastSocket(null).use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            network?.bindSocket(s)
            s.soTimeout = 700
            val q = Mdns.query(name)
            val group = InetAddress.getByName("224.0.0.251")
            val buf = ByteArray(1500)
            var found: InetAddress? = null
            repeat(3) {
                if (found != null) return@repeat
                s.send(DatagramPacket(q, q.size, group, 5353))
                val end = System.nanoTime() + 700_000_000L
                while (found == null && System.nanoTime() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: SocketTimeoutException) { break }
                    found = Mdns.answer(buf, p.length, name)
                }
            }
            found
        }
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ control
    private fun call(cmd: String, args: JSONObject = JSONObject(), timeoutMs: Long = 3000): JSONObject {
        dead?.let { throw IOException(it) }
        val id = nextId.incrementAndGet()
        val q = ArrayBlockingQueue<JSONObject>(1)
        replies[id] = q
        try {
            synchronized(writeLock) {
                val line = args.put("id", id).put("cmd", cmd).toString() + "\n"
                val os = ctrl?.getOutputStream() ?: throw IOException("not connected")
                os.write(line.toByteArray()); os.flush()
            }
            val r = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("The bridge did not answer '$cmd'")
            if (r.has("_dead")) throw IOException(dead ?: "connection lost")
            if (!r.optBoolean("ok")) throw IOException(r.optString("error", "'$cmd' failed"))
            return r
        } finally { replies.remove(id) }
    }

    private fun ctrlLoop() {
        try {
            val rd = BufferedReader(InputStreamReader(ctrl!!.getInputStream()))
            while (alive) {
                val line = rd.readLine() ?: throw IOException("connection closed")
                val m = JSONObject(line)
                replies[m.optInt("id", -1)]?.offer(m)
            }
        } catch (e: Exception) {
            if (alive) die("Lost the connection to the wireless bridge (${e.message})")
        }
    }

    private fun die(reason: String) {
        if (dead == null) dead = reason
        val d = JSONObject().put("_dead", true)
        replies.values.forEach { it.offer(d) }
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    private fun subscribe() {
        val p = Irw1.subscribePacket(token)
        try { udp?.send(DatagramPacket(p, p.size)) } catch (e: IOException) { }
    }

    private fun ping() {
        val t0 = System.nanoTime() / 1000
        val r = call("ping", JSONObject().put("t", t0), 2000)
        val t1 = System.nanoTime() / 1000
        synchronized(pings) {
            pings.addLast((t1 - t0) to (r.getLong("bridge_us") - (t0 + t1) / 2))
            while (pings.size > 10) pings.removeFirst()
            offsetUs = pings.minByOrNull { it.first }!!.second
        }
    }

    private fun keepaliveLoop() {
        var n = 0
        while (alive && dead == null) {
            subscribe()
            try {
                ping()
                if (n % 2 == 0) status = call("status")
            } catch (e: IOException) {
                if (dead != null) return
            }
            n++
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return }
        }
    }

    private fun rxLoop() {
        val buf = ByteArray(2048)
        val p = DatagramPacket(buf, buf.size)
        while (alive && dead == null) {
            try {
                p.setData(buf)
                udp?.receive(p) ?: return
            } catch (e: SocketTimeoutException) { continue } catch (e: IOException) {
                if (!alive) return
                continue
            }
            for (f in asm.feed(buf, p.length)) {
                val v = f.values
                val half = Irw1.WIDTH * Irw1.TEMP_ROW0 * 2
                synchronized(frameLock) {
                    for (i in v.indices) {
                        val raw = v[i]
                        out[half + 2 * i] = raw.toByte(); out[half + 2 * i + 1] = (raw shr 8).toByte()
                        val img = raw shr 6           // stand-in image half (unused downstream)
                        out[2 * i] = img.toByte(); out[2 * i + 1] = (img shr 8).toByte()
                    }
                    latest = out
                    seq++
                    offsetUs?.let { latencyMs = (System.nanoTime() / 1000 - (f.tsUs - it)) / 1000.0 }
                    frameLock.notifyAll()
                }
            }
        }
    }

    // ------------------------------------------------------------------ FrameSource
    private val copy = ByteArray(out.size)

    override fun read(): ByteArray? {
        synchronized(frameLock) {
            val end = System.nanoTime() + 1_000_000_000L
            while (dead == null && seq == readSeq) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return null
                frameLock.wait(left)
            }
            dead?.let { throw IOException(it) }
            readSeq = seq
            System.arraycopy(latest!!, 0, copy, 0, copy.size)
        }
        return copy
    }

    fun linkStats() = LinkStats(
        asm.loss(), latencyMs, status.optInt("rssi_dbm", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
        status.optDouble("battery_pct").takeIf { !it.isNaN() }, status.optBoolean("camera_ok", true),
    )

    override fun setHighRange(high: Boolean) {
        if (!canSwitchRange) return
        call("set_range", JSONObject().put("high", high), 8000)
        cam.rangeFlag = if (high) 2 else 1
    }

    override fun currentHighRange(): Boolean? = try {
        if (canSwitchRange) call("get_range").getBoolean("high") else null
    } catch (e: IOException) { null }

    override fun close() {
        if (alive && dead == null) try { call("stop", timeoutMs = 1000) } catch (e: IOException) { }
        alive = false
        die("closed")
        try { ctrl?.shutdownInput(); ctrl?.shutdownOutput() } catch (e: IOException) { }
        try { ctrl?.close() } catch (e: IOException) { }
        udp?.close()
        ctrl = null; udp = null
    }
}

/** Minimal mDNS A-record lookup (QU question, so the answer comes back unicast). */
internal object Mdns {
    fun query(name: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray()
            o.write(b.size); o.write(b)
        }
        o.write(byteArrayOf(0, 0, 1, 0x80.toByte(), 1))     // type A, class IN + unicast-response bit
        return o.toByteArray()
    }

    /** IPv4 address for [name] in an mDNS response, or null. */
    fun answer(b: ByteArray, len: Int, name: String): InetAddress? {
        if (len < 12) return null
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        val qd = u16(4); val an = u16(6) + u16(8) + u16(10)
        var i = 12
        fun readName(start: Int): Pair<String, Int> {
            val sb = StringBuilder(); var p = start; var next = -1; var hops = 0
            while (p < len) {
                val l = b[p].toInt() and 0xFF
                if (l == 0) { p++; break }
                if (l and 0xC0 == 0xC0) {
                    if (next < 0) next = p + 2
                    p = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                    if (++hops > 20) break
                    continue
                }
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(b, p + 1, l)); p += 1 + l
            }
            return sb.toString() to (if (next >= 0) next else p)
        }
        try {
            repeat(qd) { i = readName(i).second + 4 }
            repeat(an) {
                val (n, p) = readName(i)
                val type = u16(p); val rdlen = u16(p + 8)
                if (type == 1 && rdlen == 4 && n.equals(name.trimEnd('.'), ignoreCase = true)) {
                    return InetAddress.getByAddress(b.copyOfRange(p + 10, p + 14))
                }
                i = p + 10 + rdlen
            }
        } catch (e: IndexOutOfBoundsException) { }
        return null
    }
}
