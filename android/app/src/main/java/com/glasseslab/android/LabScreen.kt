package com.glasseslab.android

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider

@Composable
fun LabScreen(vm: LabViewModel, activity: Activity) {
  val extras = vm.extras
  Column(
      modifier =
          Modifier.fillMaxSize()
              .background(Color.White)
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("GlassesLab · DAT PoC (Android)", fontSize = 20.sp, fontWeight = FontWeight.Bold)

    MockSection(vm)

    Section("Estado") {
      Info("Registro", vm.registrationText)
      Info("Lentes", vm.deviceText)
      Info("Sesión", vm.sessionText)
      Info("Stream", vm.streamText)
      Info("Frames / FPS", "${vm.framesReceived} / ${"%.1f".format(vm.fps)} · ${vm.frameSize}")
    }

    Section("1 · Registro con Meta AI (abre la app Meta AI)") {
      Btn("Conectar app con Meta AI", enabled = !vm.isRegistered) { vm.register(activity) }
      Btn("Desconectar app", enabled = vm.isRegistered, outlined = true) { vm.unregister(activity) }
      Btn("Abrir actualización de firmware", outlined = true) { vm.openFirmwareUpdate(activity) }
    }

    Section("2 · Sesión con los lentes") {
      Btn("Iniciar sesión", enabled = vm.isRegistered && !vm.hasSession) { vm.startSession() }
      Btn("Terminar sesión", enabled = vm.hasSession, outlined = true) { vm.endSession() }
    }

    Section("3 · Video en vivo") {
      Toggle("Incluir audio PCM (experimental)", vm.includePcm, enabled = !vm.isStreaming) { vm.includePcm = it }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Iniciar preview", enabled = vm.sessionStarted && !vm.isStreaming) { vm.startPreview() }
        Btn("Detener", enabled = vm.isStreaming, outlined = true) { vm.stopPreview() }
      }
      // El TextureView se mantiene siempre: el decodificador HEVC dibuja en su Surface.
      AndroidView(
          factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener =
                  object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                      vm.setSurface(Surface(st))
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                      vm.setSurface(null)
                      return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                  }
              vm.snapshotProvider = { this.bitmap }
            }
          },
          modifier =
              Modifier.fillMaxWidth(0.7f)
                  .aspectRatio(504f / 896f)
                  .clip(RoundedCornerShape(8.dp))
                  .background(Color.Black),
      )
    }

    Section("4 · Foto y video") {
      Btn(if (vm.isCapturingPhoto) "Capturando…" else "Foto durante streaming (F1)", enabled = vm.isStreaming && !vm.isCapturingPhoto) {
        vm.capturePhoto()
      }
      Btn(
          if (vm.isCapturingHq) "Capturando HQ… ${vm.hqProgress}" else "Foto HQ sin streaming (F2, experimental)",
          enabled = vm.sessionStarted && !vm.isStreaming && !vm.isCapturingHq,
      ) {
        vm.captureHqPhoto()
      }
      Toggle("Grabar con audio de los lentes", vm.includeAudioInVideo, enabled = !vm.isRecordingVideo) { vm.includeAudioInVideo = it }
      Btn(if (vm.isRecordingVideo) "⏹ Detener grabación (V1)" else "⏺ Grabar video (V1)", enabled = vm.isStreaming || vm.isRecordingVideo) {
        vm.toggleVideo()
      }
      vm.lastPhoto?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Última foto",
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        )
      }
    }

    Section("5 · Audio") {
      Btn(if (vm.isRecordingPcm) "⏹ Detener PCM DAT (A1)" else "⏺ Grabar PCM DAT (A1)", enabled = vm.isStreaming && vm.streamHasPcm) {
        if (vm.isRecordingPcm) vm.stopPcm() else vm.startPcm()
      }
      Btn(if (vm.isRecordingHfp) "⏹ Detener mic Bluetooth (A2)" else "⏺ Grabar mic Bluetooth (A2)") {
        if (vm.isRecordingHfp) vm.stopHfp() else vm.startHfp()
      }
      Note("A1 requiere preview con PCM activado. A2 usa el perfil manos libres Bluetooth y no requiere cámara. Graba ≥ 5 s hablando.")
    }

    Section("6 · Botones y gestos (Inputs, I1)") {
      Toggle("Botón de captura toma foto (si hay preview)", extras.captureTriggersPhoto) { extras.captureTriggersPhoto = it }
      Btn(if (extras.inputsActive) "Detener Inputs" else "Activar Inputs", enabled = vm.sessionStarted || extras.inputsActive) {
        if (extras.inputsActive) extras.stopInputs() else extras.startInputs()
      }
      Note("Captura: ${extras.captureEvents} · Táctiles: ${extras.touchEvents}")
      extras.inputEvents.take(5).forEach { Mono(it) }
    }

    Section("7 · Sensores de movimiento (Motion, M1)") {
      Btn(if (extras.motionActive) "Detener Motion" else "Iniciar Motion (30 Hz)", enabled = vm.sessionStarted || extras.motionActive) {
        if (extras.motionActive) extras.stopMotion() else extras.startMotion()
      }
      Note("Muestras: ${extras.motionSamples}")
      Mono(extras.motionText)
    }

    Section("8 · Voz a texto en los lentes (Speech, SP1)") {
      Btn(if (extras.speechActive) "Detener Speech" else "Iniciar Speech", enabled = vm.sessionStarted || extras.speechActive) {
        if (extras.speechActive) extras.stopSpeech() else extras.startSpeech()
      }
      Note("Estado: ${extras.speechState} · Idioma: ${extras.speechLocale}")
      if (extras.partialTranscript.isNotEmpty()) Note("… ${extras.partialTranscript}")
      extras.finalTranscripts.take(5).forEach { Text("✓ $it") }
    }

    Section("9 · \"Hey Meta, start …\" (Voice Invocations, VI1)") {
      Btn(if (extras.voiceListening) "Dejar de escuchar" else "Escuchar invocaciones de voz") {
        if (extras.voiceListening) extras.stopVoice() else extras.startVoice()
      }
      Note(extras.voiceText)
      Note("Requiere configurar el nombre hablado de la app en Wearables Developer Center. No necesita sesión de cámara.")
    }

    Section("10 · IA (Claude)") {
      if (!vm.hasSavedKey) {
        OutlinedTextField(
            value = vm.apiKeyInput,
            onValueChange = { vm.apiKeyInput = it },
            label = { Text("API key de Anthropic") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Btn("Guardar key") { vm.saveApiKey() }
      }
      OutlinedTextField(
          value = vm.aiPrompt,
          onValueChange = { vm.aiPrompt = it },
          label = { Text("Pregunta") },
          modifier = Modifier.fillMaxWidth(),
      )
      Btn(if (vm.aiBusy) "Analizando…" else "Enviar última foto a Claude (IA1)", enabled = !vm.aiBusy && vm.lastPhoto != null && vm.hasSavedKey) {
        vm.sendLastPhotoToAi()
      }
      if (vm.aiResponse.isNotEmpty()) Text(vm.aiResponse, fontSize = 14.sp)
    }

    Section("Pruebas de aceptación") {
      vm.results.forEach { r ->
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.id} · ${r.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                r.status.label,
                color =
                    when (r.status) {
                      TestStatus.PASS -> Color(0xFF1B7F3B)
                      TestStatus.FAIL -> Color(0xFFC62828)
                      TestStatus.NOT_RUN -> Color.Gray
                    },
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
          }
          Note("Criterio: ${r.criterion}")
          if (r.detail.isNotEmpty()) Text(r.detail, fontSize = 12.sp)
          r.evidenceFile?.let { Note("Evidencia: $it") }
        }
        HorizontalDivider()
      }
      Btn("Preparar paquete de evidencia (.zip)") { vm.prepareEvidenceZip() }
      vm.evidenceZip?.let { zip ->
        Btn("Compartir ${zip.name}") {
          val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", zip)
          val send =
              Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
          activity.startActivity(Intent.createChooser(send, "Compartir evidencia"))
        }
      }
      Btn("Reiniciar resultados", outlined = true) { vm.resetResults() }
    }

    Section("Registro de eventos") { vm.logLines.takeLast(60).reversed().forEach { Mono(it) } }
    Spacer(Modifier.padding(24.dp))
  }
}

