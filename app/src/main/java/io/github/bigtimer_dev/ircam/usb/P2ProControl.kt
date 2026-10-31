package io.github.bigtimer_dev.ircam.usb

import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * InfiRay P2 Pro vendor command channel (vendor control transfers to "mailboxes"), as used by
 * ircam-linux for temperature range switching. Protocol from p2pro-rs (p2pro-hw crate).
 */
class P2ProControl(private val conn: UsbDeviceConnection) {
    private fun out(mailbox: Int, data: ByteArray) {
        val r = conn.controlTransfer(REQ_TYPE_OUT, REQ_OUT, REQ_VALUE, mailbox, data, data.size, 1000)
        if (r < 0) throw IOException("control OUT failed")
    }

    private fun input(mailbox: Int, length: Int): ByteArray {
        val buf = ByteArray(length)
        val r = conn.controlTransfer(REQ_TYPE_IN, REQ_IN, REQ_VALUE, mailbox, buf, length, 1000)
        if (r < 0) throw IOException("control IN failed")
        return buf.copyOf(r)
    }

    private fun waitReady(timeoutMs: Long = 5000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val st = input(MB_STATUS, 1).firstOrNull()?.toInt()?.and(0xFF) ?: 0
            if (st and STATUS_BUSY == 0) return
            if (st and STATUS_ERROR != 0) throw IOException("camera reported error status 0x%02x".format(st))
            if (System.currentTimeMillis() > end) throw IOException("timeout waiting for camera")
            Thread.sleep(10)
        }
    }

    private fun header(code: Int, p1: Int, p2: Int) = byteArrayOf(
        code.toByte(), (code shr 8).toByte(),
        (p1 shr 8).toByte(), p1.toByte(),
        (p2 shr 24).toByte(), (p2 shr 16).toByte(), (p2 shr 8).toByte(), p2.toByte(),
    )

    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun longWrite(code: Int, p1: Int, p2: Int) {
        out(MB_HEADER_B, header(code, p1, p2))
        out(MB_DATA, be32(0) + be32(0))
        waitReady()
    }

    private fun longRead(code: Int, p1: Int, p2: Int, length: Int): ByteArray {
        out(MB_HEADER_B, header(code, p1, p2))
        out(MB_DATA, be32(0) + be32(length))
        waitReady()
        val d = input(MB_LONG_RESULT, length)
        waitReady()
        return d
    }

    @Synchronized
    fun highGain(): Boolean {
        val d = longRead(CMD_TPD, TPD_GAIN_SELECT, 0, 2)
        return d.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) } != 0
    }

    /** High gain = low temperature range (about -20..150 °C); low gain = high range. */
    @Synchronized
    fun setHighGain(high: Boolean) = longWrite(CMD_TPD or SET_FLAG, TPD_GAIN_SELECT, if (high) 1 else 0)

    companion object {
        const val VID = 0x0BDA
        const val PID = 0x5830
        private const val REQ_TYPE_OUT = 0x41
        private const val REQ_TYPE_IN = 0xC1
        private const val REQ_OUT = 0x45
        private const val REQ_IN = 0x44
        private const val REQ_VALUE = 0x0078
        private const val MB_STATUS = 0x0200
        private const val MB_HEADER_B = 0x9D00
        private const val MB_DATA = 0x1D08
        private const val MB_LONG_RESULT = 0x1D10
        private const val STATUS_BUSY = 0x03
        private const val STATUS_ERROR = 0xFC
        private const val SET_FLAG = 0x4000
        private const val CMD_TPD = 0x8514
        private const val TPD_GAIN_SELECT = 5
    }
}
