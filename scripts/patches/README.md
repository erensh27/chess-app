Per-engine build patches applied by scripts/build_engines.sh before compiling.

Known so far:
- ravager (armeabi-v7a): src/tt.c uses __uint128_t (64-bit only). Needs a
  mulhi fallback shim for 32-bit. Pattern will repeat for other modern
  NNUE engines; engines that cannot build for v7a ship arm64-v8a-only and
  the app filters the roster by device ABI.
