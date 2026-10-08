#!/usr/bin/env bash
# The CI checks, locally, before pushing. On a Mac it also compiles iOS.
# Usage: scripts/check.sh [--fast]   (--fast: ktlint and unit tests only)
set -euo pipefail
cd "$(dirname "$0")/.."

tasks=(:composeApp:ktlintCheck :composeApp:testDebugUnitTest)
if [[ "${1:-}" != "--fast" ]]; then
  # Minified like the store build: R8 fails here on missing classes or rules.
  tasks+=(:composeApp:assemblePreview)
  [[ "$(uname)" == "Darwin" ]] && tasks+=(:composeApp:compileKotlinIosArm64)
fi

./gradlew "${tasks[@]}"

# Room exports the schema on every build: a diff means composeApp/schemas/ must be committed.
git add -N composeApp/schemas
git diff --exit-code --stat -- composeApp/schemas || {
  echo "Room schema changed: commit composeApp/schemas/" >&2
  exit 1
}
echo "All checks passed."
