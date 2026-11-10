package com.glasseslab.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.removeMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Capacidades experimentales de DAT 1.0.x (solo dev/beta, no publicables en producción): Inputs,
 * Motion, Speech y Voice Invocations. Según la guía oficial deben habilitarse para la app en
 * Wearables Developer Center; en Developer Mode puede que no lo exijan. Las pruebas lo dirán.
 */
class Extras(private val vm: LabViewModel) {
  private val scope get() = vm.viewModelScope

  // ---------- Estado visible ----------
  var inputsActive by mutableStateOf(false)
  val inputEvents = mutableStateListOf<String>()
  var captureEvents by mutableStateOf(0)
  var touchEvents by mutableStateOf(0)
  var captureTriggersPhoto by mutableStateOf(true)

  var motionActive by mutableStateOf(false)
  var motionSamples by mutableStateOf(0)
  var motionText by mutableStateOf("—")

  var speechActive by mutableStateOf(false)
  var speechState by mutableStateOf("—")
  var speechLocale by mutableStateOf("—")
  var partialTranscript by mutableStateOf("")
  val finalTranscripts = mutableStateListOf<String>()

  var voiceListening by mutableStateOf(false)
  var voiceText by mutableStateOf("Inactivo")

  // ---------- Privado ----------
  private var inputs: Inputs? = null
  private val inputJobs = mutableListOf<Job>()
  private var shortPressTicket = 0

  private var motion: Motion? = null
  private val motionJobs = mutableListOf<Job>()
  private var motionCsv: File? = null
  private val motionRows = mutableListOf<String>()
  private val accelMagnitudes = mutableListOf<Double>()
  private var maxGyro = 0.0
  private var motionEvaluated = false
  private var motionStarted = false

  private var speech: Speech? = null
  private val speechJobs = mutableListOf<Job>()
  private var speechStarted = false
  private var transcriptFile: File? = null

  private val voiceJobs = mutableListOf<Job>()
  private var voiceClose: (() -> Unit)? = null
  private val prefs = vm.getApplication<android.app.Application>().getSharedPreferences("glasseslab", Context.MODE_PRIVATE)

  private fun startedSession() =
      vm.session.takeIf { vm.sessionStarted }.also {
        if (it == null) vm.log("Inicia la sesión con los lentes primero (estado STARTED).")
      }

  // ======================= Inputs (I1) =======================

  fun startInputs() {
    if (inputs != null) return
    val session = startedSession() ?: return
    // Agregar Inputs lo activa automáticamente (no tiene start()).
    session
        .addInputs(InputsConfiguration(consumeBack = true))
        .fold(
            onSuccess = { added ->
              inputs = added
              inputsActive = true
              inputJobs += scope.launch { added.events.collect { onInput(it) } }
              inputJobs += scope.launch { added.state.collect { vm.log("Inputs: $it") } }
              inputJobs +=
                  scope.launch {
                    added.errors.collect { error ->
                      vm.log("❌ Inputs: $error")
                      if (captureEvents == 0 && touchEvents == 0) {
                        vm.setResult("I1", TestStatus.FAIL, "Inputs error: $error")
                      }
                    }
                  }
              vm.log("Inputs activo: presiona el botón de captura y desliza/toca la patilla")
            },
            onFailure = { error, _ -> vm.setResult("I1", TestStatus.FAIL, "addInputs: ${error.description}") },
        )
  }

  fun stopInputs(removeFromSession: Boolean = true) {
    inputJobs.forEach { it.cancel() }
    inputJobs.clear()
    if (removeFromSession) vm.session?.removeInputs()
    inputs = null
    inputsActive = false
  }

