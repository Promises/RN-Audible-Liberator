#!/bin/bash

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Parse arguments
PLATFORM="all"
if [ "$1" = "android" ] || [ "$1" = "ios" ]; then
    PLATFORM="$1"
fi

echo -e "${GREEN}Building Rust native libraries for: $PLATFORM${NC}"

if [ "$PLATFORM" = "all" ] || [ "$PLATFORM" = "android" ]; then
    echo -e "${YELLOW}Building for Android...${NC}"
    "$SCRIPT_DIR/build-rust-android.sh"
fi

if [ "$PLATFORM" = "all" ] || [ "$PLATFORM" = "ios" ]; then
    echo -e "${YELLOW}Building for iOS...${NC}"
    "$SCRIPT_DIR/build-rust-ios.sh"
fi

echo -e "${GREEN}✓ All builds complete!${NC}"
