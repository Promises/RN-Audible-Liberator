# Build Scripts

This directory contains build scripts for compiling Rust native code and running the app on different platforms.

## Prerequisites

### For All Platforms
- Rust and Cargo installed (`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`)
- Node.js >= 20.16.0

### For Android
- Android SDK with NDK installed
- Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` environment variable
  ```bash
  export ANDROID_NDK_HOME=$HOME/Library/Android/sdk/ndk/26.1.10909125
  ```
- Java Development Kit (JDK) 17 or higher

### For iOS (macOS only)
- Xcode with Command Line Tools
- CocoaPods (`sudo gem install cocoapods`)

## Scripts

### Desktop Development Scripts

#### `test-rust-desktop.sh`
🆕 Comprehensive desktop testing script for rapid Rust development without mobile builds.

```bash
./scripts/test-rust-desktop.sh
```

**What it does:**
1. Runs all Rust unit tests
2. Builds the CLI tool
3. Tests CLI functionality
4. Runs example programs
5. Checks code formatting
6. Runs Clippy lints

**Why use this:** Much faster iteration than rebuilding for mobile. Perfect for TDD workflow.

### Mobile Build Scripts

#### `build-rust-android.sh`
Builds the Rust native library for all Android architectures (arm64-v8a, armeabi-v7a, x86, x86_64).

```bash
./scripts/build-rust-android.sh
```

**Output**: Compiled `.so` libraries in `android/app/src/main/jniLibs/`

#### `build-rust-ios.sh`
Builds the Rust native library for iOS (device and simulator) and creates an XCFramework.

```bash
./scripts/build-rust-ios.sh
```

**Output**: XCFramework in `ios/Frameworks/RustCore.xcframework`

#### `build-rust.sh`
Master build script that builds Rust for both platforms or a specific one.

```bash
./scripts/build-rust.sh           # Build for both Android and iOS
./scripts/build-rust.sh android   # Build for Android only
./scripts/build-rust.sh ios       # Build for iOS only
```

### `test-and-run.sh`
Comprehensive script that:
1. Runs Rust tests
2. Builds Rust native library
3. Runs TypeScript type checking
4. Builds and runs the app on device/simulator

```bash
# Android emulator
./scripts/test-and-run.sh android

# Android physical device
./scripts/test-and-run.sh android device

# iOS simulator
./scripts/test-and-run.sh ios

# iOS physical device
./scripts/test-and-run.sh ios device
```

## NPM Scripts

The following npm scripts are available in `package.json`:

```bash
# Development (without rebuilding Rust)
npm run android:dev          # Start Expo with Android
npm run ios:dev              # Start Expo with iOS

# Full build and run (with Rust rebuild)
npm run android              # Build Rust + run on Android
npm run ios                  # Build Rust + run on iOS

# Build Rust only
npm run build:rust           # Build for all platforms
npm run build:rust:android   # Build for Android only
npm run build:rust:ios       # Build for iOS only

# Testing
npm run test:rust            # Run Rust unit tests
npm run test:rust:desktop    # 🆕 Desktop testing (fast!)
npm run test:android         # Full pipeline: test + build + run
npm run test:ios             # Full pipeline: test + build + run

# Desktop Development (NEW!)
npm run rust:cli -- test               # Run CLI with args
npm run rust:cli -- auth -e test@example.com -p pass
npm run rust:example basic_test        # Run example
npm run rust:doc                       # Build and open docs
npm run rust:fmt                       # Format code
npm run rust:clippy                    # Run linter

# Prebuild (regenerate native directories)
npm run prebuild:android     # Regenerate android/ folder
npm run prebuild:ios         # Regenerate ios/ folder
```

## Troubleshooting

### Android NDK not found
Make sure `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT` is set:
```bash
echo $ANDROID_NDK_HOME
# Should output something like: /Users/username/Library/Android/sdk/ndk/26.1.10909125
```

### Rust target not installed
Install required targets:
```bash
# For Android
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android

# For iOS
rustup target add aarch64-apple-ios x86_64-apple-ios aarch64-apple-ios-sim
```

### Library not loading at runtime
1. Make sure Rust library was built successfully
2. Check that `.so`/`.a` files exist in the correct directories
3. Clean and rebuild: `npm run prebuild:android && npm run android`

### TypeScript errors
Run type checking separately:
```bash
npx tsc --noEmit
```

## Development Workflow

### 🆕 Desktop-First Development (Recommended)

**Fast iteration cycle for Rust development:**

```bash
# 1. Make changes to Rust code
# 2. Run desktop tests (seconds, not minutes!)
npm run test:rust:desktop

# 3. Test specific features with CLI
npm run rust:cli -- test --message "Testing new feature"

# 4. Run examples
npm run rust:example basic_test

# 5. Check code quality
npm run rust:fmt
npm run rust:clippy

# 6. Only when ready, build for mobile
npm run android    # or npm run ios
```

**Why desktop-first?**
- ⚡ **10-100x faster** than mobile builds
- 🔍 Better debugging (use `dbg!()`, print statements)
- 📝 Faster test-driven development
- 🛠️ Use native Rust tooling (cargo watch, rust-analyzer)

**Watch mode for continuous testing:**
```bash
# Install cargo-watch
cargo install cargo-watch

# Auto-run tests on file changes
cd native/rust-core
cargo watch -x test
```

### Quick UI development (no native changes)
```bash
npm start
# Then press 'a' for Android or 'i' for iOS
```

### After modifying Rust code
```bash
# Option 1: Test on desktop first (fast)
npm run test:rust:desktop

# Option 2: Test directly on mobile
npm run test:android    # or npm run test:ios
```

### Complete rebuild from scratch
```bash
# Clean everything
rm -rf node_modules android ios native/rust-core/target
npm install

# Rebuild
npm run prebuild:android
npm run android
```
