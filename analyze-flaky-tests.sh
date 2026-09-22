#!/bin/bash
#
# Flaky Test Analyzer Script
#
# Runs integration tests multiple times and analyzes failure patterns
# to identify flaky tests.
#
# Usage:
#   ./analyze-flaky-tests.sh [runs] [-v]
#
# Arguments:
#   runs  - Number of test runs (default: 5, minimum: 3)
#   -v    - Verbose output (show individual test results)
#
# Examples:
#   ./analyze-flaky-tests.sh           # Run 5 times, normal output
#   ./analyze-flaky-tests.sh 10        # Run 10 times
#   ./analyze-flaky-tests.sh 3 -v      # Run 3 times with verbose output
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Default values
RUNS=5
VERBOSE=""
MODULE="excalibur-integration"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -v|--verbose)
            VERBOSE="-v"
            shift
            ;;
        -*)
            echo "Unknown option: $1"
            echo "Usage: $0 [runs] [-v]"
            exit 1
            ;;
        *)
            if [[ $1 =~ ^[0-9]+$ ]]; then
                RUNS=$1
            else
                echo "Invalid run count: $1"
                exit 1
            fi
            shift
            ;;
    esac
done

# Validate run count
if [[ $RUNS -lt 3 ]]; then
    echo "Error: Minimum run count is 3 for meaningful flakiness analysis"
    exit 1
fi

echo "============================================================"
echo "           Flaky Test Analysis for ExcaliburDB"
echo "============================================================"
echo ""
echo "Module: $MODULE"
echo "Runs: $RUNS"
echo "Verbose: $([[ -n "$VERBOSE" ]] && echo "yes" || echo "no")"
echo ""
echo "This will run the integration test suite $RUNS times."
echo "Estimated time: ~$((RUNS * 2)) minutes (depending on test count)"
echo ""
read -p "Continue? [y/N] " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

# Build the test module first (without running tests)
echo ""
echo "Building test module..."
./gradlew :excalibur-test:build -x test --quiet

# Run the analyzer
echo ""
echo "Running flaky test analysis..."
echo ""

# `|| EXIT_CODE=$?` rather than a bare call: `set -e` is on, and a non-zero exit from the
# analyzer would otherwise end the script here - taking the summary below with it, so the one
# run that found something is the one run that never says what.
EXIT_CODE=0
java --enable-preview --enable-native-access=ALL-UNNAMED \
    -cp "excalibur-test/build/classes/java/main:excalibur-test/build/resources/main" \
    com.eonmux.excalibur.test.FlakyTestAnalyzer "$MODULE" "$RUNS" "$VERBOSE" || EXIT_CODE=$?

echo ""
echo "============================================================"
if [[ $EXIT_CODE -eq 0 ]]; then
    echo "Analysis complete: All tests passed consistently"
else
    echo "Analysis complete: Issues found (see report above)"
fi
echo "============================================================"

exit $EXIT_CODE
