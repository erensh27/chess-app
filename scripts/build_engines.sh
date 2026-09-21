#!/usr/bin/env bash
# Cross-compile every engine in engines/manifest.json for the ABI targets,
# emitting real PIE executables named lib<engine>.so into
# DroidFishApp/src/main/jniLibs/<abi>/ so Android extracts them with exec
# permission (native lib dir). Skeleton: engine recipes land per-engine.
set -euo pipefail
echo "build_engines: no engine recipes yet - skipping (scaffold)"
