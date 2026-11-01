package io.github.bigtimer_dev.ircam

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bigtimer_dev.ircam.data.Media
import io.github.bigtimer_dev.ircam.data.MeasureParams
import io.github.bigtimer_dev.ircam.data.Settings
import io.github.bigtimer_dev.ircam.data.SettingsStore
import io.github.bigtimer_dev.ircam.data.SnapshotItem
import io.github.bigtimer_dev.ircam.data.VideoRecorder
import io.github.bigtimer_dev.ircam.thermal.Box
import io.github.bigtimer_dev.ircam.thermal.DemoSource
import io.github.bigtimer_dev.ircam.thermal.FrameResult
import io.github.bigtimer_dev.ircam.thermal.FrameSource
import io.github.bigtimer_dev.ircam.thermal.Palettes
import io.github.bigtimer_dev.ircam.thermal.Processor
import io.github.bigtimer_dev.ircam.thermal.RawFiles
import io.github.bigtimer_dev.ircam.thermal.RawMeta
import io.github.bigtimer_dev.ircam.thermal.SnapshotSource
import io.github.bigtimer_dev.ircam.thermal.Spot
import io.github.bigtimer_dev.ircam.thermal.Thermal
import io.github.bigtimer_dev.ircam.thermal.UsbPool2Source
import io.github.bigtimer_dev.ircam.ui.Renderer
import io.github.bigtimer_dev.ircam.ui.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.Executors

sealed interface CamState {
    /** No camera; [usbPresent] when a camera is plugged in but not yet permitted/opened. */
    data class Idle(val usbPresent: Boolean = false, val message: String? = null) : CamState
    data class Connecting(val name: String) : CamState
    data class Live(val name: String, val canSwitchRange: Boolean, val demo: Boolean) : CamState
    data class Analysis(val item: SnapshotItem) : CamState
}

enum class Tool { VIEW, SPOT, BOX }

