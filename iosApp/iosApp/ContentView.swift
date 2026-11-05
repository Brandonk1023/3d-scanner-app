import SwiftUI
import Shared

/// Phase 0 shell: proves Swift → shared (Kotlin/Native) → C++ core.
/// The real iOS UI (SwiftUI or Compose Multiplatform) is decided before Phase 9.
struct ContentView: View {
    private let wiring = IosEntryKt.wiringCheck()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Facet")
                .font(.largeTitle.bold())
            Text("Wiring check")
                .font(.headline)
            Text(wiring)
                .font(.system(.footnote, design: .monospaced))
                .accessibilityIdentifier("wiringCheck")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

#Preview {
    ContentView()
}
