# Engine Arena

Android chess app for engine-vs-engine battles: 20+ strong open-source
UCI engines (CCRL ~3200-3650+) bundled as on-device native binaries, each
labeled with its real CCRL rating. Independent time controls, FEN starts,
PGN export, chess.com-clean board.

Built as a fork of DroidFish 1.90 (GPLv3). Engine Arena is GPLv3; each
bundled engine keeps its own license (see NOTICE and engines/manifest.json).

## Build

APKs are built by GitHub Actions (`.github/workflows/android.yml`): the
workflow cross-compiles every engine with the Android NDK for
`arm64-v8a` and `armeabi-v7a`, then assembles the APK and uploads it as
an artifact.

## Status

Early scaffold: DroidFish 1.90 base, rebranded, Android 12 (SDK 31)
compliant. Engine roster is being license/rating verified - see
engines/manifest.json.
