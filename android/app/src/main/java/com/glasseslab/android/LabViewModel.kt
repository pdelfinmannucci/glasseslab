package com.glasseslab.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.photo.types.PhotoQuality
import com.meta.wearable.dat.camera.photo.types.PhotoResolution
import com.meta.wearable.dat.camera.photo.types.PhotoState
import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.AudioInputHandler
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcDecoder
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.HevcParameterSetCollector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.RecordingResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.VideoRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orquesta el ciclo de vida oficial de DAT 1.0.x en Android: initialize → registro (Meta AI) →
 * DeviceSession → addCamera → stream.start → captura/grabación → stop. Cada prueba de aceptación
 * queda registrada con evidencia en filesDir/GlassesLab.
 */
class LabViewModel(application: Application) : AndroidViewModel(application) {
  private val app: Application = application
  val evidence = EvidenceStore(application)
  val extras = Extras(this)
  val mock = MockMode(this)
  /** true mientras el MockDeviceKit está activo: los resultados se marcan [SIMULADO]. */
  var simulated by mutableStateOf(false)

  // ---------- Estado visible ----------
  val results = mutableStateListOf<TestResult>().apply { addAll(evidence.load() ?: TestCatalog.initial) }
  val logLines = mutableStateListOf<String>()
  var registrationText by mutableStateOf("—")
  var isRegistered by mutableStateOf(false)
  var deviceText by mutableStateOf("Sin dispositivo")
  var sessionText by mutableStateOf("sin sesión")
  var sessionStarted by mutableStateOf(false)
  var hasSession by mutableStateOf(false)
  var streamText by mutableStateOf("STOPPED")
  var isStreaming by mutableStateOf(false)
  var streamHasPcm by mutableStateOf(false)
  var includePcm by mutableStateOf(false)
  var includeAudioInVideo by mutableStateOf(false)
  var framesReceived by mutableStateOf(0)
  var fps by mutableStateOf(0.0)
  var frameSize by mutableStateOf("—")
  var isCapturingPhoto by mutableStateOf(false)
  var isCapturingHq by mutableStateOf(false)
  var hqProgress by mutableStateOf("")
  var isRecordingVideo by mutableStateOf(false)
  var isRecordingPcm by mutableStateOf(false)
  var isRecordingHfp by mutableStateOf(false)
  var lastPhoto by mutableStateOf<Bitmap?>(null)
  var lastPhotoName by mutableStateOf<String?>(null)
  var aiPrompt by mutableStateOf("¿Qué componente se ve y hay alguna falla visible?")
  var aiResponse by mutableStateOf("")
  var aiBusy by mutableStateOf(false)
  var apiKeyInput by mutableStateOf("")
  var hasSavedKey by mutableStateOf(false)
  var evidenceZip by mutableStateOf<File?>(null)

  // ---------- Conexión con la Activity ----------
  var requestWearablesPermission: (suspend (Permission) -> PermissionStatus)? = null
  var requestRecordAudio: (suspend () -> Boolean)? = null
  var requestPhoneCamera: (suspend () -> Boolean)? = null
  /** La UI entrega un snapshot del preview (TextureView.getBitmap) para la evidencia de S1. */
  var snapshotProvider: (() -> Bitmap?)? = null

  // ---------- Privado ----------
  internal var session: DeviceSession? = null
  internal var currentDeviceId: String? = null
  private var camera: Camera? = null
  private var monitoring = false
  private val sessionJobs = mutableListOf<Job>()
  private val streamJobs = mutableListOf<Job>()
  private val hqJobs = mutableListOf<Job>()
  private var deviceJob: Job? = null
  private var currentDeviceType = "—"
  private var currentFirmware = "—"
  private var streamWasActive = false

  private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
  private val decoderLock = Any()
  @Volatile private var decoder: HevcDecoder? = null
  @Volatile private var decoderSurface: Surface? = null
  private val csdCollector = HevcParameterSetCollector()
  private val frameCounter = AtomicInteger(0)
  @Volatile private var lastFrameSize = "—"
  private var streamStartMs = 0L