  private fun onInput(event: InputEvent) {
    val text = event.toString()
    inputEvents.add(0, text)
    while (inputEvents.size > 20) inputEvents.removeAt(inputEvents.size - 1)
    vm.evidence.append("input_event", mapOf("event" to text))
    when (event) {
      is InputEvent.Capture -> {
        captureEvents++
        val press = event.pressType.toString().uppercase()
        if (press.contains("DOUBLE")) {
          shortPressTicket++ // known issue oficial: un doble toque llega como SHORT + DOUBLE
        } else if (press.contains("SHORT")) {
          scheduleCapturePhoto()
        }
      }
      else -> touchEvents++
    }
    if (captureEvents >= 1 && touchEvents >= 1 && vm.result("I1")?.status != TestStatus.PASS) {
      vm.setResult("I1", TestStatus.PASS, "eventos captura=$captureEvents táctiles=$touchEvents. Último: $text")
    }
  }

  /** Espera 400 ms antes de tomar la foto, para descartar el SHORT_PRESS de un doble toque. */
  private fun scheduleCapturePhoto() {
    if (!captureTriggersPhoto) return
    val ticket = ++shortPressTicket
    scope.launch {
      delay(400)
      if (shortPressTicket != ticket) return@launch
      if (vm.isStreaming) {
        vm.log("Botón de captura → foto (F1)")
        vm.capturePhoto()
      } else {
        vm.log("Botón de captura recibido; inicia el preview para que dispare una foto")
      }
    }
  }

  // ======================= Motion (M1) =======================

  fun startMotion() {
    if (motion != null) return
    val session = startedSession() ?: return
    session
        .addMotion(MotionConfiguration(samplingRate = MotionSamplingRate.HZ_30))
        .fold(
            onSuccess = { added ->
              motion = added
              motionActive = true
              motionSamples = 0
              accelMagnitudes.clear()
              maxGyro = 0.0
              motionEvaluated = false
              motionStarted = false
              motionCsv = vm.evidence.newCaptureFile("M1_motion", "csv")
              motionRows.clear()
              motionRows.add("timestamp_ns,source,ax,ay,az,gx,gy,gz,mx,my,mz,orientation")
              motionJobs += scope.launch { added.samples.collect { onMotion(it) } }
              motionJobs +=
                  scope.launch {
                    added.state.collect { state ->
                      val s = state.toString().uppercase()
                      vm.log("Motion: $state")
                      if (s == "STARTED") motionStarted = true
                      // Una foto puede detener Motion (observado en el sample oficial BirdSpotter iOS).
                      if (s == "STOPPED" && motionStarted && motionActive) {
                        vm.log("Motion se detuvo solo; reiniciando…")
                        added.start()
                      }
                    }
                  }
              motionJobs +=
                  scope.launch {
                    added.errors.collect { error ->
                      vm.log("❌ Motion: $error")
                      if (motionSamples == 0) vm.setResult("M1", TestStatus.FAIL, "Motion error: $error")
                    }
                  }
              // A diferencia de Inputs, Motion se entrega detenido: hay que llamar start().
              added.start()
              vm.log("Motion iniciado a 30 Hz: mueve la cabeza")
            },
            onFailure = { error, _ -> vm.setResult("M1", TestStatus.FAIL, "addMotion: ${error.description}") },
        )
  }

  fun stopMotion(removeFromSession: Boolean = true) {
    motionActive = false
    motion?.stop()
    motionJobs.forEach { it.cancel() }
    motionJobs.clear()
    if (removeFromSession) vm.session?.removeMotion()
    motion = null
    flushCsv()
  }

  private fun f(v: Double) = "%.3f".format(java.util.Locale.US, v)

