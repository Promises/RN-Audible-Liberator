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
RUST_DIR="$PROJECT_ROOT/native/rust-core"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}RN Audible - Rust Desktop Testing${NC}"
echo -e "${BLUE}========================================${NC}\n"

cd "$RUST_DIR"

# Step 1: Run unit tests
echo -e "${YELLOW}Step 1: Running unit tests...${NC}"
if cargo test; then
    echo -e "${GREEN}✓ All unit tests passed${NC}\n"
else
    echo -e "${RED}✗ Unit tests failed${NC}"
    exit 1
fi

# Step 2: Build CLI tool
echo -e "${YELLOW}Step 2: Building CLI tool...${NC}"
if cargo build --features cli --bin rn-audible-cli; then
    echo -e "${GREEN}✓ CLI tool built successfully${NC}\n"
else
    echo -e "${RED}✗ CLI build failed${NC}"
    exit 1
fi

# Step 3: Run CLI test
echo -e "${YELLOW}Step 3: Testing CLI...${NC}"
if cargo run --features cli --bin rn-audible-cli -- test --message "Desktop test"; then
    echo -e "${GREEN}✓ CLI test passed${NC}\n"
else
    echo -e "${RED}✗ CLI test failed${NC}"
    exit 1
fi

# Step 4: Run examples
echo -e "${YELLOW}Step 4: Running examples...${NC}"
if cargo run --example basic_test; then
    echo -e "${GREEN}✓ Example tests passed${NC}\n"
else
    echo -e "${RED}✗ Example tests failed${NC}"
    exit 1
fi

# Step 5: Check formatting
echo -e "${YELLOW}Step 5: Checking code formatting...${NC}"
if cargo fmt -- --check; then
    echo -e "${GREEN}✓ Code formatting is correct${NC}\n"
else
    echo -e "${YELLOW}⚠ Code formatting issues found. Run 'cargo fmt' to fix.${NC}\n"
fi

# Step 6: Run clippy lints
echo -e "${YELLOW}Step 6: Running Clippy lints...${NC}"
if cargo clippy -- -D warnings; then
    echo -e "${GREEN}✓ No Clippy warnings${NC}\n"
else
    echo -e "${YELLOW}⚠ Clippy found some warnings${NC}\n"
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}✓ All desktop tests passed!${NC}"
echo -e "${GREEN}========================================${NC}"

echo -e "\n${BLUE}Available commands:${NC}"
echo -e "  ${YELLOW}cargo run --features cli --bin rn-audible-cli -- --help${NC}"
echo -e "  ${YELLOW}cargo run --example basic_test${NC}"
echo -e "  ${YELLOW}cargo test${NC}"
echo -e "  ${YELLOW}cargo doc --open${NC}"
