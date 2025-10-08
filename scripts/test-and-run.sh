#!/bin/bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Parse arguments
PLATFORM="${1:-android}"
DEVICE_TYPE="${2:-}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}LibriSync - Test and Run${NC}"
echo -e "${BLUE}========================================${NC}"

# Step 1: Run Rust tests
echo -e "\n${YELLOW}Step 1: Running Rust tests...${NC}"
cd "$PROJECT_ROOT/native/rust-core"
if cargo test; then
    echo -e "${GREEN}✓ Rust tests passed${NC}"
else
    echo -e "${RED}✗ Rust tests failed${NC}"
    exit 1
fi

# Step 2: Build Rust for target platform
echo -e "\n${YELLOW}Step 2: Building Rust native library for $PLATFORM...${NC}"
cd "$PROJECT_ROOT"
if [ "$PLATFORM" = "android" ]; then
    ./scripts/build-rust-android.sh
elif [ "$PLATFORM" = "ios" ]; then
    ./scripts/build-rust-ios.sh
else
    echo -e "${RED}Unknown platform: $PLATFORM${NC}"
    echo "Usage: $0 [android|ios] [device-type]"
    exit 1
fi

# Step 3: Run TypeScript type checking
echo -e "\n${YELLOW}Step 3: Running TypeScript type checking...${NC}"
if npx tsc --noEmit; then
    echo -e "${GREEN}✓ TypeScript types OK${NC}"
else
    echo -e "${RED}✗ TypeScript type errors found${NC}"
    exit 1
fi

# Step 4: Build and run on device/simulator
echo -e "\n${YELLOW}Step 4: Building and running app on $PLATFORM...${NC}"

if [ "$PLATFORM" = "android" ]; then
    if [ -n "$DEVICE_TYPE" ]; then
        if [ "$DEVICE_TYPE" = "device" ]; then
            echo -e "${BLUE}Running on Android physical device...${NC}"
            npx expo run:android --device
        else
            echo -e "${BLUE}Running on Android emulator...${NC}"
            npx expo run:android
        fi
    else
        echo -e "${BLUE}Running on Android (auto-detect)...${NC}"
        npx expo run:android
    fi
elif [ "$PLATFORM" = "ios" ]; then
    if [ -n "$DEVICE_TYPE" ]; then
        if [ "$DEVICE_TYPE" = "device" ]; then
            echo -e "${BLUE}Running on iOS physical device...${NC}"
            npx expo run:ios --device
        else
            echo -e "${BLUE}Running on iOS simulator...${NC}"
            npx expo run:ios --simulator
        fi
    else
        echo -e "${BLUE}Running on iOS simulator...${NC}"
        npx expo run:ios
    fi
fi

echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}✓ All tests passed and app is running!${NC}"
echo -e "${GREEN}========================================${NC}"
