#!/bin/bash
# Script to compile Go stubs for all platforms
# Requires Go compiler to be installed

set -e

STUB_GO="stub.go"
STUBS_DIR="stubs"


PLATFORMS_FILE="platforms.txt"

if [ ! -f "$PLATFORMS_FILE" ]; then
    echo "Error: $PLATFORMS_FILE not found."
    exit 1
fi

# Read platforms from file, skipping empty lines and comments
platforms=()
while IFS= read -r line || [ -n "$line" ]; do
    # Skip empty lines and comments
    line=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    if [ -z "$line" ] || [ "${line#\#}" != "$line" ]; then
        continue
    fi
    platforms+=("$line")
done < "$PLATFORMS_FILE"

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
