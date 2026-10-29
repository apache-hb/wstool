#!/bin/sh

set -eu
printf '%s\n' root >> "$WST_BUILD_ORDER"
test "${WST_FAIL_PACKAGE:-}" != root
