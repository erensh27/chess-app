#!/usr/bin/env bash
# Engine Arena: cross-compile every engine in engines/manifest.json for
# arm64-v8a + armeabi-v7a as PIE executables named lib<id>.so into
# DroidFishApp/src/main/jniLibs/<abi>/ (native lib dir = exec permission on
# Android 10+). Continues past per-engine failures and prints a summary.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/engines/src"
OUT_BASE="$ROOT/DroidFishApp/src/main/jniLibs"
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/25.2.9519653}"
API=24
HOST_TAG=linux-x86_64
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"

ABIS="arm64-v8a armeabi-v7a"
declare -A TRIPLE=( [arm64-v8a]=aarch64-linux-android [armeabi-v7a]=armv7a-linux-androideabi )
declare -A ARCHFLAGS=( [arm64-v8a]="-march=armv8.2-a+dotprod" [armeabi-v7a]="-march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb" )
declare -A DEFS=( [arm64-v8a]="-DIS_64BIT -DUSE_POPCNT -DUSE_NEON -DUSE_NEON_DOTPROD" [armeabi-v7a]="-DUSE_NEON" )

# per-engine overrides: id:srcdir:cstd:extra-flags (srcdir relative to engine root, cstd e.g. c++17/c++20/c11)
declare -A CFG=(
  [stockfish]="src:c++17:-DUSE_PTHREADS"
  [shashchess]="src:c++17:"
  [ravager]="src:c11:EXCLUDE:tuner.c"
  [tucano]="src:c11:"
  [ethereal]="src:c11:"
  [demolito]="src:c11:"
  [weiss]="src:c11:"
  [xiphos]="src:c11:"
  [stash]="src:c11:"
  [goob]="src:c11:"
)

ok=(); fail=()
for eng in $(python3 -c "import json;print(' '.join(e['id'] for e in json.load(open('$ROOT/engines/manifest.json'))['engines']))"); do
  ed="$SRC/$eng"
  [ -d "$ed" ] || { echo "SKIP $eng (no source)"; continue; done
  lang=$(python3 -c "import json;m=json.load(open('$ROOT/engines/manifest.json'));print([e['lang'] for e in m['engines'] if e['id']=='$eng'][0])")
  if [ "$lang" = "Rust" ]; then echo "RUST $eng (handled by build_rust.sh)"; continue; fi
  IFS=':' read -r srcdir std extra <<< "${CFG[$eng]:-src:c++17:}"
  [ -d "$ed/$srcdir" ] || srcdir=.
  for abi in $ABIS; do
    cxx="$TOOLCHAIN/${TRIPLE[$abi]}$API-clang++"
    cc="$TOOLCHAIN/${TRIPLE[$abi]}$API-clang"
    mkdir -p "$OUT_BASE/$abi"
    tmp="/tmp/ea-build-$eng-$abi"; rm -rf "$tmp"; mkdir -p "$tmp"
    flags="-O3 -DNDEBUG -fPIE -pie -static-libstdc++ -fexceptions -frtti ${ARCHFLAGS[$abi]} ${DEFS[$abi]} $extra -Wl,-s"
    # gather sources
    (cd "$ed/$srcdir" && find . -name '*.cpp' -o -name '*.cc' -o -name '*.c' | grep -v -i -E 'test|unit|example|/tbprobe$' > "$tmp/files.txt")
    # stockfish-family: fetch default nets if incbin references them and files are missing
    if [ "$eng" = "stockfish" ] && [ ! -f "$ed/src/$(grep -o 'nn-[a-z0-9]*\.nnue' "$ed/src/evaluate.h" 2>/dev/null | head -1)" ]; then
      (cd "$ed/src" && make net >/dev/null 2>&1 || true)
    fi
    nfiles=$(wc -l < "$tmp/files.txt")
    if (cd "$ed/$srcdir" && $cxx $flags -std=$std @<(sed 's|^|'"$ed/$srcdir/"'|' "$tmp/files.txt" | sed "s|^$ed/$srcdir/\./|$ed/$srcdir/|") -o "$tmp/lib$eng.so" ) >"$tmp/log" 2>&1; then
      cp "$tmp/lib$eng.so" "$OUT_BASE/$abi/lib$eng.so"
      echo "OK   $eng $abi ($nfiles files)"
    else
      echo "FAIL $eng $abi - see /tmp/ea-build-$eng-$abi/log"
    fi
  done
done
echo; echo "Built binaries:"; find "$OUT_BASE" -name 'lib*.so' | sort
