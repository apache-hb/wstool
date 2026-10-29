#!/bin/sh
set -eu
mkdir -p "$WST_PREFIX"
cat src/message.txt src/package-overlay.txt data/version.txt > "$WST_PREFIX/package-output.txt"
printf '%s\n' "$PWD" > "$WST_PREFIX/build-directory.txt"