  private val videoAudioHandler = AudioInputHandler(application)
  private val videoRecorder = VideoRecorder(application, viewModelScope)
  private val hfpHandler = AudioInputHandler(application)
  private var hfpWav: WavWriter? = null
  @Volatile private var pcmWav: WavWriter? = null
  private val pcmBuffersReceived = AtomicInteger(0)

  private val prefs = application.getSharedPreferences("glasseslab", Context.MODE_PRIVATE)

  init {
    hasSavedKey = prefs.getString("anthropic_key", null) != null
    log("App iniciada. Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    evidence.append("app_start", environment())
    // Contador de frames → UI cada 500 ms (los frames llegan en un hilo de fondo).
    viewModelScope.launch {
      var lastCount = 0
      var lastTime = System.currentTimeMillis()
      while (isActive) {
        delay(500)
        val now = System.currentTimeMillis()
        val count = frameCounter.get()
        fps = if (now > lastTime) (count - lastCount) * 1000.0 / (now - lastTime) else 0.0
        lastCount = count
        lastTime = now
        framesReceived = count
        frameSize = lastFrameSize
        if (count >= 150 && isStreaming && result("S1")?.status != TestStatus.PASS) passStreaming(count)
      }
    }
  }

  // ======================= Registro (R0) y dispositivo (D0) =======================

  fun startMonitoring() {
    if (monitoring) return
    monitoring = true
    viewModelScope.launch {
      Wearables.registrationState.collect { state ->
        registrationText = state.toString()
        isRegistered = state == RegistrationState.REGISTERED
        log("Registro: $state")
        if (isRegistered && result("R0")?.status != TestStatus.PASS) {
          setResult("R0", TestStatus.PASS, "registrationState=REGISTERED, devMode=${Wearables.isDevMode}")
        }
      }
    }
    viewModelScope.launch {
      Wearables.devices.collect { ids ->
        val id = ids.firstOrNull()
        if (id != currentDeviceId) watchDevice(id)
        currentDeviceId = id
        if (id != null) extras.deviceAvailable()
      }
    }
  }

  private fun watchDevice(id: String?) {
    deviceJob?.cancel()
    if (id == null) {
      deviceText = "Sin dispositivo"
      return
    }
    deviceJob = viewModelScope.launch {
      Wearables.devicesMetadata[id]?.collect { device -> onDevice(device) }
    }
  }

  private fun onDevice(d: Device) {
    deviceText =
        "${d.name.ifEmpty { "lentes" }} · ${d.deviceType} · ${d.linkState} · batería ${d.batteryLevel}% · " +
            "firmware ${d.firmwareInfo ?: "?"} · ${d.donState}"
    if (d.deviceType == DeviceType.UNKNOWN) return
    currentDeviceType = d.deviceType.toString()
    currentFirmware = d.firmwareInfo ?: "?"
    if (result("D0")?.status == TestStatus.PASS) return
    val detail =
        "nombre=${d.name} tipo=${d.deviceType} firmware=${d.firmwareInfo} compat=${d.compatibility} link=${d.linkState}"
    evidence.append("device_detected", mapOf("detail" to detail))
    when {
      d.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED ->
          setResult("D0", TestStatus.FAIL, "Requiere actualizar firmware. $detail")
      d.deviceType == DeviceType.META_GLASSES -> setResult("D0", TestStatus.PASS, detail)
      else -> setResult("D0", TestStatus.FAIL, "Tipo distinto de META_GLASSES. $detail")
    }
  }

  fun register(activity: android.app.Activity) {
    log("Iniciando registro: se abrirá Meta AI…")
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: android.app.Activity) = Wearables.startUnregistration(activity)

  fun openFirmwareUpdate(activity: android.app.Activity) {
    Wearables.openFirmwareUpdate(activity).onFailure { error, _ -> log("❌ firmware: ${error.description}") }
  }

  // ======================= Sesión =======================

  fun startSession() {
    if (session != null) return
    Wearables.createSession(AutoDeviceSelector())
        .fold(
            onSuccess = { created ->
              session = created
              hasSession = true
              sessionJobs += viewModelScope.launch { created.state.collect { onSessionState(it) } }
              sessionJobs +=
                  viewModelScope.launch {
                    created.errors.collect { error -> log("❌ Sesión: ${error.description}") }
                  }
              created.start()
              log("Sesión iniciando…")
            },
            onFailure = { error, _ -> log("❌ createSession: ${error.description}") },
        )
  }

  fun endSession() {
    session?.stop()
  }

  private fun onSessionState(state: DeviceSessionState) {
    sessionText = state.toString()
    sessionStarted = state == DeviceSessionState.STARTED
    log("Sesión: $state")
    if (state == DeviceSessionState.STOPPED) {
      sessionJobs.forEach { it.cancel() }
      sessionJobs.clear()
      session = null
      hasSession = false
      sessionStarted = false
      clearStream()
      extras.sessionEnded()
    }
  }

  internal suspend fun ensurePermission(permission: Permission): Boolean {
    val current = Wearables.checkPermissionStatus(permission).getOrNull()
    if (current == PermissionStatus.Granted) return true
    val request = requestWearablesPermission ?: return false
    log("Solicitando permiso $permission: se abrirá Meta AI…")
    val status = request(permission)
    log("Permiso $permission: $status")
    return status == PermissionStatus.Granted
  }

  // ======================= Streaming (S1) =======================

  fun setSurface(surface: Surface?) {
    synchronized(decoderLock) {
      decoderSurface = surface
      if (surface == null) {
        decoder?.stop()
        decoder = null
      }
    }
  }

  fun startPreview() {
    val current = session ?: return log("Inicia la sesión primero.")
    if (!sessionStarted || camera != null) return
    val withPcm = includePcm
    viewModelScope.launch {
      if (!ensurePermission(Permission.CAMERA)) {
        setResult("S1", TestStatus.FAIL, "Permiso de cámara no concedido")
        return@launch
      }
      if (withPcm && !ensurePermission(Permission.MICROPHONE)) {
        setResult("A1", TestStatus.FAIL, "Permiso de micrófono (DAT) no concedido")
        return@launch
      }
      // HEVC comprimido: se decodifica en el TextureView y se graba directo a MP4 (igual que el sample).
      val config =
          StreamConfiguration(
              audioCodec = if (withPcm) AudioCodec.PCM(AudioSampleRate.RATE_16000, 1) else null,
              videoQuality = VideoQuality.MEDIUM,
              frameRate = 24,
              compressVideo = true,
          )
      current
          .addCamera(config)
          .fold(
              onSuccess = { added ->
                camera = added
                streamHasPcm = withPcm
                frameCounter.set(0)
                pcmBuffersReceived.set(0)
                streamStartMs = System.currentTimeMillis()
                streamWasActive = false
                attachStream(added.stream, withPcm)
                streamText = "STARTING"
                added.stream.start().onFailure { error, _ ->
                  log("❌ stream.start: ${error.description}")
                  clearStream()
                }
                log("Stream solicitado: HEVC 504x896 @24fps, PCM=$withPcm")
                evidence.append("stream_requested", mapOf("quality" to "MEDIUM", "fps" to "24", "pcm" to "$withPcm"))
              },
              onFailure = { error, _ -> log("❌ addCamera: ${error.description}") },
          )
    }
  }

  private fun attachStream(stream: Stream, withPcm: Boolean) {
    streamJobs += viewModelScope.launch(frameDispatcher) { stream.videoStream.collect { onFrame(it) } }
    streamJobs += viewModelScope.launch { stream.state.collect { onStreamState(it) } }
    streamJobs +=
        viewModelScope.launch {
          stream.errorStream.collect { error ->
            log("❌ Stream: ${error.description}")
            evidence.append("stream_error", mapOf("error" to error.toString()))
            if (withPcm && error.toString().contains("AUDIO", ignoreCase = true)) {
              setResult("A1", TestStatus.FAIL, "Error de audio: $error")
            }
          }
        }
    if (withPcm) {
      streamJobs +=
          viewModelScope.launch(Dispatchers.IO) {
            stream.audioStream.collect { frame ->
              pcmBuffersReceived.incrementAndGet()
              val writer = pcmWav ?: return@collect
              val buffer = frame.buffer.duplicate().order(ByteOrder.nativeOrder())
              val bytes = ByteArray(buffer.remaining())
              buffer.get(bytes)
              writer.write(bytes, 0, bytes.size)
            }
          }
    }
  }

  private fun onFrame(frame: VideoFrame) {
    if (!frame.isCompressed) return
    val buffer = frame.buffer
    val bytes = ByteArray(buffer.remaining())
    val position = buffer.position()
    buffer.get(bytes)
    buffer.position(position)
    csdCollector.offer(bytes)
    synchronized(decoderLock) {
      val surface = decoderSurface
      if (decoder == null && surface != null) {
        decoder =
            HevcDecoder().also { d ->
              d.start(frame.width, frame.height, surface)
              csdCollector.complete()?.let { d.decodeFrame(it, 0) }
            }
      }
      decoder?.decodeFrame(bytes, frame.presentationTimeUs)
    }
    if (videoRecorder.isRecording.value) {
      videoRecorder.writeCompressedFrame(bytes, frame.presentationTimeUs, frame.width, frame.height, frame.isCodecConfig)
    }
    if (!frame.isCodecConfig) {
      frameCounter.incrementAndGet()
      lastFrameSize = "${frame.width}x${frame.height}"
    }
  }

  private fun onStreamState(state: StreamState) {
    streamText = state.toString()
    isStreaming = state == StreamState.STREAMING
    val terminal = state == StreamState.STOPPED || state == StreamState.CLOSED
    if (!terminal) {
      streamWasActive = true
    } else if (streamWasActive) {
      streamWasActive = false
      if (result("S1")?.status == TestStatus.NOT_RUN && frameCounter.get() > 0) {
        setResult("S1", TestStatus.FAIL, "El stream se detuvo tras ${frameCounter.get()} frames (<150)")
      }
      clearStream()
    }
  }

  private fun passStreaming(count: Int) {
    val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000.0
    var file: String? = null
    snapshotProvider?.invoke()?.let { bmp ->
      val f = evidence.newCaptureFile("S1_frame", "jpg")
      FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
      file = f.name
    }
    setResult(
        "S1",
        TestStatus.PASS,
        "$count frames en ${"%.1f".format(elapsed)} s, fps≈${"%.1f".format(fps)}, frame=$lastFrameSize, dispositivo=$currentDeviceType",
        file)
  }

  fun stopPreview() {
    if (isRecordingVideo) viewModelScope.launch { stopVideo() }
    if (isRecordingPcm) stopPcm()
    streamText = "STOPPING"
    camera?.stop()
    clearStream()
  }

  private fun clearStream() {
    streamJobs.forEach { it.cancel() }
    streamJobs.clear()
    synchronized(decoderLock) {
      decoder?.stop()
      decoder = null
    }
    csdCollector.reset()
    if (!isCapturingHq) {
      camera?.stop()
      camera = null
    }
    isStreaming = false
    streamHasPcm = false
    streamText = "STOPPED"
  }

  // ======================= Foto durante streaming (F1) =======================

  fun capturePhoto() {
    val stream = camera?.stream ?: return log("La foto requiere streaming activo.")
    if (!isStreaming || isCapturingPhoto) return
    isCapturingPhoto = true
    viewModelScope.launch {
      val result = stream.capturePhoto()
      result.fold(
          onSuccess = { photo -> onStreamPhoto(photo) },
          onFailure = { error, _ -> setResult("F1", TestStatus.FAIL, "capturePhoto: ${error.description}") },
      )
      isCapturingPhoto = false
    }
  }

  private fun onStreamPhoto(photo: PhotoData) {
    when (photo) {
      is PhotoData.Bitmap -> {
        val f = evidence.newCaptureFile("F1_foto", "jpg")
        FileOutputStream(f).use { photo.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        lastPhoto = photo.bitmap
        lastPhotoName = f.name
        setResult("F1", TestStatus.PASS, "formato=Bitmap resolución=${photo.bitmap.width}x${photo.bitmap.height} bytes=${f.length()}", f.name)
      }
      is PhotoData.HEIC -> {
        val buffer = photo.data.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val f = evidence.newCaptureFile("F1_foto", "heic")
        f.writeBytes(bytes)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
          lastPhoto = bmp
          lastPhotoName = f.name
          setResult("F1", TestStatus.PASS, "formato=HEIC resolución=${bmp.width}x${bmp.height} bytes=${bytes.size}", f.name)
        } else {
          setResult("F1", TestStatus.FAIL, "${bytes.size} bytes HEIC no decodificables", f.name)
        }
      }
    }
  }

  // ======================= Foto HQ independiente (F2, experimental) =======================

  fun captureHqPhoto() {
    val current = session ?: return log("Inicia la sesión primero.")
    if (!sessionStarted) return
    if (camera != null) return log("Detén el preview antes de la foto HQ: Stream y Photo compiten por la cámara.")
    isCapturingHq = true
    hqProgress = ""
    viewModelScope.launch {
      if (!ensurePermission(Permission.CAMERA)) {
        isCapturingHq = false
        setResult("F2", TestStatus.FAIL, "Permiso de cámara no concedido")
        return@launch
      }
      current
          .addCamera(StreamConfiguration())
          .fold(
              onSuccess = { added ->
                camera = added
                startHq(added)
              },
              onFailure = { error, _ ->
                isCapturingHq = false
                setResult("F2", TestStatus.FAIL, "addCamera: ${error.description}")
              },
          )
    }
  }

  private fun startHq(cam: Camera) {
    val photo = cam.photo // acceder crea e inicia el hijo Photo
    var requested = false
    hqJobs +=
        viewModelScope.launch {
          photo.state.collect { state ->
            log("Photo: $state")
            if (state == PhotoState.STARTED && !requested) {
              requested = true
              photo.capturePhoto(resolution = PhotoResolution.FULL, quality = PhotoQuality.HIGH)
              log("capturePhoto(FULL, HIGH)")
            }
          }
        }
    hqJobs +=
        viewModelScope.launch {
          photo.photoStream.collect { capture ->
            val f = evidence.newCaptureFile("F2_fotoHQ", "jpg")
            f.writeBytes(capture.imageData)
            val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(capture.imageData, 0, capture.imageData.size) }
            if (bmp != null) {
              lastPhoto = bmp
              lastPhotoName = f.name
              setResult("F2", TestStatus.PASS, "bytes=${capture.imageData.size} resolución=${bmp.width}x${bmp.height}", f.name)
            } else {
              setResult("F2", TestStatus.FAIL, "${capture.imageData.size} bytes no decodificables", f.name)
            }
            finishHq()
          }
        }
    hqJobs += viewModelScope.launch { photo.transferProgressStream.collect { hqProgress = it.toString() } }
    hqJobs +=
        viewModelScope.launch {
          photo.errors.collect { error ->
            log("❌ Photo: $error")
            setResult("F2", TestStatus.FAIL, "Photo error: $error")
            finishHq()
          }
        }
    hqJobs +=
        viewModelScope.launch {
          delay(60_000)
          if (isCapturingHq) {
            setResult("F2", TestStatus.FAIL, "Tiempo agotado (60 s) esperando la foto HQ")
            finishHq()
          }
        }
  }

