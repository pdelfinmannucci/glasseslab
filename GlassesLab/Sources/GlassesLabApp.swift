import MWDATCore
import SwiftUI

@main
struct GlassesLabApp: App {
  @State private var controller: GlassesController

  init() {
    var configureError: String?
    do {
      // Debe llamarse una sola vez al iniciar la app.
      try Wearables.configure()
    } catch {
      configureError = "Wearables.configure() falló: \(error)"
    }
    _controller = State(wrappedValue: GlassesController(configureError: configureError))
  }

  var body: some Scene {
    WindowGroup {
      ContentView(controller: controller)
        .onOpenURL { url in
          // Solo reenviar al SDK los callbacks de Meta AI (registro / permisos).
          guard
            let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            components.queryItems?.contains(where: { $0.name == "metaWearablesAction" }) == true
          else { return }
          Task { await controller.handleMetaAICallback(url) }
        }
    }
  }
}