@Composable
private fun MockSection(vm: LabViewModel) {
  val mock = vm.mock
  Section("🧪 Modo simulado (sin lentes ni teléfono)") {
    Note("Lentes Meta Glasses virtuales con el MockDeviceKit oficial de Meta. Sirve para probar la app en el emulador. Los resultados quedan marcados [SIMULADO] y NO prueban los lentes reales. No simula audio (A1, A2).")
    Info("Estado", mock.status)
    if (!mock.enabled) {
      Btn("Activar lentes simulados") { vm.enableMock() }
    } else {
      Note("Luego usa los pasos 2 → 10 como con lentes reales. Para simular gestos y voz:")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Botón captura", outlined = true) { mock.pressCapture() }
        Btn("Deslizar", outlined = true) { mock.swipe() }
        Btn("Tocar", outlined = true) { mock.tapSelect() }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Decir frase", outlined = true) { mock.say("revisar presión de neumáticos") }
        Btn("Hey Meta", outlined = true) { mock.heyMeta() }
      }
      Btn("Desactivar modo simulado", outlined = true) { mock.disable() }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(title, fontWeight = FontWeight.Bold)
      content()
    }
  }
}

@Composable
private fun Info(label: String, value: String) {
  Row {
    Text(label, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.weight(0.35f))
    Text(value, fontSize = 13.sp, modifier = Modifier.weight(0.65f))
  }
}

@Composable
private fun Btn(text: String, enabled: Boolean = true, outlined: Boolean = false, onClick: () -> Unit) {
  if (outlined) {
    OutlinedButton(onClick = onClick, enabled = enabled) { Text(text) }
  } else {
    Button(onClick = onClick, enabled = enabled) { Text(text) }
  }
}

@Composable
private fun Toggle(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text, modifier = Modifier.weight(1f), fontSize = 14.sp)
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}

@Composable private fun Note(text: String) = Text(text, fontSize = 12.sp, color = Color.Gray)

@Composable private fun Mono(text: String) = Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
