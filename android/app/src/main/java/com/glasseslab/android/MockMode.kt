package com.glasseslab.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSource
import com.meta.wearable.dat.motion.types.Vector3
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modo simulado con el MockDeviceKit oficial de Meta: lentes "Meta Glasses" virtuales para probar la
 * app en el emulador de Android sin teléfono ni lentes.
 *
 * Simula: registro, permisos, cámara (usa la cámara del emulador/teléfono), foto, Inputs, Motion,
 * Speech y Voice Invocations. NO simula audio PCM (A1) ni micrófono Bluetooth (A2).
 * Todo resultado obtenido así se marca [SIMULADO]: no prueba nada sobre los lentes reales.
 */
class MockMode(private val vm: LabViewModel) {
  var enabled by mutableStateOf(false)
  var status by mutableStateOf("Apagado")

  private val kit by lazy { MockDeviceKit.getInstance(vm.getApplication()) }
  private var glasses: MockGlasses? = null

  /** Requiere el permiso CAMERA de Android: la cámara del emulador alimenta el video simulado. */
  fun enable(hasPhoneCamera: Boolean) {
    if (enabled) return
    vm.viewModelScope.launch {
      runCatching {
            withContext(Dispatchers.Default) {
              kit.enable() // registro simulado y permisos concedidos por defecto
              val paired = kit.pairGlasses(GlassesModel.META_GLASSES).getOrNull() ?: error("pairGlasses falló")
              paired.powerOn()
              paired.unfold()
              paired.don()
              if (hasPhoneCamera) paired.services.camera.setCameraFeed(CameraFacing.BACK)
              val photo = Uri.fromFile(makeTestPhoto())
              paired.services.camera.setCapturedImage(photo)
              paired.services.cameraCapture.setCapturedPhoto(photo)
              paired.services.motion.setMotionFeed(motionFeed(), true)
              paired
            }
          }
          .onSuccess {
            glasses = it
            enabled = true
            vm.simulated = true
            status = "Activo: Meta Glasses simulados" + if (hasPhoneCamera) " (video = cámara del emulador)" else " (sin video: falta permiso de cámara)"
            vm.log("🧪 MODO SIMULADO activado. Los resultados se marcarán [SIMULADO].")
          }
          .onFailure {
            status = "Error: ${it.message}"
            vm.log("❌ MockDeviceKit: ${it.message}")
          }
    }
  }

  fun disable() {
    if (!enabled) return
    vm.endSession()
    runCatching {
      glasses?.let { kit.unpairDevice(it) }
      kit.disable()
    }
    glasses = null
    enabled = false
    vm.simulated = false
    status = "Apagado"
    vm.log("Modo simulado desactivado.")
  }

  // ---------- Inyección de eventos (equivalen a tocar los lentes) ----------

  fun pressCapture() = inject("botón de captura") { it.services.input.capture(CapturePressType.SHORT_PRESS) }

  fun swipe() = inject("deslizar en la patilla") { it.services.input.navDown(InputSource.CAPTOUCH) }

  fun tapSelect() = inject("toque") { it.services.input.select(InputSource.CAPTOUCH) }

  fun say(text: String) =
      inject("voz \"$text\"") { it.services.speech.simulateTranscription(text, true, 0.95f) }

  fun heyMeta() =
      inject("\"Hey Meta, start GlassesLab\"") { it.services.voiceInvocation.simulateLaunchAppAction() }

  private fun inject(label: String, action: (MockGlasses) -> Unit) {
    val g = glasses ?: return vm.log("Activa primero el modo simulado.")
    runCatching { action(g) }
        .onSuccess { vm.log("🧪 Simulado: $label") }
        .onFailure { vm.log("❌ Simulación ($label): ${it.message}") }
  }

  // ---------- Datos simulados ----------

  /** Foto de prueba que devolverá la "cámara" simulada en F1/F2. */
  private fun makeTestPhoto(): File {
    val file = File(vm.getApplication<android.app.Application>().cacheDir, "mock_photo.jpg")
    val bmp = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.rgb(30, 34, 44))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 130, 32); textSize = 64f }
    canvas.drawText("FOTO SIMULADA · MockDeviceKit", 80f, 460f, paint)
    paint.color = Color.WHITE
    paint.textSize = 40f
    canvas.drawText("No proviene de los lentes reales", 80f, 540f, paint)
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    return file
  }

  /** 2 s de cabeza moviéndose suavemente a 30 Hz, con la gravedad en el eje X (como el sample BirdSpotter). */
  private fun motionFeed(): List<MotionSample> {
    val hz = 30
    val g = 9.81f
    val intervalNs = 1_000_000_000L / hz
    return List(hz * 2) { i ->
      val t = i.toFloat() / hz
      val tilt = 0.3f * sin(t * 3.14f)
      MotionSample(
          i * intervalNs,
          Vector3(g * (1 - tilt * tilt / 2), g * tilt, 0f),
          Vector3(0f, 0.5f * kotlin.math.cos(t * 3.14f), 0f),
          null,
          null,
          MotionSource.GLASSES,
      )
    }
  }
}
