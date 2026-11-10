import SwiftUI

struct ContentView: View {
  @Bindable var controller: GlassesController

  var body: some View {
    NavigationStack {
      Form {
        statusSection
        registrationSection
        sessionSection
        streamSection
        captureSection
        audioSection
        ExtrasSection(extras: controller.extras, sessionStarted: controller.sessionState == .started)
        aiSection
        resultsSection
        logSection
      }
      .navigationTitle("GlassesLab · DAT PoC")
    }
  }

  // MARK: Secciones

  private var statusSection: some View {
    Section("Estado") {
      row("Registro", controller.registrationText)
      row("Lentes", controller.deviceText)
      row("Sesión", controller.sessionText)
      row("Stream", controller.streamStateText)
      row("Frames / FPS", "\(controller.framesReceived) / \(String(format: "%.1f", controller.fps)) · \(controller.frameSizeText)")
    }
  }

  private var registrationSection: some View {
    Section("1 · Registro con Meta AI (abre la app Meta AI)") {
      Button("Conectar app con Meta AI") { controller.register() }
        .disabled(controller.isRegistered)
      Button("Desconectar app", role: .destructive) { controller.unregister() }
        .disabled(!controller.isRegistered)
      Button("Abrir actualización de firmware") { controller.openFirmwareUpdate() }
    }
  }

  private var sessionSection: some View {
    Section("2 · Sesión con los lentes") {
      Button("Iniciar sesión") { controller.startSession() }
        .disabled(!controller.isRegistered || controller.hasSession)
      Button("Terminar sesión", role: .destructive) { controller.endSession() }
        .disabled(!controller.hasSession)
    }
  }

  private var streamSection: some View {
    Section("3 · Video en vivo") {
      Toggle("Incluir audio PCM (experimental)", isOn: $controller.includePCM)
        .disabled(controller.isStreaming)
      HStack {
        Button("Iniciar preview") { controller.startPreview() }
          .disabled(controller.sessionState != .started || controller.isStreaming)
        Spacer()
        Button("Detener", role: .destructive) { controller.stopPreview() }
          .disabled(!controller.isStreaming)
      }
      if let image = controller.previewImage, controller.isStreaming {
        Image(uiImage: image)
          .resizable()
          .scaledToFit()
          .frame(maxHeight: 420)
          .clipShape(RoundedRectangle(cornerRadius: 8))
      }
    }
  }

  private var captureSection: some View {
    Section("4 · Foto y video") {
      Button(controller.isCapturingPhoto ? "Capturando…" : "Foto durante streaming (F1)") {
        controller.capturePhoto()
      }
      .disabled(!controller.isStreaming || controller.isCapturingPhoto)

      Button(controller.isCapturingHQ ? "Capturando HQ…" : "Foto HQ sin streaming (F2, experimental)") {
        controller.captureHQPhoto()
      }
      .disabled(controller.sessionState != .started || controller.isStreaming || controller.isCapturingHQ)

      Button(controller.isRecordingVideo ? "⏹ Detener grabación (V1)" : "⏺ Grabar video (V1)") {
        if controller.isRecordingVideo {
          Task { await controller.stopVideo() }
        } else {
          controller.startVideo()
        }
      }
      .disabled(!controller.isStreaming)

      if let photo = controller.lastPhoto {
        Image(uiImage: photo)
          .resizable()
          .scaledToFit()
          .frame(maxHeight: 260)
      }
    }
  }

  private var audioSection: some View {
    Section("5 · Audio") {
      Button(controller.isRecordingPCM ? "⏹ Detener PCM DAT (A1)" : "⏺ Grabar PCM DAT (A1)") {
        controller.isRecordingPCM ? controller.stopPCM() : controller.startPCM()
      }
      .disabled(!controller.streamHasPCM || !controller.isStreaming)

      Button(controller.isRecordingHFP ? "⏹ Detener mic Bluetooth (A2)" : "⏺ Grabar mic Bluetooth HFP (A2)") {
        controller.isRecordingHFP ? controller.stopHFP() : controller.startHFP()
      }
      Text("A1 requiere streaming con PCM. A2 usa el perfil manos libres de iOS y no requiere cámara. Graba ≥ 5 s hablando.")
        .font(.footnote)
        .foregroundStyle(.secondary)
    }
  }

  private var aiSection: some View {
    Section("6 · IA (Claude)") {
      if !controller.hasSavedKey {
        SecureField("API key de Anthropic", text: $controller.apiKeyInput)
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
        Button("Guardar key en Keychain") { controller.saveAPIKey() }
      }
      TextField("Pregunta", text: $controller.aiPrompt, axis: .vertical)
      Button(controller.aiBusy ? "Analizando…" : "Enviar última foto a Claude (IA1)") {
        controller.sendLastPhotoToAI()
      }
      .disabled(controller.aiBusy || controller.lastPhoto == nil || !controller.hasSavedKey)
      if !controller.aiResponse.isEmpty {
        Text(controller.aiResponse).font(.callout).textSelection(.enabled)
      }
    }
  }

  private var resultsSection: some View {
    Section("Pruebas de aceptación") {
      ForEach(controller.results) { result in
        VStack(alignment: .leading, spacing: 4) {
          HStack {
            Text("\(result.id) · \(result.title)").font(.subheadline.bold())
            Spacer()
            Text(result.status.rawValue)
              .font(.caption.bold())
              .padding(.horizontal, 6)
              .padding(.vertical, 2)
              .background(color(result.status).opacity(0.2))
              .foregroundStyle(color(result.status))
              .clipShape(Capsule())
          }
          Text("Criterio: \(result.criterion)").font(.caption).foregroundStyle(.secondary)
          if !result.detail.isEmpty {
            Text(result.detail).font(.caption).textSelection(.enabled)
          }
          if let file = result.evidenceFile {
            Text("Evidencia: \(file)").font(.caption2).foregroundStyle(.secondary)
          }
        }
      }
      Button("Preparar paquete de evidencia (.zip)") { controller.prepareEvidenceZip() }
      if let zip = controller.evidenceZipURL {
        ShareLink(item: zip) { Label("Compartir \(zip.lastPathComponent)", systemImage: "square.and.arrow.up") }
      }
      Button("Reiniciar resultados", role: .destructive) { controller.resetResults() }
    }
  }

  private var logSection: some View {
    Section("Registro de eventos") {
      ForEach(Array(controller.logLines.suffix(60).enumerated().reversed()), id: \.offset) { item in
        Text(item.element).font(.system(.caption2, design: .monospaced))
      }
    }
  }

  // MARK: Helpers

  private func row(_ title: String, _ value: String) -> some View {
    HStack(alignment: .top) {
      Text(title).foregroundStyle(.secondary)
      Spacer()
      Text(value).multilineTextAlignment(.trailing).font(.callout)
    }
  }

  private func color(_ status: TestStatus) -> Color {
    switch status {
    case .pass: return .green
    case .fail: return .red
    case .notRun: return .gray
    }
  }
}
