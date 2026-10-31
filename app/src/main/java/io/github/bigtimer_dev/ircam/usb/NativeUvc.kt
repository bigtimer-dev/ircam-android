package io.github.bigtimer_dev.ircam.usb

/** JNI bridge to the libusb-based UVC streamer (app/src/main/cpp/uvc.c). */
object NativeUvc {
    init { System.loadLibrary("ircamusb") }

    @JvmStatic external fun nativeOpen(fd: Int, width: Int, height: Int, errOut: Array<String?>): Long
    @JvmStatic external fun nativeStart(handle: Long): String?
    @JvmStatic external fun nativeRead(handle: Long, out: ByteArray, timeoutMs: Int): Int
    @JvmStatic external fun nativeDropped(handle: Long): Long
    @JvmStatic external fun nativeClose(handle: Long)
}
