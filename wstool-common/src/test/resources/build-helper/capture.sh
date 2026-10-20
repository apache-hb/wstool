#!/bin/sh
printf '%s\n' "$PWD" "$WST_SRCDIR" "$WST_PRIVATE" "$WST_PREFIX" "$@" > invocation.txt
