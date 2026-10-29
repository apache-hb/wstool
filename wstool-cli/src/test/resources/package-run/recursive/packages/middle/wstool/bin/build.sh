#!/bin/sh

set -eu
printf '%s\n' middle >> "$WST_BUILD_ORDER"
test "${WST_FAIL_PACKAGE:-}" != middle
