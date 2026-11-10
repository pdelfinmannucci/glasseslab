import SwiftUI

/// UI de las capacidades experimentales: Inputs, Motion, Speech y Voice Invocations.
struct ExtrasSection: View {
  @Bindable var extras: ExtraCapabilities
  let sessionStarted: Bool

  var body: some View {
    Section("7 · Botones y gestos (Inputs, I1)") {
      Toggle("Botón de captura toma foto (si hay preview)", isOn: $extras.captureTriggersPhoto)
      Button(extras.inputsActive ? "Detener Inputs" : "Activar Inputs") {
        extras.inputsActive ? extras.stopInputs() : extras.startInputs()
      }
      .disabled(!sessionStarted && !extras.inputsActive)
      Text("Captura: \(extras.captureEvents) · Táctiles: \(extras.touchEvents)").font(.caption)
      ForEach(Array(extras.inputEvents.prefix(5).enumerated()), id: \.offset) { item in
        Text(item.element).font(.system(.caption2, design: .monospaced))
      }
    }

    Section("8 · Sensores de movimiento (Motion, M1)") {
      Button(extras.motionActive ? "Detener Motion" : "Iniciar Motion (30 Hz)") {
        extras.motionActive ? extras.stopMotion() : extras.startMotion()
      }
      .disabled(!sessionStarted && !extras.motionActive)
      Text("Muestras: \(extras.motionSamples)").font(.caption)
      Text(extras.motionText).font(.system(.caption2, design: .monospaced))
    }

    Section("9 · Voz a texto en los lentes (Speech, SP1)") {
      Button(extras.speechActive ? "Detener Speech" : "Iniciar Speech") {
        extras.speechActive ? extras.stopSpeech() : extras.startSpeech()
      }
      .disabled(!sessionStarted && !extras.speechActive)
      Text("Estado: \(extras.speechStateText) · Idioma: \(extras.speechLocale)").font(.caption)
      if !extras.partialTranscript.isEmpty {
        Text("… \(extras.partialTranscript)").font(.callout).foregroundStyle(.secondary)
      }
      ForEach(Array(extras.finalTranscripts.prefix(5).enumerated()), id: \.offset) { item in
        Text("✓ \(item.element)").font(.callout)
      }
    }

    Section("10 · \"Hey Meta, start …\" (Voice Invocations, VI1)") {
      Button(extras.voiceListening ? "Dejar de escuchar" : "Escuchar invocaciones de voz") {
        extras.voiceListening ? extras.stopVoice() : extras.startVoice()
      }
      Text(extras.voiceText).font(.caption)
      Text("Requiere configurar el nombre hablado de la app en Wearables Developer Center. No necesita sesión de cámara.")
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
  }
}