  private fun finishHq() {
    viewModelScope.launch {
      isCapturingHq = false
      val jobs = hqJobs.toList()
      hqJobs.clear()
      jobs.forEach { it.cancel() }
      camera?.stop()
      camera = null
    }
  }

  // ======================= Video (V1) =======================

  fun toggleVideo() {
    if (isRecordingVideo) {
      viewModelScope.launch { stopVideo() }
      return
    }
    if (!isStreaming) return log("La grabación requiere streaming activo.")
    viewModelScope.launch {
      var withAudio = includeAudioInVideo
      if (withAudio) withAudio = requestRecordAudio?.invoke() ?: false
      videoRecorder.setAudioInputHandler(videoAudioHandler)
      videoRecorder.setIncludeAudio(withAudio)
      isRecordingVideo = true
      val audioOn = videoRecorder.startRecording(csdCollector.complete())
      log("Grabando video (audio de los lentes=${withAudio && audioOn})")
    }
  }

  suspend fun stopVideo() {
    if (!isRecordingVideo) return
    var waited = 0L
    while (!videoRecorder.hasStartedWriting.value && waited < 500) {
      delay(25)
      waited += 25
    }
    isRecordingVideo = false
    when (val result = videoRecorder.stopRecording()) {
      is RecordingResult.Completed -> {
        val f = evidence.newCaptureFile("V1_video", "mp4")
        withContext(Dispatchers.IO) {
          app.contentResolver.openInputStream(result.uri)?.use { input ->
            FileOutputStream(f).use { input.copyTo(it) }
          }
          runCatching { app.contentResolver.delete(result.uri, null, null) }
        }
        val (durationMs, size) =
            withContext(Dispatchers.IO) {
              runCatching {
                    MediaMetadataRetriever().use { r ->
                      r.setDataSource(f.absolutePath)
                      val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                      val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                      val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                      d to "${w}x$h"
                    }
                  }
                  .getOrDefault(0L to "?")
            }
        val detail = "duración=${"%.1f".format(durationMs / 1000.0)} s, tamaño=$size, bytes=${f.length()}"
        setResult("V1", if (durationMs >= 3000) TestStatus.PASS else TestStatus.FAIL, detail, f.name)
      }
      RecordingResult.NoRecording -> setResult("V1", TestStatus.FAIL, "No se grabó ningún frame (grabación muy corta)")
      RecordingResult.Failed -> setResult("V1", TestStatus.FAIL, "No se pudo guardar el MP4")
    }
  }

