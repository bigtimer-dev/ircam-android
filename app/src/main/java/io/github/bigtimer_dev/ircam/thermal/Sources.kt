package io.github.bigtimer_dev.ircam.thermal

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import io.github.bigtimer_dev.ircam.usb.NativeUvc
import io.github.bigtimer_dev.ircam.usb.P2ProControl
import java.io.IOException
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Something that delivers raw camera frames (the byte layout of the camera's YUYV stream). */
abstract class FrameSource {
    val cam = CameraState()
    abstract val name: String
    open val live = true
    open val canSwitchRange = false

    abstract fun open()
    /** Newest raw frame, or null on timeout. Throws IOException when the camera is gone. */
    abstract fun read(): ByteArray?
    open fun close() {}
    open fun setHighRange(high: Boolean) {}
    /** true/false when the camera reports its range, null when unknown. */
    open fun currentHighRange(): Boolean? = null

    protected fun defaultConfig() {
        val corr = cam.tempCorrection
        cam.resetConfigToDefaults()
        cam.tempCorrection = corr
    }
}

/** InfiRay P2 / P2 Pro / TC001 family (pool 2): 256x384 YUYV, lower half radiometric. */
class UsbPool2Source(
    private val manager: UsbManager,
    private val device: UsbDevice,
) : FrameSource() {
    override val name: String = when {
        device.vendorId == P2ProControl.VID && device.productId == P2ProControl.PID -> "InfiRay P2 Pro"
        else -> device.productName ?: "USB thermal camera"
    }
    override var canSwitchRange = false
        private set

    private var conn: UsbDeviceConnection? = null
    private var handle = 0L
    private var p2: P2ProControl? = null
    private val buf = ByteArray(Pools.POOL2_W * Pools.POOL2_H * 2)

    override fun open() {
        val c = manager.openDevice(device) ?: throw IOException("Could not open the USB camera (permission denied?)")
        conn = c
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 0x0E) c.claimInterface(itf, true) // detach uvcvideo if bound
        }
        val err = arrayOfNulls<String>(1)
        handle = NativeUvc.nativeOpen(c.fileDescriptor, Pools.POOL2_W, Pools.POOL2_H, err)
        if (handle == 0L) throw IOException(err[0] ?: "Could not open the UVC stream")
        NativeUvc.nativeStart(handle)?.let { throw IOException(it) }
        cam.setGeometry(Pools.POOL2_W, Pools.POOL2_H, Pools.POOL_2)
        defaultConfig()
        Thermal.generateLut(cam)
        if (device.vendorId == P2ProControl.VID && device.productId == P2ProControl.PID) {
            p2 = P2ProControl(c)
            canSwitchRange = true
        }
    }

    override fun read(): ByteArray? {
        val n = NativeUvc.nativeRead(handle, buf, 1000)
        if (n < 0) throw IOException("The camera stopped streaming (disconnected?)")
        return if (n == buf.size) buf else null
    }

    override fun setHighRange(high: Boolean) {
        val p = p2 ?: return
        p.setHighGain(!high) // high gain = low range
        cam.rangeFlag = if (high) 2 else 1
    }

    override fun currentHighRange(): Boolean? = try {
        p2?.let { !it.highGain() }
    } catch (e: IOException) {
        null
    }

    override fun close() {
        if (handle != 0L) { NativeUvc.nativeClose(handle); handle = 0L }
        conn?.close()
        conn = null
        p2 = null
    }
}

/** A simulated P2 Pro (pool 2 frame format) so the app can be tried without a camera. */
class DemoSource : FrameSource() {
    override val name = "Demo camera"
    override val canSwitchRange = true
    private val w = 256
    private val h = 192
    private val fpn = DoubleArray(w * h)
    private val rnd = Random(1)
    private var t0 = 0L
    private var last = 0L
    private val frame = ByteArray(w * 384 * 2)

    override fun open() {
        cam.setGeometry(Pools.POOL2_W, Pools.POOL2_H, Pools.POOL_2)
        defaultConfig()
        Thermal.generateLut(cam)
        for (i in fpn.indices) fpn[i] = gaussian() * 0.05
        t0 = System.nanoTime()
    }

    private fun gaussian(): Double {
        // Box-Muller
        val u = rnd.nextDouble().coerceAtLeast(1e-12)
        val v = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * cos(2 * Math.PI * v)
    }

    override fun read(): ByteArray {
        val wait = 40 - (System.nanoTime() - last) / 1_000_000
        if (wait > 0) Thread.sleep(wait)
        last = System.nanoTime()
        val t = (last - t0) / 1e9
        val cx = 128 + 70 * cos(t * 0.4)
        val cy = 96 + 45 * sin(t * 0.55)
        val half = w * 192 * 2
        for (y in 0 until h) for (x in 0 until w) {
            var s = 22.0 + 4.0 * (y / 192.0) + 1.5 * sin(x / 25.0)
            val dx = x - cx; val dy = y - cy
            s += 48.0 * exp(-((dx * dx + dy * dy) / (2 * 14.0 * 14.0)))
            if (kotlin.math.abs(x - 60) < 18 && kotlin.math.abs(y - 140) < 10) s += 30.0
            val ex = x - 200.0; val ey = y - 50.0
            s -= 14.0 * exp(-((ex * ex + ey * ey) / (2 * 10.0 * 10.0)))
            val i = y * w + x
            s += fpn[i] + rnd.nextDouble(-0.1, 0.1)
            val raw = ((s + 273.15) * 64.0).toInt().coerceIn(0, 65535)
            val img = raw shr 6
            frame[2 * i] = img.toByte(); frame[2 * i + 1] = (img shr 8).toByte()
            frame[half + 2 * i] = raw.toByte(); frame[half + 2 * i + 1] = (raw shr 8).toByte()
        }
        return frame
    }

    override fun setHighRange(high: Boolean) { cam.rangeFlag = if (high) 2 else 1 }
    override fun currentHighRange() = cam.rangeFlag == 2
}

/** A RAW snapshot (SnapShotRAW_*.png) opened for analysis. */
class SnapshotSource(private val image: RawImage, override val name: String) : FrameSource() {
    override val live = false
    private lateinit var raw: ByteArray

    override fun open() {
        val m = image.meta
        cam.pool = m.pool; cam.width = image.width; cam.height = image.height
        cam.metaRows = m.metaRows; cam.widthOffset = m.widthOffset; cam.heightOffset = m.heightOffset
        cam.tempCorrection = m.tempCorrection; cam.ambient = m.ambient; cam.reflected = m.reflected
        cam.humidity = m.humidity; cam.emissivity = m.emissivity; cam.distance = m.distance.toDouble()
        cam.initConstants()
        raw = RawFiles.unpack(image)
        val td = Thermal.decodeFrame(cam, raw)
        Thermal.readMetadata(cam, td)
        Thermal.readCalibration(cam, td)
        Thermal.generateLut(cam)
    }

    override fun read(): ByteArray = raw
}
