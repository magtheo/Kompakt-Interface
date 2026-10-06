#!/usr/bin/env bash
# Local verification gate — the project's source of truth for "green".
# (CI in .github/workflows/ci.yml runs the same three steps; this script is the local equivalent.)
# Usage: scripts/verify.sh            — build, unit tests, lint
#        scripts/verify.sh --clean    — same, from a cold Gradle cache
set -euo pipefail
cd "$(dirname "$0")/.."

# Build environment (JDK 17, Android SDK) — see docs/development-plan.md Phase 0.
if [ -f "$HOME/tools/env.sh" ]; then
  # shellcheck disable=SC1091
  . "$HOME/tools/env.sh"
fi

if [ "${1:-}" = "--clean" ]; then
  ./gradlew clean
fi

./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
echo "verify: OK"
