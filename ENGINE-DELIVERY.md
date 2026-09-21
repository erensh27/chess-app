# Engine delivery architecture (decided 2026-09-21, user steering)

User decision: "download nets on first run, ship no engine pre downloaded."

## Why targetSdk 28

Android blocks executing binaries from app-private storage only for apps
targeting API 29+. Apps targeting <=28 run in the legacy SELinux domain
where app_data_file execute is allowed - this is how DroidFish's own
"install engine from file" works on Android 14 (DroidFish 1.90 targets 28).

So: compileSdk 31 (modern APIs at compile time), targetSdk 28 (legacy exec
domain at runtime). Lean APK, zero engines bundled.

## Flow

1. App ships with engines/manifest.json catalog (45 engines: name, version,
   CCRL 40/15 rating, license, per-ABI availability).
2. Engine picker shows rating in the dropdown; user picks engines for an
   arena match; missing engines download on demand.
3. Downloads land in filesDir/engines/<id>/<abi>/, chmod +x, executed via
   DroidFish's ExternalEngine (child process + UCI pipes).
4. Engines are built with API 24 toolchains -> devices must run Android 7+.
5. Most engines embed their NNUE net (incbin) = single-file download;
   external-net engines fetch the net next to the binary.

## Hosting

CI (.github/workflows) cross-builds all engines for arm64-v8a and
armeabi-v7a and publishes them as GitHub Release assets on this repo
(per-engine per-ABI zips: lib<id>.so + net if external + LICENSE).

The repo is PUBLIC (flipped 2026-09-21 6:47 PM IST), so the app downloads
engine packs anonymously straight from the engines-latest release. No tokens
ship in the app.

## 32-bit note

Modern NNUE engines assume 64-bit (e.g. __uint128_t TT indexing). Engines
that cannot build for armeabi-v7a ship arm64-v8a-only; the app filters the
catalog by device ABI. scripts/patches/ carries 32-bit shims where feasible.

## Arena match picker (user steering 2026-09-21 6:46 PM)

Both engine dropdowns list EVERY engine in the catalog, each showing its
CCRL 40/15 rating. Mirror matches are allowed (Ravager vs Ravager,
Stockfish vs Stockfish) - no filtering of the second dropdown based on the
first selection. Independent time controls per side, FEN start support.
