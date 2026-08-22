#!/usr/bin/env bash
# Local verification gate — the project's source of truth for "green".
# (GitHub Actions is disabled; this script is the CI. See TASKS.md note on T-001.)
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
