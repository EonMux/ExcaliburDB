#!/bin/bash
#
# Flaky test analysis for ExcaliburDB.
#
# Runs one module's test suite several times and reports every test that passed in some runs
# and failed in others, and every test that failed in all of them. The work is done by
# com.eonmux.excalibur.test.FlakyTestAnalyzer, which reads the JUnit XML reports Gradle writes
# for each run rather than scraping console output; this script builds it, validates the
# arguments and runs it.
#
# Usage:
#   ./analyze-flaky-tests.sh [options] [runs]
#
# Options:
#   -r, --runs N      Number of test runs (default: 5, minimum: 3). A bare number does the same.
#   -m, --module M    Module to test (default: excalibur-integration)
#   -v, --verbose     Stream Gradle's output for every run
#   -y, --yes         Do not ask for confirmation before starting
#   -h, --help        Show this help and exit
#
# Examples:
#   ./analyze-flaky-tests.sh                           # 5 runs of excalibur-integration
#   ./analyze-flaky-tests.sh 10                        # 10 runs
#   ./analyze-flaky-tests.sh -m excalibur-query -r 3   # 3 runs of the query module
#   ./analyze-flaky-tests.sh -y -v                     # no prompt, Gradle output shown
#
# Exit codes:
#   0 - Every test passed in every run
#   1 - Flaky tests or consistent failures were found
#   2 - The command line was wrong, or the analysis could not be carried out
#

set -euo pipefail

readonly DEFAULT_RUNS=5
readonly DEFAULT_MODULE="excalibur-integration"
readonly MIN_RUNS=3
readonly EXIT_USAGE=2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

RUNS=$DEFAULT_RUNS
MODULE=$DEFAULT_MODULE
VERBOSE=false
ASSUME_YES=false

print_help() {
    cat << EOF
Flaky test analysis for ExcaliburDB

Runs one module's test suite several times and reports every test that passed in some runs
and failed in others, and every test that failed in all of them.

Usage:
  $0 [options] [runs]

Options:
  -r, --runs N      Number of test runs (default: $DEFAULT_RUNS, minimum: $MIN_RUNS). A bare number does the same.
  -m, --module M    Module to test (default: $DEFAULT_MODULE)
  -v, --verbose     Stream Gradle's output for every run
  -y, --yes         Do not ask for confirmation before starting
  -h, --help        Show this help and exit

Exit codes:
  0 - Every test passed in every run
  1 - Flaky tests or consistent failures were found
  2 - The command line was wrong, or the analysis could not be carried out
EOF
}

# Reports a command-line mistake and exits with the usage code. The help goes to stderr, so a
# caller capturing stdout does not mistake it for a result.
usage_error() {
    echo "analyze-flaky-tests: $1" >&2
    echo "" >&2
    print_help >&2
    exit $EXIT_USAGE
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -r|--runs)
                [[ $# -ge 2 && -n "$2" && ! "$2" =~ ^- ]] || usage_error "$1 needs a run count"
                RUNS="$2"
                shift 2
                ;;
            -m|--module)
                [[ $# -ge 2 && -n "$2" && ! "$2" =~ ^- ]] || usage_error "$1 needs a module name"
                MODULE="$2"
                shift 2
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -y|--yes)
                ASSUME_YES=true
                shift
                ;;
            -h|--help)
                print_help
                exit 0
                ;;
            -*)
                usage_error "unknown option: $1"
                ;;
            *)
                [[ "$1" =~ ^[0-9]+$ ]] || usage_error "unexpected argument: $1"
                RUNS="$1"
                shift
                ;;
        esac
    done

    [[ "$RUNS" =~ ^[0-9]+$ ]] || usage_error "the run count must be a whole number, got: $RUNS"
    if (( RUNS < MIN_RUNS )); then
        usage_error "the run count must be at least $MIN_RUNS for a pattern to mean anything, got: $RUNS"
    fi
    [[ -d "$SCRIPT_DIR/${MODULE//://}" ]] || usage_error "no such module: $MODULE"
}

# Asks before starting, because a run is minutes of machine time per repetition. Skipped with
# --yes, and when there is no terminal to answer from - a prompt nobody can see would otherwise
# hang the caller or read its input as an answer.
confirm() {
    if [[ "$ASSUME_YES" == "true" || ! -t 0 ]]; then
        return 0
    fi
    local reply
    read -r -p "Continue? [y/N] " -n 1 reply
    echo ""
    [[ "$reply" =~ ^[Yy]$ ]]
}

main() {
    parse_args "$@"
    cd "$SCRIPT_DIR"

    echo "============================================================"
    echo "           Flaky Test Analysis for ExcaliburDB"
    echo "============================================================"
    echo ""
    echo "Module:  $MODULE"
    echo "Runs:    $RUNS"
    echo "Verbose: $([[ "$VERBOSE" == "true" ]] && echo "yes" || echo "no")"
    echo ""
    echo "This runs the $MODULE test suite $RUNS times."
    echo ""

    if ! confirm; then
        echo "Aborted."
        exit 0
    fi

    echo ""
    echo "Compiling the analyzer..."
    if ! ./gradlew --quiet :excalibur-test:classes; then
        echo "analyze-flaky-tests: the analyzer did not compile" >&2
        exit $EXIT_USAGE
    fi

    echo ""
    echo "Running flaky test analysis..."
    echo ""

    local analyzer_args=("$MODULE" "$RUNS")
    if [[ "$VERBOSE" == "true" ]]; then
        analyzer_args+=("-v")
    fi

    # `|| exit_code=$?` rather than a bare call: `set -e` is on, and a non-zero exit from the
    # analyzer would otherwise end the script here - taking the summary below with it, so the
    # one run that found something is the one run that never says what.
    local exit_code=0
    java --enable-preview --enable-native-access=ALL-UNNAMED \
        -cp "excalibur-test/build/classes/java/main:excalibur-test/build/resources/main" \
        com.eonmux.excalibur.test.FlakyTestAnalyzer "${analyzer_args[@]}" || exit_code=$?

    echo ""
    echo "============================================================"
    case $exit_code in
        0) echo "Analysis complete: every test passed in every run" ;;
        1) echo "Analysis complete: flaky or failing tests found (see the report above)" ;;
        *) echo "Analysis could not be completed (exit code $exit_code)" ;;
    esac
    echo "============================================================"

    exit $exit_code
}

main "$@"
