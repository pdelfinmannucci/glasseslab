import Foundation

enum TestStatus: String, Codable {
  case notRun = "NO EJECUTADA"
  case pass = "PASÓ"
  case fail = "FALLÓ"
}

struct TestResult: Codable, Identifiable {
  let id: String
  var title: String
  var criterion: String
  var status: TestStatus = .notRun
  var detail: String = ""
  var evidenceFile: String?
  var timestamp: Date?
}

enum TestCatalog {
  static let initial: [TestResult] = [
    TestResult(id: "R0", title: "Registro de la app en Meta AI",
               criterion: "registrationState == registered"),
    TestResult(id: "D0", title: "Detección del modelo de lentes",
               criterion: "Device.deviceType() == metaGlasses y compatibility no requiere update"),
    TestResult(id: "S1", title: "Streaming de video en vivo",
               criterion: "StreamState == streaming y ≥150 frames recibidos"),
    TestResult(id: "F1", title: "Foto durante streaming (stream.capturePhoto)",
               criterion: "JPEG recibido, decodificable, guardado en disco"),
    TestResult(id: "F2", title: "Foto HQ independiente (camera.photo, experimental)",
               criterion: "PhotoCaptureData recibido, decodificable, guardado"),
    TestResult(id: "V1", title: "Grabación de video (frames del stream → MP4)",
               criterion: "MP4 con pista de video y duración ≥ 3 s"),
    TestResult(id: "A1", title: "Audio PCM por DAT (experimental, requiere streaming)",
               criterion: "WAV ≥ 3 s con RMS > 0.003 (hablar durante la prueba)"),
    TestResult(id: "A2", title: "Audio del micrófono de los lentes por Bluetooth HFP (iOS)",
               criterion: "Ruta de entrada = bluetoothHFP, WAV ≥ 3 s, RMS > 0.003"),
    TestResult(id: "I1", title: "Inputs: botón de captura y panel táctil (experimental)",
               criterion: "≥1 evento .capture y ≥1 evento táctil (nav/select/back/drag)"),
    TestResult(id: "M1", title: "Motion: IMU de los lentes (experimental)",
               criterion: "≥90 muestras; |aceleración| media entre 7 y 12.5 m/s² (gravedad)"),
    TestResult(id: "SP1", title: "Speech: transcripción en los lentes (experimental)",
               criterion: "≥1 transcripción final no vacía"),
    TestResult(id: "VI1", title: "Voice Invocations: \"Hey Meta, start …\" (experimental)",
               criterion: "LaunchApp recibido y sendSuccess entregado"),
    TestResult(id: "IA1", title: "Envío de la foto capturada a la API de Claude",
               criterion: "HTTP 200, stop_reason ≠ refusal, texto no vacío"),
  ]
}

/// Persistencia de evidencia en Documents/GlassesLab (visible en la app Archivos).
final class EvidenceStore: @unchecked Sendable {
  let root: URL
  let captures: URL
  private let logURL: URL
  private let resultsURL: URL
  private let lock = NSLock()

  init() {
    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    root = docs.appendingPathComponent("GlassesLab", isDirectory: true)
    captures = root.appendingPathComponent("captures", isDirectory: true)
    logURL = root.appendingPathComponent("evidence.jsonl")
    resultsURL = root.appendingPathComponent("results.json")
    try? FileManager.default.createDirectory(at: captures, withIntermediateDirectories: true)
  }

  static func stamp(_ date: Date = Date()) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyyMMdd-HHmmss"
    return f.string(from: date)
  }

  func newCaptureURL(prefix: String, ext: String) -> URL {
    captures.appendingPathComponent("\(prefix)_\(Self.stamp()).\(ext)")
  }

  func append(event: String, fields: [String: String]) {
    var record = fields
    record["event"] = event
    record["time"] = ISO8601DateFormatter().string(from: Date())
    guard
      let data = try? JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]),
      var line = String(data: data, encoding: .utf8)
    else { return }
    line += "\n"
    lock.withLock {
      if let handle = try? FileHandle(forWritingTo: logURL) {
        handle.seekToEndOfFile()
        handle.write(Data(line.utf8))
        try? handle.close()
      } else {
        try? Data(line.utf8).write(to: logURL)
      }
    }
  }

  func save(results: [TestResult]) {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    encoder.dateEncodingStrategy = .iso8601
    guard let data = try? encoder.encode(results) else { return }
    lock.withLock { try? data.write(to: resultsURL) }
  }

  func loadResults() -> [TestResult]? {
    guard let data = try? Data(contentsOf: resultsURL) else { return nil }
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    return try? decoder.decode([TestResult].self, from: data)
  }

  /// Empaqueta toda la carpeta de evidencia en un .zip (NSFileCoordinator .forUploading).
  func makeZip() throws -> URL {
    var coordinatorError: NSError?
    var result: URL?
    var copyError: Error?
    NSFileCoordinator().coordinate(readingItemAt: root, options: .forUploading, error: &coordinatorError) { tmp in
      let dest = FileManager.default.temporaryDirectory
        .appendingPathComponent("GlassesLab-evidencia-\(Self.stamp()).zip")
      try? FileManager.default.removeItem(at: dest)
      do {
        try FileManager.default.copyItem(at: tmp, to: dest)
        result = dest
      } catch {
        copyError = error
      }
    }
    if let coordinatorError { throw coordinatorError }
    if let copyError { throw copyError }
    guard let result else { throw CocoaError(.fileWriteUnknown) }
    return result
  }
}