  private fun onMotion(sample: MotionSample) {
    motionSamples++
    val row = mutableListOf(sample.timestampNs.toString(), sample.source.toString())
    var text = ""
    val a = sample.accelerometer
    if (a != null) {
      val x = a.x.toDouble()
      val y = a.y.toDouble()
      val z = a.z.toDouble()
      val magnitude = sqrt(x * x + y * y + z * z)
      accelMagnitudes.add(magnitude)
      text = "acc (${f(x)}, ${f(y)}, ${f(z)}) |a|=${"%.2f".format(magnitude)} m/s²"
      row += listOf(f(x), f(y), f(z))
    } else {
      row += listOf("", "", "")
    }
    val g = sample.gyroscope
    if (g != null) {
      val x = g.x.toDouble()
      val y = g.y.toDouble()
      val z = g.z.toDouble()
      maxGyro = maxOf(maxGyro, sqrt(x * x + y * y + z * z))
      text += " · gyro (${f(x)}, ${f(y)}, ${f(z)}) rad/s"
      row += listOf(f(x), f(y), f(z))
    } else {
      row += listOf("", "", "")
    }
    val m = sample.magnetometer
    row += if (m != null) listOf(f(m.x.toDouble()), f(m.y.toDouble()), f(m.z.toDouble())) else listOf("", "", "")
    row += "\"" + (sample.orientation?.toString() ?: "") + "\""
    motionRows.add(row.joinToString(","))
    if (motionRows.size >= 60) flushCsv()
    motionText = text

    if (motionSamples >= 90 && !motionEvaluated) {
      motionEvaluated = true
      flushCsv()
      val file = motionCsv?.name
      if (accelMagnitudes.isEmpty()) {
        vm.setResult("M1", TestStatus.FAIL, "$motionSamples muestras sin acelerómetro", file)
      } else {
        val mean = accelMagnitudes.average()
        val ok = mean > 7 && mean < 12.5
        vm.setResult(
            "M1",
            if (ok) TestStatus.PASS else TestStatus.FAIL,
            "muestras=$motionSamples con acelerómetro=${accelMagnitudes.size} |a| medio=${"%.2f".format(mean)} m/s² (gravedad≈9.81) gyro máx=${"%.2f".format(maxGyro)} rad/s",
            file)
      }
    }
  }

  private fun flushCsv() {
    val file = motionCsv ?: return
    if (motionRows.isEmpty()) return
    file.appendText(motionRows.joinToString("\n") + "\n")
    motionRows.clear()
  }

  // ======================= Speech (SP1) =======================

  fun startSpeech() {
    if (speech != null) return
    val session = startedSession() ?: return
    scope.launch {
      if (!vm.ensurePermission(Permission.MICROPHONE)) {
        vm.setResult("SP1", TestStatus.FAIL, "Permiso de micrófono (DAT) no concedido")
        return@launch
      }
      session
          .addSpeech()
          .fold(
              onSuccess = { added ->
                speech = added
                speechActive = true
                speechStarted = false
                transcriptFile = vm.evidence.newCaptureFile("SP1_transcripcion", "txt")
                speechJobs +=
                    scope.launch {
                      added.transcriptions.collect { result ->
                        result ?: return@collect
                        onTranscript(result.text, result.isFinal, result.confidence.toString())
                      }
                    }
                speechJobs +=
                    scope.launch {
                      added.state.collect { state ->
                        speechState = state.toString()
                        vm.log("Speech: $state")
                        val s = state.toString().uppercase()
                        if (s == "STARTED") speechStarted = true
                        // Known issue oficial: tras ~1 minuto deja de transcribir. Si se detiene solo, reinicia.
                        if (s == "STOPPED" && speechStarted && speechActive) {
                          vm.log("Speech se detuvo solo; reiniciando…")
                          added.start()
                        }
                      }
                    }
                speechJobs += scope.launch { added.locale.collect { speechLocale = it.toString() } }
                speechJobs +=
                    scope.launch {
                      added.errors.collect { error ->
                        vm.log("❌ Speech: $error")
                        if (finalTranscripts.isEmpty()) vm.setResult("SP1", TestStatus.FAIL, "Speech error: $error")
                      }
                    }
                added.start().onFailure { error, _ ->
                  vm.setResult("SP1", TestStatus.FAIL, "speech.start: ${error.description}")
                }
                vm.log("Speech iniciado: di una frase clara cerca de los lentes")
              },
              onFailure = { error, _ -> vm.setResult("SP1", TestStatus.FAIL, "addSpeech: ${error.description}") },
          )
    }
  }

