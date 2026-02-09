#!/bin/bash
# Script to compile Go stubs for all platforms
# Requires Go compiler to be installed

set -e

STUB_GO="stub.go"
STUBS_DIR="stubs"


## Needs to match platform in jpaxa.java --variants option
platforms=( 
  "windows amd64 windows-x86_64"
  "darwin  amd64 osx-x86_64"
  "darwin  arm64 osx-aarch64"
  "linux   amd64 linux-x86_64"
  "linux   arm64 linux-aarch64"
  "linux   arm   linux-arm"
)

if [ ! -f "$STUB_GO" ]; then
    echo "Error: stub.go not found."
    exit 1
fi

mkdir -p "$STUBS_DIR"

echo "Compiling stubs for each platforms..."

for entry in "${platforms[@]}"; do
  read -r goos goarch suffix <<<"$entry"

  echo "  ${suffix} -> $STUBS_DIR/stub-${suffix}" 
  GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 go build -o "$STUBS_DIR/stub-${suffix}" "$STUB_GO"
  {
    echo ""
    echo "CAXACAXACAXA"
  } >> "$STUBS_DIR/stub-${suffix}"
done

echo ""
echo "Done! Stubs compiled to $STUBS_DIR/"
echo ""
echo "You can now use jpaxa. The stubs will be automatically found in the $STUBS_DIR/ directory."