  // ======================= Audio PCM por DAT (A1, experimental) =======================

  fun startPcm() {
    if (!isStreaming || !streamHasPcm) return log("Activa 'Incluir audio PCM' antes de iniciar el preview.")
    val f = evidence.newCaptureFile("A1_pcm_dat", "wav")
    pcmWav = WavWriter(f, 16_000, 1)
    isRecordingPcm = true
    log("Grabando PCM DAT (habla cerca de los lentes). Buffers recibidos hasta ahora: ${pcmBuffersReceived.get()}")
  }

  fun stopPcm() {
    val writer = pcmWav ?: return
    pcmWav = null
    isRecordingPcm = false
    writer.close()
    val detail = "segundos=${"%.1f".format(writer.seconds)} rms=${"%.4f".format(writer.rms)} buffers=${pcmBuffersReceived.get()} formato=PCM16 16 kHz mono"
    val ok = writer.seconds >= 3 && writer.rms > 0.003
    setResult("A1", if (ok) TestStatus.PASS else TestStatus.FAIL, detail, writer.file.name)
  }

  // ======================= Micrófono por Bluetooth HFP (A2) =======================

  fun startHfp() {
    if (isRecordingHfp) return
    viewModelScope.launch {
      if (requestRecordAudio?.invoke() != true) {
        setResult("A2", TestStatus.FAIL, "Permiso RECORD_AUDIO denegado")
        return@launch
      }
      val routed = withContext(Dispatchers.IO) { hfpHandler.prepare() }
      if (!routed) {
        setResult("A2", TestStatus.FAIL, "No se pudo enrutar el micrófono Bluetooth SCO de los lentes")
        return@launch
      }
      val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
      val route = audioManager.communicationDevice?.let { "${it.productName} (tipo ${it.type})" } ?: "?"
      val f = evidence.newCaptureFile("A2_hfp", "wav")
      val wav = WavWriter(f, AudioInputHandler.SAMPLE_RATE, 1)
      hfpWav = wav
      hfpHandler.pcmDataCallback = { data, offset, size -> wav.write(data, offset, size) }
      if (!hfpHandler.startRecording()) {
        hfpHandler.pcmDataCallback = null
        wav.close()
        hfpWav = null
        setResult("A2", TestStatus.FAIL, "AudioRecord no inició por Bluetooth. Ruta: $route")
        return@launch
      }
      isRecordingHfp = true
      evidence.append("hfp_route", mapOf("route" to route))
      log("Grabando micrófono Bluetooth (habla cerca de los lentes): $route")
    }
  }

