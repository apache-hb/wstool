#!/bin/sh

set -eu
printf 'write-enabled\n' >> "$WST_SRCDIR/source-write.txt"
busybox wget -q -O "$WST_PREFIX/network.txt" "$WSTOOL_TEST_NETWORK_URL"
