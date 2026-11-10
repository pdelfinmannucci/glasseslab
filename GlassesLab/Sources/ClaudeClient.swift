import Foundation
import Security
import UIKit

/// Cliente HTTP mínimo de la Messages API de Anthropic (no hay SDK oficial para Swift).
///
/// AVISO DE SEGURIDAD: guardar la API key en el iPhone solo es aceptable para esta PoC personal.
/// En producción la app debe llamar a un backend propio que guarde la key y haga la llamada.
enum ClaudeClient {
  enum ClaudeError: LocalizedError {
    case http(Int, String)
    case refusal(String)
    case emptyResponse(String)
    case imageEncoding

    var errorDescription: String? {
      switch self {
      case .http(let code, let body): return "HTTP \(code): \(body.prefix(400))"
      case .refusal(let detail): return "El modelo rechazó la solicitud (refusal): \(detail)"
      case .emptyResponse(let raw): return "Respuesta sin texto: \(raw.prefix(400))"
      case .imageEncoding: return "No se pudo codificar la imagen a JPEG"
      }
    }
  }

  struct Result {
    let text: String
    let model: String
    let stopReason: String
    let inputTokens: Int
    let outputTokens: Int
  }

  static let model = "claude-opus-5-5"

  static let systemPrompt = """
    Eres un asistente técnico para mecánicos de taller automotriz. Recibes imágenes capturadas \
    desde lentes inteligentes del técnico. Describe lo que se ve (componente, estado, posibles \
    fallas visibles, lecturas de instrumentos o etiquetas legibles) y sugiere próximos pasos de \
    diagnóstico seguros. Si la imagen no permite concluir algo, dilo explícitamente. Responde en español.
    """

  static func analyze(image: UIImage, prompt: String, apiKey: String) async throws -> Result {
    guard let jpeg = downscaledJPEG(image, maxEdge: 1568, quality: 0.8) else {
      throw ClaudeError.imageEncoding
    }

    let body: [String: Any] = [
      "model": model,
      "max_tokens": 16000,
      "system": systemPrompt,
      "output_config": ["effort": "medium"],
      // Si los clasificadores de seguridad rechazan, el servidor reintenta con el modelo recomendado.
      "fallbacks": "default",
      "messages": [
        [
          "role": "user",
          "content": [
            [
              "type": "image",
              "source": [
                "type": "base64",
                "media_type": "image/jpeg",
                "data": jpeg.base64EncodedString(),
              ],
            ],
            ["type": "text", "text": prompt],
          ],
        ]
      ],
    ]

    var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
    request.httpMethod = "POST"
    request.timeoutInterval = 180
    request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
    request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
    request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
    request.setValue("application/json", forHTTPHeaderField: "content-type")
    request.httpBody = try JSONSerialization.data(withJSONObject: body)

    let (data, response) = try await URLSession.shared.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? -1
    let raw = String(data: data, encoding: .utf8) ?? ""
    guard status == 200 else { throw ClaudeError.http(status, raw) }

    let json = (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    let stopReason = json["stop_reason"] as? String ?? "?"
    if stopReason == "refusal" {
      throw ClaudeError.refusal("\(json["stop_details"] ?? "sin detalle")")
    }
    let blocks = json["content"] as? [[String: Any]] ?? []
    let text = blocks
      .filter { ($0["type"] as? String) == "text" }
      .compactMap { $0["text"] as? String }
      .joined(separator: "\n")
    guard !text.isEmpty else { throw ClaudeError.emptyResponse(raw) }
    let usage = json["usage"] as? [String: Any] ?? [:]
    return Result(
      text: text,
      model: json["model"] as? String ?? model,
      stopReason: stopReason,
      inputTokens: usage["input_tokens"] as? Int ?? 0,
      outputTokens: usage["output_tokens"] as? Int ?? 0
    )
  }

  static func downscaledJPEG(_ image: UIImage, maxEdge: CGFloat, quality: CGFloat) -> Data? {
    let size = image.size
    let longest = max(size.width, size.height)
    guard longest > maxEdge else { return image.jpegData(compressionQuality: quality) }
    let scale = maxEdge / longest
    let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
      image.draw(in: CGRect(origin: .zero, size: target))
    }
    return resized.jpegData(compressionQuality: quality)
  }
}

/// Keychain mínimo para la API key de la PoC.
enum KeychainStore {
  private static let service = "GlassesLab.anthropic"
  private static let account = "api-key"

  static func save(_ value: String) {
    let base: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
    SecItemDelete(base as CFDictionary)
    var add = base
    add[kSecValueData as String] = Data(value.utf8)
    add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    SecItemAdd(add as CFDictionary, nil)
  }

  static func load() -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
      let data = item as? Data
    else { return nil }
    return String(data: data, encoding: .utf8)
  }
}
