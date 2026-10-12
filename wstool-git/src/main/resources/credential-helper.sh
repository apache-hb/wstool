!f() { if test "$1" = get; then printf 'username=%s\npassword=%s\n\n' "$WSTOOL_GIT_USERNAME" "$WSTOOL_GIT_PASSWORD"; fi; }; f
