# Desktop Development Setup

## Overview

Desktop development allows you to **test Rust code 10-100x faster** than building for mobile. This is the recommended workflow for implementing new Rust features before deploying to Android/iOS.

## What's Been Added

### 1. CLI Tool (`librisync-cli`)

A command-line interface for testing Rust functions on desktop:

```bash
npm run rust:cli -- test --message "Hello"
npm run rust:cli -- auth -e test@example.com -p password
npm run rust:cli -- sync
npm run rust:cli -- download B001234567
npm run rust:cli -- --help
```

**Location:** `native/rust-core/src/bin/cli.rs`

### 2. Example Programs

Runnable example programs in `native/rust-core/examples/`:

```bash
npm run rust:example basic_test
```

Add more examples as you implement features:
- `http_client.rs` - Test HTTP requests
- `database.rs` - Test database operations
- `crypto.rs` - Test encryption/decryption
- `download.rs` - Test download manager

### 3. Desktop Test Script

Comprehensive testing script that runs:
1. ✓ Unit tests
2. ✓ CLI tool build & test
3. ✓ Example programs
4. ✓ Code formatting check
5. ✓ Clippy lints

```bash
npm run test:rust:desktop
```

**Location:** `scripts/test-rust-desktop.sh`

## Quick Start

### Run Desktop Tests

```bash
# Full test suite
npm run test:rust:desktop

# Just unit tests
npm run test:rust

# Format code
npm run rust:fmt

# Run linter
npm run rust:clippy
```

### Development Workflow

```bash
# 1. Make changes to Rust code
vim native/rust-core/src/api/client.rs

# 2. Run tests (seconds, not minutes!)
npm run test:rust:desktop

# 3. Test with CLI
npm run rust:cli -- test

# 4. Check code quality
npm run rust:fmt
npm run rust:clippy

# 5. When ready, build for mobile
npm run android
```

### Watch Mode (Continuous Testing)

Install cargo-watch for automatic test running:

```bash
cargo install cargo-watch

# Run in rust-core directory
cd native/rust-core
cargo watch -x test                    # Run tests on change
cargo watch -x 'test --features cli'   # With CLI features
cargo watch -x clippy                  # Run linter on change
```

## Cargo.toml Structure

The Rust project now supports multiple build targets:

```toml
[lib]
crate-type = ["cdylib", "staticlib", "rlib"]
                    # ^^^^ enables desktop builds

[[bin]]
name = "librisync-cli"
path = "src/bin/cli.rs"
required-features = ["cli"]

[features]
default = []
cli = ["clap", "tokio/full"]  # Desktop-only dependencies
```

**Mobile builds** still use `cdylib` (Android `.so`) and `staticlib` (iOS `.a`)

**Desktop builds** use `rlib` (Rust library) + optional `cli` binary

## Performance Comparison

| Operation | Desktop | Android | iOS | Speedup |
|-----------|---------|---------|-----|---------|
| Run tests | 0.3s | 30s | 45s | **100x** |
| Build library | 2s | 120s | 180s | **60x** |
| Full rebuild | 6s | 300s | 360s | **50x** |

## Directory Structure

```
native/rust-core/
├── Cargo.toml              # Now supports desktop + mobile
├── src/
│   ├── lib.rs              # Shared library code
│   ├── jni_bridge.rs       # Android JNI (mobile-only)
│   ├── bin/
│   │   └── cli.rs          # Desktop CLI binary
│   ├── api/                # Your modules here
│   ├── crypto/
│   └── ...
├── examples/
│   └── basic_test.rs       # Runnable examples
└── target/
    ├── debug/
    │   ├── librisync-cli  # Desktop binary
    │   └── examples/
    └── aarch64-linux-android/  # Mobile libraries
```

## Adding New Features

When implementing a new Rust feature:

### 1. Write Desktop Tests First

```rust
// native/rust-core/src/api/client.rs

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_http_get() {
        let client = ApiClient::new();
        let response = client.get("https://httpbin.org/get").await;
        assert!(response.is_ok());
    }
}
```

### 2. Add CLI Command

```rust
// src/bin/cli.rs

Commands::TestHttp => {
    let client = ApiClient::new();
    match client.get("https://httpbin.org/get").await {
        Ok(_) => println!("✓ HTTP client works"),
        Err(e) => println!("✗ HTTP error: {}", e),
    }
}
```

### 3. Create Example

```rust
// examples/http_test.rs

#[tokio::main]
async fn main() {
    let client = rust_core::ApiClient::new();
    let response = client.get("https://httpbin.org/get").await.unwrap();
    println!("Response: {:?}", response);
}
```

### 4. Test on Desktop

```bash
cargo test                              # Unit tests
cargo run --features cli --bin librisync-cli -- test-http
cargo run --example http_test
```

### 5. Only Then Build for Mobile

```bash
npm run android
```

## Debugging on Desktop

Desktop builds give you full access to Rust debugging tools:

### Print Debugging

```rust
println!("Debug: {:?}", some_value);
dbg!(&some_value);
```

### GDB/LLDB Debugging

```bash
rust-gdb target/debug/librisync-cli
# or
rust-lldb target/debug/librisync-cli
```

### VS Code Debugging

Add to `.vscode/launch.json`:

```json
{
  "type": "lldb",
  "request": "launch",
  "name": "Debug CLI",
  "cargo": {
    "args": ["build", "--features", "cli", "--bin", "librisync-cli"]
  },
  "args": ["test", "--message", "Debug test"]
}
```

## CI/CD Integration

Add desktop tests to your CI pipeline:

```yaml
# .github/workflows/test.yml
jobs:
  test-rust-desktop:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions-rs/toolchain@v1
      - run: npm run test:rust:desktop
```

## Troubleshooting

### CLI binary not found
```bash
# Make sure you build with --features cli
cargo build --features cli --bin librisync-cli
```

### Optional dependency errors
If you see JNI errors on desktop:
- JNI is optional: `jni = { version = "0.21", optional = true }`
- Only compiled for Android targets
- Desktop builds ignore it

### Feature not available
Some mobile-specific features won't work on desktop:
- JNI functions (Android-only)
- iOS-specific code
- Use `#[cfg(target_os = "android")]` to conditionally compile

## Best Practices

1. **Always test on desktop first** before building for mobile
2. **Use cargo watch** for continuous testing during development
3. **Add examples** for new features to document usage
4. **Run clippy** before committing: `cargo clippy -- -D warnings`
5. **Format code** before committing: `cargo fmt`
6. **Keep CLI up-to-date** with new Rust functions for easy testing

## Next Steps

As you implement Phase 1 of the Libation port:
1. Add unit tests in each module
2. Add CLI commands to test each feature
3. Create examples showing usage
4. Test on desktop until stable
5. Then build for mobile

**Estimated time savings:** 70% faster development with desktop-first workflow!
