#!/bin/sh

set -eu
if busybox wget -T 3 -q -O "$WST_PREFIX/network.txt" "$WSTOOL_TEST_NETWORK_URL"; then
  exit 31
fi
if touch "$WST_SRCDIR/should-not-write.txt"; then
  exit 32
fi
printf 'network-isolated\n' > "$WST_PREFIX/network.txt"
