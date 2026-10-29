#!/bin/sh

set -eu
printf '%s\n' leaf >> "$WST_BUILD_ORDER"
test "${WST_FAIL_PACKAGE:-}" != leaf
