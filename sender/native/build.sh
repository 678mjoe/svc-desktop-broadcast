#!/bin/sh
set -eu

source_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
dist_dir="$source_dir/../../dist"
case "${1:-}" in
    macos)
        app="$dist_dir/SVC Audio Check.app"
        mkdir -p "$app/Contents/MacOS"
        cp "$source_dir/macos_meter.plist" "$app/Contents/Info.plist"
        xcrun clang++ -std=c++17 -O2 -Wall -Wextra -Werror -fobjc-arc -mmacosx-version-min=14.2 \
            -framework AppKit -framework CoreAudio "$source_dir/macos_meter.mm" \
            -o "$app/Contents/MacOS/svc-audio-check"
        codesign --force --sign - "$app"
        ;;
    windows|windows-package)
        mkdir -p "$dist_dir"
        export ZIG_GLOBAL_CACHE_DIR="$source_dir/../../backups/p4c-native/zig-cache"
        export ZIG_LOCAL_CACHE_DIR="$ZIG_GLOBAL_CACHE_DIR/local"
        zig cc -target x86_64-windows-gnu -std=c11 -municode -O2 -Wall -Wextra -Werror \
            "$source_dir/windows_meter.c" -lmmdevapi -lole32 -luuid -luser32 -lversion -ladvapi32 \
            -o "$dist_dir/svc-wasapi-check.exe"
        if [ "$1" = windows-package ]; then
            python3 "$source_dir/package_windows.py" "$dist_dir"
        fi
        ;;
    *)
        echo "Usage: sh build.sh macos|windows|windows-package" >&2
        exit 2
        ;;
esac
