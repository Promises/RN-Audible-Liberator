# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RN Audible is a React Native port of Libation (an Audible client and DRM remover) for iOS and Android. The architecture uses React Native + Expo for the UI layer, with shared native code written in Rust for performance-critical operations like DRM removal and audio processing.

## Key Architecture Patterns

### Three-Layer Architecture

1. **UI Layer (React Native/TypeScript)**: `App.tsx` and future UI components
2. **Bridge Layer (TypeScript)**: `modules/expo-rust-bridge/` - Currently a placeholder that will be replaced with UniFFI-generated bindings
3. **Native Core (Rust)**: `native/rust-core/` - Shared business logic compiled for both iOS and Android

### Rust-to-JavaScript Bridge

The project uses platform-specific native bindings:

**Android (JNI)**:
- **`native/rust-core/src/jni_bridge.rs`**: JNI wrapper functions that expose Rust to Kotlin
- **`modules/expo-rust-bridge/android/.../ExpoRustBridgeModule.kt`**: Kotlin Expo module that loads the native library
- Compiled Rust libraries are placed in `android/app/src/main/jniLibs/{architecture}/librust_core.so`

**iOS (C FFI)**:
- **UniFFI scaffolding**: For future iOS implementation
- Compiled libraries will be packaged as XCFramework in `ios/Frameworks/`

**Bridge Layer**:
- **`modules/expo-rust-bridge/index.ts`**: TypeScript interface with fallback for development mode
- **`modules/expo-rust-bridge/expo-module.config.json`**: Expo autolinking configuration

When adding new Rust functions:
1. Add the function to `src/lib.rs` as `pub fn`
2. Create JNI wrapper in `src/jni_bridge.rs` (Android)
3. Export through Kotlin module in `ExpoRustBridgeModule.kt`
4. Update TypeScript interface in `index.ts`

## Development Commands

### Quick Development (No Native Rebuild)

```bash
npm start              # Start Expo dev server (interactive menu)
npm run android:dev    # Start with Android (Expo Go mode)
npm run ios:dev        # Start with iOS (Expo Go mode)
```

### Full Build with Native Code

```bash
npm run android        # Build Rust + Android native + Run on emulator/device
npm run ios            # Build Rust + iOS native + Run on simulator/device
```

### Build Scripts

```bash
# Build Rust for specific platforms
npm run build:rust              # Build for both Android and iOS
npm run build:rust:android      # Build for Android only (all architectures)
npm run build:rust:ios          # Build for iOS only (device + simulator)

# Direct script invocation
./scripts/build-rust-android.sh # Compile to arm64-v8a, armeabi-v7a, x86, x86_64
./scripts/build-rust-ios.sh     # Create XCFramework for iOS
./scripts/build-rust.sh android # Build for specific platform
```

### Testing

```bash
npm run test:rust      # Run Rust unit tests
npm run test:android   # Full pipeline: Rust tests + build + run on Android
npm run test:ios       # Full pipeline: Rust tests + build + run on iOS
npx tsc --noEmit       # TypeScript type checking
```

### Native Directory Management

```bash
npm run prebuild:android  # Regenerate android/ folder from scratch
npm run prebuild:ios      # Regenerate ios/ folder from scratch
```

**Build Requirements**:
- Android: `ANDROID_NDK_HOME` must be set to NDK path
- iOS: Xcode command line tools must be installed
- See `scripts/README.md` for detailed setup instructions

## Code Organization

### UI Structure

```
src/
├── navigation/
│   └── AppNavigator.tsx        # Bottom tab navigation
├── screens/
│   ├── LibraryScreen.tsx       # Audiobook list with status
│   ├── AccountScreen.tsx       # Login/account management
│   └── SettingsScreen.tsx      # App settings & configuration
├── components/                  # Shared UI components (future)
└── styles/
    └── theme.ts                # Color palette, typography, spacing
```

### Styling Approach

The project uses StyleSheet API for styling. Consistent dark theme is defined in `src/styles/theme.ts`:
- Background: `#1a1a1a`
- Secondary background: `#2a2a2a`
- Primary text: `#ffffff`
- Secondary text: `#888888`
- Accent (monospace text): `#00ff00`

Prefer StyleSheet over inline styles for maintainability and performance.

### Reference Implementation

The `references/Libation/` directory contains the original C# Libation source code. When implementing new features (Audible API, DRM removal, library management), consult the C# implementation in `references/Libation/` to understand the logic, then port it to Rust in `native/rust-core/`.

**Note**: The `references/` directory is git-ignored and should remain local only.

### Implementation Planning Documents

- **`LIBATION_PORT_PLAN.md`**: Comprehensive 15-week plan for porting Libation to Rust
  - Phase-by-phase breakdown
  - Dependency list
  - Testing strategy (unit, integration, E2E)
  - Critical challenges and solutions

- **`AGENT_IMPLEMENTATION_PLAN.md`**: Agent-assisted implementation guide for Phase 1
  - Task breakdown for general-purpose agent
  - Week-by-week execution plan
  - Success criteria and validation steps

- **`DESKTOP_DEVELOPMENT.md`**: Desktop-first development workflow
  - CLI tool for testing Rust without mobile builds
  - 10-100x faster iteration cycle
  - Watch mode, debugging, CI/CD integration

## Current Implementation Status

### ✅ Completed
- Expo project structure with native directories
- Rust core module with JNI bindings (Android) - **TESTED AND WORKING**
- Expo native module (`ExpoRustBridgeModule.kt`)
- Cross-compilation build scripts for Android (all architectures)
- Cross-compilation build scripts for iOS (device + simulator)
- Automated test and build pipeline
- TypeScript bridge with fallback for development
- Three-screen app skeleton:
  - **Library Screen**: Audiobook list with status indicators
  - **Account Screen**: Login/logout with Audible credentials
  - **Settings Screen**: Download directory, DRM options, app settings
- Bottom tab navigation with React Navigation

### 🚧 In Progress / Needs Testing
- **iOS Native Bridge**: Build script ready, needs iOS-specific Expo module implementation

### 📋 Next Implementation Priorities

See `LIBATION_PORT_PLAN.md` for comprehensive porting plan.

**Immediate priorities:**
1. Implement Rust error handling types
2. HTTP client for Audible API
3. SQLite database layer (port from Libation schema)
4. OAuth authentication flow
5. Library sync from Audible
6. DRM removal (AAX → M4B conversion)

## Build Architecture

### Android Build Flow
1. `build-rust-android.sh` compiles Rust to `.so` for all Android architectures
2. Libraries copied to `android/app/src/main/jniLibs/{architecture}/`
3. Gradle builds APK with embedded native libraries
4. Expo module loads library via `System.loadLibrary("rust_core")`

### iOS Build Flow (Planned)
1. `build-rust-ios.sh` compiles Rust to `.a` for iOS targets
2. Creates XCFramework combining device + simulator libraries
3. XCFramework linked in Xcode project
4. Expo module calls C FFI functions

## Important Notes

- Node.js version warnings can be ignored (works with 20.12.2+)
- The Rust module compiles to both `cdylib` and `staticlib` as configured in `Cargo.toml`
- UniFFI is kept for future cross-platform binding generation, but currently using platform-specific bindings
- Android NDK must be properly configured with `ANDROID_NDK_HOME` environment variable
- All build scripts are in `scripts/` directory - see `scripts/README.md` for details
