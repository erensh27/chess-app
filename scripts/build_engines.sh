#!/usr/bin/env bash
# Engine Arena build_engines.sh v2
# Builds the LIVE engine set (stockfish, ravager, patricia) for Android as
# PIE executables named lib<id>.so into engines/bin/<abi>/.
# Recipes use each engine's own build files/flags (learned from the v1
# generic-recipe failure map). Patches live in scripts/patches/ and are
# applied idempotently. armeabi-v7a is best-effort; arm64-v8a is the gate.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/engines/src"
OUT="$ROOT/engines/bin"
NDK="${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-/opt}/ndk/"* 2>/dev/null | sort -V | tail -1)}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24
STRIP="$TC/llvm-strip"
ABIS="${ABIS:-arm64-v8a}"
ok=(); fail=()

note() { echo "== $*"; }
done_ok() { ok+=("$1"); echo "OK   $1"; }
done_fail() { fail+=("$1"); echo "FAIL $1 (see $2)"; }

apply_patch() { # apply_patch <engine> <patchfile>
  (cd "$SRC/$1" && git apply --check "$ROOT/scripts/patches/$2" 2>/dev/null && git apply "$ROOT/scripts/patches/$2" && echo "patch $2 applied") || true
}

build_stockfish() { # $1=abi
  local abi=$1 triple arch
  case $abi in
    arm64-v8a) triple=aarch64-linux-android; arch=armv8-dotprod ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; arch=armv7-neon ;;
  esac
  local cxx="$TC/${triple}${API}-clang++"
  ( cd "$SRC/stockfish/src"
    grep -o 'nn-[a-z0-9]*\.nnue' evaluate.h | sort -u | while read -r n; do [ -f "$n" ] || make net >/dev/null 2>&1; done
    make -j"$(nproc)" build ARCH="$arch" COMP=clang CXX="$cxx" LDFLAGS=-latomic ) > /tmp/ea-sf-$abi.log 2>&1 || { done_fail "stockfish $abi" /tmp/ea-sf-$abi.log; return; }
  mkdir -p "$OUT/$abi"
  "$STRIP" "$SRC/stockfish/src/stockfish" -o "$OUT/$abi/libstockfish.so"
  done_ok "stockfish $abi"
}

build_ravager() { # $1=abi
  local abi=$1 triple flags
  case $abi in
    arm64-v8a) triple=aarch64-linux-android; flags="-O3 -DNDEBUG -fPIE -pie -march=armv8.2-a+dotprod -DUSE_NEON -DUSE_NEON_DOTPROD" ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; flags="-O3 -DNDEBUG -fPIE -pie -march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb -DUSE_NEON" ;;
  esac
  local cc="$TC/${triple}${API}-clang"
  local net; net=$(grep -m1 '^EVALFILE ?=' "$SRC/ravager/Makefile" | awk '{print $3}')
  local srcs="src/bitboard.c src/board.c src/movegen.c src/see.c src/evaluate.c src/params.c src/tt.c src/search.c src/nnue.c src/tb/tbprobe.c src/tb_syzygy.c src/uci.c"
  ( cd "$SRC/ravager" && $cc $flags -std=c11 -DEVALFILE=\"$net\" $srcs -o /tmp/libravager-$abi.so ) > /tmp/ea-ravager-$abi.log 2>&1 || { done_fail "ravager $abi" /tmp/ea-ravager-$abi.log; return; }
  mkdir -p "$OUT/$abi"
  "$STRIP" /tmp/libravager-$abi.so -o "$OUT/$abi/libravager.so"
  done_ok "ravager $abi"
}

build_patricia() { # $1=abi
  local abi=$1 triple flags
  case $abi in
    arm64-v8a) triple=aarch64-linux-android; flags="-march=armv8.2-a+dotprod" ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; flags="-march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb" ;;
  esac
  local cxx="$TC/${triple}${API}-clang++"
  apply_patch patricia patricia-assume-aligned.patch
  ( cd "$SRC/patricia/engine" && $cxx -O3 -std=c++20 -ffast-math $flags -pthread -static-libstdc++ src/patricia.cpp src/fathom/src/tbprobe.c -o /tmp/libpatricia-$abi.so ) > /tmp/ea-patricia-$abi.log 2>&1 || { done_fail "patricia $abi" /tmp/ea-patricia-$abi.log; return; }
  mkdir -p "$OUT/$abi"
  "$STRIP" /tmp/libpatricia-$abi.so -o "$OUT/$abi/libpatricia.so"
  done_ok "patricia $abi"
}

note "NDK: $NDK"
for abi in $ABIS; do
  build_stockfish "$abi"
  build_ravager "$abi"
  build_patricia "$abi"
done
echo
echo "== SUMMARY =="
printf 'OK:   %s\n' "${ok[@]:-none}"
printf 'FAIL: %s\n' "${fail[@]:-none}"
ls -la "$OUT"/*/ 2>/dev/null
