#!/usr/bin/env bash
# Builds every module in the project.
#
# Gradle already knows the module list and the dependency order between them, and builds
# independent modules in parallel. Enumerating modules here only creates a second list that
# drifts from settings.gradle.kts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "ERROR: Gradle wrapper not found at gradle/wrapper/gradle-wrapper.jar" >&2
    exit 1
fi

./gradlew build "$@"
echo "All modules built successfully."
