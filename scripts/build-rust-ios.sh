#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}Building Rust native library for iOS...${NC}"

# Get project root directory
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_DIR="$PROJECT_ROOT/native/rust-core"
IOS_LIBS_DIR="$PROJECT_ROOT/ios/Frameworks"

# iOS architectures (parallel arrays for bash 3.2 compatibility)
RUST_TARGETS=("aarch64-apple-ios" "x86_64-apple-ios" "aarch64-apple-ios-sim")
IOS_ARCHS=("ios-arm64" "ios-x86_64-simulator" "ios-arm64-simulator")

# Install Rust targets if not already installed
echo -e "${YELLOW}Checking Rust targets...${NC}"
for target in "${RUST_TARGETS[@]}"; do
    if ! rustup target list | grep -q "$target (installed)"; then
        echo -e "${YELLOW}Installing target: $target${NC}"
        rustup target add "$target"
    fi
done

# Build for each target
cd "$RUST_DIR"

for i in "${!RUST_TARGETS[@]}"; do
    target="${RUST_TARGETS[$i]}"
    arch="${IOS_ARCHS[$i]}"

    echo -e "${GREEN}Building for $target ($arch)...${NC}"

    cargo build --release --target "$target"

    echo -e "${GREEN}✓ Built library for $arch${NC}"
done

# Create universal binary for device
echo -e "${GREEN}Creating universal binary for iOS device...${NC}"
mkdir -p "$IOS_LIBS_DIR"

lipo -create \
    "target/aarch64-apple-ios/release/librust_core.a" \
    -output "$IOS_LIBS_DIR/librust_core-device.a"

# Create universal binary for simulator
echo -e "${GREEN}Creating universal binary for iOS simulator...${NC}"
lipo -create \
    "target/x86_64-apple-ios/release/librust_core.a" \
    "target/aarch64-apple-ios-sim/release/librust_core.a" \
    -output "$IOS_LIBS_DIR/librust_core-simulator.a"

# Create XCFramework
echo -e "${GREEN}Creating XCFramework...${NC}"
xcodebuild -create-xcframework \
    -library "$IOS_LIBS_DIR/librust_core-device.a" \
    -library "$IOS_LIBS_DIR/librust_core-simulator.a" \
    -output "$IOS_LIBS_DIR/RustCore.xcframework"

echo -e "${GREEN}All iOS architectures built successfully!${NC}"
echo -e "${YELLOW}XCFramework created at: $IOS_LIBS_DIR/RustCore.xcframework${NC}"