  fun stopHfp() {
    val wav = hfpWav ?: return
    hfpWav = null
    isRecordingHfp = false
    hfpHandler.pcmDataCallback = null
    hfpHandler.stopRecording()
    wav.close()
    val detail = "segundos=${"%.1f".format(wav.seconds)} rms=${"%.4f".format(wav.rms)} formato=PCM16 ${AudioInputHandler.SAMPLE_RATE} Hz mono (SCO)"
    val ok = wav.seconds >= 3 && wav.rms > 0.003
    setResult("A2", if (ok) TestStatus.PASS else TestStatus.FAIL, detail, wav.file.name)
  }

  // ======================= IA (IA1) =======================

  fun saveApiKey() {
    val key = apiKeyInput.trim()
    if (key.isEmpty()) return
    prefs.edit().putString("anthropic_key", key).apply()
    apiKeyInput = ""
    hasSavedKey = true
    log("API key guardada en el almacenamiento privado de la app.")
  }

  fun sendLastPhotoToAi() {
    val image = lastPhoto ?: return log("Primero captura una foto (F1 o F2).")
    val key = prefs.getString("anthropic_key", null) ?: return log("Falta la API key de Anthropic.")
    aiBusy = true
    aiResponse = ""
    val prompt = aiPrompt
    val photoName = lastPhotoName
    viewModelScope.launch {
      val started = System.currentTimeMillis()
      runCatching { withContext(Dispatchers.IO) { ClaudeClient.analyze(image, prompt, key) } }
          .onSuccess { r ->
            aiResponse = r.text
            val f = evidence.newCaptureFile("IA1_respuesta", "txt")
            f.writeText("Foto: $photoName\nPrompt: $prompt\nModelo: ${r.model}\n\n${r.text}")
            val latency = (System.currentTimeMillis() - started) / 1000.0
            setResult(
                "IA1",
                TestStatus.PASS,
                "modelo=${r.model} stop=${r.stopReason} tokens in/out=${r.inputTokens}/${r.outputTokens} latencia=${"%.1f".format(latency)} s foto=$photoName",
                f.name)
          }
          .onFailure { e ->
            aiResponse = "Error: ${e.message}"
            setResult("IA1", TestStatus.FAIL, e.message ?: e.toString())
          }
      aiBusy = false
    }
  }

