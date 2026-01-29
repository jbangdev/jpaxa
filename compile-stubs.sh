#!/bin/bash
# Script to compile Go stubs for all platforms
# Requires Go compiler to be installed

set -e

STUB_GO="stub.go"
STUBS_DIR="stubs"

if [ ! -f "$STUB_GO" ]; then
    echo "Error: stub.go not found."
    exit 1
fi

mkdir -p "$STUBS_DIR"

echo "Compiling stubs for all platforms..."

# Windows x64
echo "  Windows x64..."
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--win32--x64" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--win32--x64"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--win32--x64"

# macOS x64
echo "  macOS x64..."
GOOS=darwin GOARCH=amd64 CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--darwin--x64" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--darwin--x64"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--darwin--x64"

# macOS ARM64
echo "  macOS ARM64..."
GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--darwin--arm64" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--darwin--arm64"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--darwin--arm64"

# Linux x64
echo "  Linux x64..."
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--linux--x64" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--linux--x64"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--linux--x64"

# Linux ARM64
echo "  Linux ARM64..."
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--linux--arm64" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--linux--arm64"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--linux--arm64"

# Linux ARM
echo "  Linux ARM..."
GOOS=linux GOARCH=arm CGO_ENABLED=0 go build -o "$STUBS_DIR/stub--linux--arm" "$STUB_GO"
echo "" >> "$STUBS_DIR/stub--linux--arm"
echo "CAXACAXACAXA" >> "$STUBS_DIR/stub--linux--arm"

echo ""
echo "Done! Stubs compiled to $STUBS_DIR/"
echo ""
echo "You can now use jpackxa. The stubs will be automatically found in the $STUBS_DIR/ directory."
