#!/usr/bin/env bash
# Gradle wrapper script for ExcaliburDB Phase 1
# This script handles building the multi-module project

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_DIR="$SCRIPT_DIR/gradle"

# Verify Gradle wrapper exists
if [ ! -f "$GRADLE_DIR/wrapper/gradle-wrapper.jar" ]; then
    echo "ERROR: Gradle wrapper not found. Download Gradle Wrapper first."
    echo "Run: ./gradlew wrapper"
    exit 1
fi

# Module build order (dependencies first)
MODULE_ORDER=(
    "excalibur-core"
    "excalibur-store"
    "excalibur-filter"
    "excalibur-vector"
    "excalibur-shard"
    "excalibur-query"
    "excalibur-llm"
    "excalibur-ingest"
    "excalibur-api"
    "excalibur-bench"
)

# Build all modules in dependency order
for module in "${MODULE_ORDER[@]}"; do
    echo "Building module: $module"
    ./gradlew :$module:build
    echo "  ✓ $module built successfully"
done

echo ""
echo "All modules built successfully!"