  // ======================= Voz (arranque por "Hey Meta") =======================

  fun enableMock() {
    viewModelScope.launch {
      val camera = requestPhoneCamera?.invoke() ?: false
      mock.enable(hasPhoneCamera = camera)
    }
  }

  fun onVoiceLaunchIntent() {
    log("La app fue abierta por una invocación de voz (Hey Meta)")
    evidence.append("voice_launch_intent", environment())
  }

  // ======================= Evidencia =======================

  fun prepareEvidenceZip() {
    viewModelScope.launch {
      runCatching { withContext(Dispatchers.IO) { evidence.makeZip() } }
          .onSuccess {
            evidenceZip = it
            log("Paquete de evidencia listo: ${it.name}")
          }
          .onFailure { log("❌ Zip: ${it.message}") }
    }
  }

  fun resetResults() {
    results.clear()
    results.addAll(TestCatalog.initial)
    evidence.save(results)
    log("Resultados reiniciados (los archivos se conservan).")
  }

  internal fun result(id: String): TestResult? = results.firstOrNull { it.id == id }

  internal fun setResult(id: String, status: TestStatus, detail: String, file: String? = null) {
    val index = results.indexOfFirst { it.id == id }
    if (index < 0) return
    val shown = if (simulated) "[SIMULADO] $detail" else detail
    results[index] =
        results[index].copy(status = status, detail = shown, evidenceFile = file, timestamp = evidence.iso())
    evidence.save(results)
    evidence.append(
        "test_result",
        environment() + mapOf("test" to id, "status" to status.label, "detail" to shown, "file" to (file ?: "")))
    log("[$id] ${status.label}: $shown")
  }

  private fun environment(): Map<String, String> =
      mapOf(
          "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
          "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
          "app_version" to "0.1.0",
          "sdk" to "mwdat-android 1.0.1",
          "deviceType" to currentDeviceType,
          "firmware" to currentFirmware,
          "mode" to if (simulated) "SIMULADO (MockDeviceKit)" else "lentes reales",
      )

  fun log(message: String) {
    val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + message
    viewModelScope.launch(Dispatchers.Main) {
      logLines.add(line)
      while (logLines.size > 300) logLines.removeAt(0)
    }
  }

  override fun onCleared() {
    super.onCleared()
    session?.stop()
    videoRecorder.close()
    videoAudioHandler.cleanup()
    hfpHandler.cleanup()
    extras.close()
  }
}