  fun stopSpeech(removeFromSession: Boolean = true) {
    speechActive = false
    speech?.stop()
    speechJobs.forEach { it.cancel() }
    speechJobs.clear()
    if (removeFromSession) vm.session?.removeSpeech()
    speech = null
    partialTranscript = ""
  }

  private fun onTranscript(text: String, isFinal: Boolean, confidence: String) {
    if (!isFinal) {
      partialTranscript = text
      return
    }
    partialTranscript = ""
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return
    finalTranscripts.add(0, trimmed)
    transcriptFile?.appendText("${vm.evidence.iso()} [conf=$confidence] $trimmed\n")
    vm.evidence.append("speech_final", mapOf("text" to trimmed, "confidence" to confidence, "locale" to speechLocale))
    if (vm.result("SP1")?.status != TestStatus.PASS) {
      vm.setResult("SP1", TestStatus.PASS, "texto=\"$trimmed\" confianza=$confidence locale=$speechLocale", transcriptFile?.name)
    }
  }

  // ======================= Voice Invocations (VI1) =======================

  /** No requiere sesión de cámara. Requiere configurar el nombre hablado en Wearables Developer Center. */
  fun startVoice() {
    if (voiceListening) return
    voiceListening = true
    voiceText = "Esperando registro…"
    prefs.edit().putBoolean("voice_auto", true).apply()
    voiceJobs +=
        scope.launch {
          // Meta AI solo entrega invocaciones a una app registrada (patrón del sample BirdSpotter).
          Wearables.registrationState.first { it == RegistrationState.REGISTERED }
          val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
          voiceClose = { stream.close() }
          voiceJobs += scope.launch { stream.state.collect { voiceText = "Estado: $it" } }
          voiceJobs +=
              scope.launch {
                stream.errors.collect { error ->
                  vm.log("❌ Voice: $error")
                  voiceText = "Error: $error"
                }
              }
          vm.log("Voice Invocations activo: di \"Hey Meta, start <nombre de la app>\"")
          stream.invocations.collect { invocation ->
            vm.evidence.append("voice_invocation", mapOf("invocation" to invocation.toString()))
            if (invocation is LaunchApp) {
              // Toda invocación debe confirmarse exactamente una vez.
              val delivered = invocation.responseHandle.sendSuccess(actionOutput = null)
              vm.log("Voice: LaunchApp recibido, confirmación entregada=$delivered")
              vm.setResult(
                  "VI1",
                  if (delivered) TestStatus.PASS else TestStatus.FAIL,
                  "LaunchApp recibido; sendSuccess entregado=$delivered")
            } else {
              vm.log("Voice: invocación no manejada $invocation")
            }
          }
        }
  }

  fun stopVoice() {
    prefs.edit().putBoolean("voice_auto", false).apply()
    closeVoice()
  }

  private fun closeVoice() {
    voiceJobs.forEach { it.cancel() }
    voiceJobs.clear()
    voiceClose?.invoke()
    voiceClose = null
    voiceListening = false
    voiceText = "Inactivo"
  }

  /** Reanuda la escucha al detectar los lentes si el usuario la dejó activa. */
  fun deviceAvailable() {
    if (!voiceListening && prefs.getBoolean("voice_auto", false)) startVoice()
  }

  // ======================= Fin de sesión / cierre =======================

  fun sessionEnded() {
    if (inputs != null) stopInputs(removeFromSession = false)
    if (motion != null) stopMotion(removeFromSession = false)
    if (speech != null) stopSpeech(removeFromSession = false)
  }

  fun close() {
    sessionEnded()
    closeVoice()
  }
}