data class RecState(val startedAt: Long)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val store = SettingsStore(app)
    val palettes = Palettes(app)
    val media = Media(app)
    val renderer = Renderer()
    private val processor = Processor(palettes)
    val emissivityTable: List<Pair<String, Double>> = runCatching {
        val a = JSONArray(app.assets.open("emissivity.json").bufferedReader().readText())
        List(a.length()) { a.getJSONArray(it).let { e -> e.getString(0) to e.getDouble(1) } }
    }.getOrDefault(emptyList())

    private val _state = MutableStateFlow<CamState>(CamState.Idle())
    val state = _state.asStateFlow()
    private val _frame = MutableStateFlow<FrameResult?>(null)
    val frame: StateFlow<FrameResult?> = _frame.asStateFlow()
    private val _settings = MutableStateFlow(store.load())

    // The camera is plugged into the phone, so it turns with it. The rotation setting is the
    // image orientation with the phone upright; the screen's rotation is added on top so the
    // scene stays upright when the phone is turned. While recording, the rotation is kept fixed
    // (the video frame size cannot change).
    private val displays = app.getSystemService(DisplayManager::class.java)
    @Volatile private var displayRot = currentDisplayRotation()
    @Volatile private var frozenDisplayRot: Int? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id == Display.DEFAULT_DISPLAY) setDisplayRotation(currentDisplayRotation())
        }
    }
    val settings = _settings.asStateFlow()
    /** Measurement parameters in use: the saved ones live, the file's ones in analysis. */
    private val _params = MutableStateFlow(_settings.value.params)
    val params = _params.asStateFlow()
    private val _spots = MutableStateFlow<List<Spot>>(emptyList())
    val spots = _spots.asStateFlow()
    private val _boxes = MutableStateFlow<List<Box>>(emptyList())
    val boxes = _boxes.asStateFlow()
    private val _tool = MutableStateFlow(Tool.VIEW)
    val tool = _tool.asStateFlow()
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection = _selection.asStateFlow()
    private val _highRange = MutableStateFlow<Boolean?>(null)
    val highRange = _highRange.asStateFlow()
    private val _rangeBusy = MutableStateFlow(false)
    val rangeBusy = _rangeBusy.asStateFlow()
    private val _recording = MutableStateFlow<RecState?>(null)
    val recording = _recording.asStateFlow()
    private val _lastThumb = MutableStateFlow<Uri?>(null)
    val lastThumb = _lastThumb.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private var source: FrameSource? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var paramsDirty = true
    private val lock = Any()
    private var lastRaw: ByteArray? = null
    private var recorder: VideoRecorder? = null
    private val control = Executors.newSingleThreadExecutor()
    private var liveDevice: UsbDevice? = null
    private var paused = false
    /** What to go back to after analysis. */
    private var resumeDemo = false

    // ------------------------------------------------------------------ USB

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.parcelableDevice()
            when (i.action) {
                ACTION_PERMISSION -> {
                    if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(dev)
                    else _state.value = CamState.Idle(true, "USB permission was not granted.")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null && dev.deviceName == liveDevice?.deviceName) {
                        stopSource()
                        liveDevice = null
                        _state.value = CamState.Idle(false, "Camera disconnected.")
                    } else if (_state.value is CamState.Idle) refreshIdle()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (dev != null && isThermalCamera(dev)) onAttached(dev)
            }
        }
    }

    init {
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(app, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshLastThumb()
    }

    private fun Intent.parcelableDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun isThermalCamera(d: UsbDevice) =
        d.vendorId == 0x0BDA && d.productId == 0x5830 ||
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == 0x0E }

    fun findCamera(): UsbDevice? {
        val all = usb.deviceList.values
        return all.firstOrNull { it.vendorId == 0x0BDA && it.productId == 0x5830 } ?: all.firstOrNull { isThermalCamera(it) }
    }

    private fun refreshIdle() {
        val s = _state.value
        if (s is CamState.Idle) _state.value = s.copy(usbPresent = findCamera() != null)
    }

    /** Called by the activity when launched/attached via the USB intent (permission is implied). */
    fun onAttached(dev: UsbDevice) {
        if (_state.value is CamState.Live && liveDevice?.deviceName == dev.deviceName) return
        if (_state.value is CamState.Analysis) { liveDevice = dev; return }
        requestOrConnect(dev)
    }

    /** Auto-start: use a plugged-in camera, otherwise stay on the connect screen. */
    fun autoStart() {
        if (_state.value !is CamState.Idle) return
        val dev = findCamera()
        if (dev != null) requestOrConnect(dev) else refreshIdle()
    }

    fun connectCamera() {
        val dev = findCamera()
        if (dev == null) {
            _state.value = CamState.Idle(false, "No thermal camera found. Plug it in (USB-C OTG) and try again.")
            return
        }
        requestOrConnect(dev)
    }

    /** Set when Android needs the CAMERA permission before it will grant USB access (UVC devices). */
    private val _needCameraPermission = MutableStateFlow<UsbDevice?>(null)
    val needCameraPermission = _needCameraPermission.asStateFlow()

    fun onCameraPermission(granted: Boolean) {
        val dev = _needCameraPermission.value ?: return
        _needCameraPermission.value = null
        if (granted) requestOrConnect(dev)
        else _state.value = CamState.Idle(true, "Android requires the Camera permission to use a USB thermal camera.")
    }

    private fun requestOrConnect(dev: UsbDevice) {
        if (usb.hasPermission(dev)) { connect(dev); return }
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _state.value = CamState.Idle(true, null)
            _needCameraPermission.value = dev
            return
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        _state.value = CamState.Idle(true, null)
        usb.requestPermission(dev, pi)
    }

    private fun connect(dev: UsbDevice) {
        liveDevice = dev
        resumeDemo = false
        start(UsbPool2Source(usb, dev))
    }

    fun startDemo() {
        resumeDemo = true
        start(DemoSource())
    }

    // ------------------------------------------------------------------ engine

    private fun start(src: FrameSource) {
        stopSource()
        val live = src.live
        _state.value = if (src is SnapshotSource) _state.value else CamState.Connecting(src.name)
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                try { src.open(); null } catch (e: Exception) { e.message ?: e.toString() }
            }
            if (err != null) {
                src.close()
                _state.value = CamState.Idle(findCamera() != null, err)
                return@launch
            }
            if (live) _params.value = _settings.value.params
            paramsDirty = live
            source = src
            if (live) {
                _state.value = CamState.Live(src.name, src.canSwitchRange, src is DemoSource)
                if (src.canSwitchRange) control.execute { _highRange.value = src.currentHighRange() }
                else _highRange.value = null
            }
            runWorker(src)
        }
    }

    private fun runWorker(src: FrameSource) {
        running = true
        worker = Thread({
            var frames = 0L
            var misses = 0
            val times = ArrayDeque<Long>()
            while (running) {
                try {
                    val raw = src.read()
                    if (raw == null) {
                        if (++misses == 8 && src.live) _messages.tryEmit("No frames from the camera…")
                        continue
                    }
                    misses = 0
                    if (!src.live) Thread.sleep(50)
                    if (paramsDirty) {
                        paramsDirty = false
                        val p = _params.value
                        src.cam.emissivity = p.emissivity; src.cam.reflected = p.reflected
                        src.cam.ambient = p.ambient; src.cam.humidity = p.humidity
                        src.cam.distance = p.distance; src.cam.tempCorrection = p.tempCorrection
                        Thermal.generateLut(src.cam)
                    }
                    val now = System.nanoTime()
                    times.addLast(now)
                    while (times.size > 1 && now - times.first() > 2_000_000_000L) times.removeFirst()
                    val fps = if (times.size > 1) (times.size - 1) * 1e9 / (now - times.first()) else 0.0
                    frames++
                    val s = effective(_settings.value)
                    val res = synchronized(lock) {
                        lastRaw = if (src.live) raw.copyOf() else raw
                        processor.process(src.cam, raw, s, _spots.value, _boxes.value, frames, if (src.live) fps else 0.0)
                    }
                    _frame.value = res
                    recorder?.let { r ->
                        try {
                            r.frame { c -> renderer.drawExport(c, res, s, _params.value.emissivity) }
                        } catch (e: Exception) {
                            _messages.tryEmit("Recording failed: ${e.message}")
                            stopRecording()
                        }
                    }
                } catch (e: IOException) {
                    if (running && src.live) {
                        running = false
                        stopRecording()
                        _state.value = CamState.Idle(findCamera() != null, e.message ?: "Camera error")
                        _frame.value = null
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    _messages.tryEmit("Processing error: ${e.message}")
                    Thread.sleep(200)
                }
            }
        }, "ircam-engine").also { it.start() }
    }

    private fun stopSource() {
        stopRecording()
        running = false
        worker?.let { it.join(2000) }
        worker = null
        source?.let { s -> control.execute { s.close() } }
        source = null
    }

    fun onBackground() {
        if (_recording.value != null) return
        val s = _state.value
        if (s is CamState.Live) {
            paused = true
            stopSource()
            _state.value = CamState.Connecting(s.name)
        }
    }

    fun onForeground() {
        if (!paused) return
        paused = false
        if (resumeDemo) startDemo()
        else {
            val dev = liveDevice?.takeIf { d -> usb.deviceList.values.any { it.deviceName == d.deviceName } }
            if (dev != null) requestOrConnect(dev) else _state.value = CamState.Idle(findCamera() != null)
        }
    }

    fun disconnect() {
        stopSource()
        liveDevice = null
        resumeDemo = false
        _frame.value = null
        _state.value = CamState.Idle(findCamera() != null)
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(f: (Settings) -> Settings) {
        val old = _settings.value
        val new = f(old)
        val eo = effective(old); val en = effective(new)
        if (en.rotation != eo.rotation || en.mirror != eo.mirror) transformObjects(eo, en)
        _settings.value = new
        store.save(new)
    }

    fun updateParams(p: MeasureParams, material: String? = null) {
        _params.value = p
        paramsDirty = true
        if (_state.value !is CamState.Analysis) {
            updateSettings { it.copy(params = p, emissivityMaterial = material ?: it.emissivityMaterial) }
        }
    }

    fun rotate() = updateSettings { it.copy(rotation = (it.rotation + 90) % 360) }

    private fun currentDisplayRotation(): Int =
        (displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0) * 90

    /** Extra clockwise image rotation that cancels the phone's turn (the sign flips when mirrored). */
    private fun compensation(s: Settings): Int {
        if (_state.value is CamState.Analysis) return 0
        val d = frozenDisplayRot ?: displayRot
        return if (s.mirror) d else (360 - d) % 360
    }

    /** Settings as used for drawing: the user's rotation plus the screen compensation. */
    private fun effective(s: Settings): Settings =
        s.copy(rotation = (s.rotation + compensation(s)) % 360)

    private fun setDisplayRotation(r: Int) {
        if (r == displayRot) return
        val old = effective(_settings.value)
        displayRot = r
        val new = effective(_settings.value)
        if (new.rotation != old.rotation) transformObjects(old, new)
    }

    /** Lock the colour range to what is shown now, or go back to automatic. */
    fun toggleRangeLock() {
        val f = _frame.value
        updateSettings {
            if (it.rangeLocked) it.copy(rangeLocked = false)
            else if (f != null) it.copy(rangeLocked = true, lockLo = f.barLo, lockHi = f.barHi)
            else it.copy(rangeLocked = true)
        }
    }

    fun setHighRange(high: Boolean) {
        val src = source ?: return
        if (!src.canSwitchRange || _rangeBusy.value) return
        _rangeBusy.value = true
        control.execute {
            try {
                src.setHighRange(high)
                Thread.sleep(800)
                _highRange.value = src.currentHighRange() ?: high
                _messages.tryEmit(if (high) "High range (up to ~600 °C)" else "Low range (−20…150 °C, more precise)")
            } catch (e: Exception) {
                _messages.tryEmit("Range switch failed: ${e.message}")
            } finally {
                _rangeBusy.value = false
            }
        }
    }

    /** Keep spots/boxes on the same scene point when the image is rotated or mirrored. */
    private fun transformObjects(old: Settings, new: Settings) {
        val f = _frame.value ?: return
        var w = f.width.toFloat(); var h = f.height.toFloat()
        var spots = _spots.value
        var boxes = _boxes.value
        if (old.mirror) { spots = spots.map { Spot(w - it.x, it.y) }; boxes = boxes.map { Box(w - it.x1, it.y0, w - it.x0, it.y1) } }
        var steps = (((new.rotation - old.rotation) / 90) % 4 + 4) % 4
        while (steps-- > 0) { // 90° clockwise: (x, y) -> (h - y, x)
            val hh = h
            spots = spots.map { Spot(hh - it.y, it.x) }
            boxes = boxes.map { Box(hh - it.y1, it.x0, hh - it.y0, it.x1) }
            val t = w; w = h; h = t
        }
        if (new.mirror) { spots = spots.map { Spot(w - it.x, it.y) }; boxes = boxes.map { Box(w - it.x1, it.y0, w - it.x0, it.y1) } }
        _spots.value = spots
        _boxes.value = boxes
    }

    // ------------------------------------------------------------------ measurement objects

    fun setTool(t: Tool) { _tool.value = t; _selection.value = null }
    fun select(s: Selection?) { _selection.value = s }

    fun addSpot(x: Float, y: Float) {
        if (_spots.value.size >= MAX_OBJECTS) { _messages.tryEmit("Up to $MAX_OBJECTS spots"); return }
        _spots.update { it + Spot(x, y) }
        _selection.value = Selection.SpotSel(_spots.value.size - 1)
    }

    fun moveSpot(i: Int, x: Float, y: Float) = _spots.update { l -> l.mapIndexed { j, s -> if (j == i) Spot(x, y) else s } }

    fun addBox(b: Box): Int {
        if (_boxes.value.size >= MAX_OBJECTS) { _messages.tryEmit("Up to $MAX_OBJECTS boxes"); return -1 }
        _boxes.update { it + b }
        return _boxes.value.size - 1
    }

    fun setBox(i: Int, b: Box) = _boxes.update { l -> l.mapIndexed { j, o -> if (j == i) b else o } }

    fun deleteSelected() {
        when (val s = _selection.value) {
            is Selection.SpotSel -> _spots.update { l -> l.filterIndexed { j, _ -> j != s.index } }
            is Selection.BoxSel -> _boxes.update { l -> l.filterIndexed { j, _ -> j != s.index } }
            null -> {}
        }
        _selection.value = null
    }

    fun clearObjects() {
        _spots.value = emptyList(); _boxes.value = emptyList(); _selection.value = null
    }

    // ------------------------------------------------------------------ snapshot / video

    fun snapshot() {
        val f = _frame.value ?: return
        val src = source ?: return
        val raw = synchronized(lock) { lastRaw?.copyOf() } ?: return
        val s = effective(_settings.value)
        val emi = _params.value.emissivity
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cam = src.cam
                val meta = RawMeta.of(cam)
                val rgb = RawFiles.buildRawFrame(raw, cam.width, cam.height, meta)
                val img = renderer.renderExport(f, s, emi)
                val item = media.saveSnapshot(img, rgb, cam.width, cam.height)
                img.recycle()
                _lastThumb.value = item.imageUri
                _messages.tryEmit("Snapshot saved to Pictures/IRCAM")
            } catch (e: Exception) {
                _messages.tryEmit("Snapshot failed: ${e.message}")
            }
        }
    }

    /** Analysis mode: save the current view (with the new palette/spots) as a new image. */
    fun saveAnalysisImage() {
        val f = _frame.value ?: return
        val s = _settings.value
        val emi = _params.value.emissivity
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val img = renderer.renderExport(f, s, emi)
                val uri = media.saveEditedImage(img)
                img.recycle()
                _lastThumb.value = uri
                _messages.tryEmit("Image saved to Pictures/IRCAM")
            } catch (e: Exception) {
                _messages.tryEmit("Save failed: ${e.message}")
            }
        }
    }

    fun toggleRecording() {
        if (_recording.value != null) { stopRecording(); return }
        val f = _frame.value ?: return
        try {
            val (w, h) = renderer.exportSize(f)
            val uri = media.newVideo()
            try {
                recorder = VideoRecorder(ctx, uri, w, h)
            } catch (e: Exception) {
                media.discard(uri); throw e
            }
            frozenDisplayRot = displayRot
            _recording.value = RecState(System.currentTimeMillis())
        } catch (e: Exception) {
            _messages.tryEmit("Could not start recording: ${e.message}")
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        _recording.value = null
        if (frozenDisplayRot != null) {
            val old = effective(_settings.value)
            frozenDisplayRot = null
            val new = effective(_settings.value)
            if (new.rotation != old.rotation) transformObjects(old, new)
        }
        control.execute {
            val ok = r.stop()
            if (ok) { media.finishVideo(r.uri); _messages.tryEmit("Video saved to Movies/IRCAM") }
            else { media.discard(r.uri); _messages.tryEmit("Recording was empty and has been discarded") }
        }
    }

    fun refreshLastThumb() {
        viewModelScope.launch(Dispatchers.IO) {
            _lastThumb.value = runCatching { media.listSnapshots().firstOrNull()?.imageUri }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ analysis

    fun openSnapshot(item: SnapshotItem) {
        viewModelScope.launch {
            val img = withContext(Dispatchers.IO) { runCatching { media.loadRaw(item.rawUri) }.getOrNull() }
            if (img == null) { _messages.tryEmit("This file is not an IRCAM RAW snapshot."); return@launch }
            val src = SnapshotSource(img, "Snapshot")
            val wasDemo = _state.value.let { it is CamState.Live && it.demo }
            stopSource()
            if (wasDemo) resumeDemo = true
            withContext(Dispatchers.IO) { src.open() }
            clearObjects()
            val c = src.cam
            _params.value = MeasureParams(c.emissivity, c.reflected, c.ambient, c.humidity, c.distance, c.tempCorrection)
            _state.value = CamState.Analysis(item)
            paramsDirty = false
            source = src
            _highRange.value = null
            runWorker(src)
        }
    }

    fun closeAnalysis() {
        stopSource()
        clearObjects()
        _frame.value = null
        _state.value = CamState.Idle(findCamera() != null)
        if (resumeDemo) startDemo()
        else liveDevice?.takeIf { d -> usb.deviceList.values.any { it.deviceName == d.deviceName } }?.let { requestOrConnect(it) }
            ?: autoStart()
    }

    override fun onCleared() {
        stopSource()
        runCatching { ctx.unregisterReceiver(usbReceiver) }
        displays.unregisterDisplayListener(displayListener)
        control.shutdown()
    }

    companion object {
        const val ACTION_PERMISSION = "io.github.bigtimer_dev.ircam.USB_PERMISSION"
        const val MAX_OBJECTS = 10
    }
}
