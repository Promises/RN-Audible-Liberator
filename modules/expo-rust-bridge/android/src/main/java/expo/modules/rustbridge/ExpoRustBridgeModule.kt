package expo.modules.rustbridge

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoRustBridgeModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("ExpoRustBridge")

    Function("logFromRust") { message: String ->
      return@Function nativeLogFromRust(message)
    }
  }

  private external fun nativeLogFromRust(message: String): String

  companion object {
    init {
      try {
        System.loadLibrary("rust_core")
      } catch (e: UnsatisfiedLinkError) {
        // Library not found - this is expected in development mode
        // until Rust library is built
        android.util.Log.w("ExpoRustBridge", "Failed to load rust_core library: ${e.message}")
      }
    }
  }
}
