#!/bin/bash
#
# Flaky Test Detection Script
#
# Runs integration tests multiple times and detects flaky tests by
# analyzing patterns in test failures across runs.
#
# Usage:
#   ./run-flaky-analysis.sh [options]
#
# Options:
#   -r, --runs N      Number of test runs (default: 5, minimum: 3)
#   -m, --module M    Module to test (default: excalibur-integration)
#   -v, --verbose     Show verbose output
#   -h, --help        Show this help message
#
# Output:
#   - Summary of test results across all runs
#   - List of flaky tests (tests that pass sometimes and fail sometimes)
#   - List of consistently failing tests
#   - Failure pattern analysis
#
# Exit codes:
#   0 - All tests passed consistently
#   1 - Flaky tests or consistent failures detected
#   2 - Script error
#

set -e

# Configuration
DEFAULT_RUNS=5
DEFAULT_MODULE="excalibur-integration"
MIN_RUNS=3

# State
RUNS=$DEFAULT_RUNS
MODULE=$DEFAULT_MODULE
VERBOSE=false
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors (disabled if not a terminal)
if [[ -t 1 ]]; then
    RED='\033[0;31m'
    GREEN='\033[0;32m'
    YELLOW='\033[0;33m'
    BLUE='\033[0;34m'
    BOLD='\033[1m'
    RESET='\033[0m'
else
    RED=''
    GREEN=''
    YELLOW=''
    BLUE=''
    BOLD=''
    RESET=''
fi

# Temporary files for results
RESULTS_DIR=""
declare -A TEST_PASS_COUNT
declare -A TEST_FAIL_COUNT
declare -A TEST_ERROR_MESSAGES

# Cleanup function
cleanup() {
    if [[ -n "$RESULTS_DIR" && -d "$RESULTS_DIR" ]]; then
        rm -rf "$RESULTS_DIR"
    fi
}
trap cleanup EXIT

# Print functions
log() {
    echo -e "${BLUE}[INFO]${RESET} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${RESET} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${RESET} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${RESET} $1"
}

log_header() {
    echo ""
    echo -e "${BOLD}$1${RESET}"
    echo "============================================================"
}

# Show help
show_help() {
    cat << EOF
Flaky Test Detection Script for ExcaliburDB

Runs integration tests multiple times and analyzes failure patterns
to identify flaky tests (tests that pass sometimes and fail sometimes).

Usage:
  $0 [options]

Options:
  -r, --runs N      Number of test runs (default: $DEFAULT_RUNS, minimum: $MIN_RUNS)
  -m, --module M    Module to test (default: $DEFAULT_MODULE)
  -v, --verbose     Show verbose output (individual test results)
  -h, --help        Show this help message

Examples:
  $0                           # Run 5 times on default module
  $0 -r 10                     # Run 10 times
  $0 -m excalibur-query -r 3   # Test query module 3 times
  $0 -v                        # Verbose output

Exit Codes:
  0 - All tests passed consistently
  1 - Flaky tests or consistent failures detected
  2 - Script error
EOF
    exit 0
}

# Parse arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -r|--runs)
                if [[ -z "$2" || "$2" =~ ^- ]]; then
                    log_error "Option $1 requires a numeric argument"
                    exit 2
                fi
                RUNS="$2"
                shift 2
                ;;
            -m|--module)
                if [[ -z "$2" || "$2" =~ ^- ]]; then
                    log_error "Option $1 requires a module name"
                    exit 2
                fi
                MODULE="$2"
                shift 2
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -h|--help)
                show_help
                ;;
            -*)
                log_error "Unknown option: $1"
                show_help
                ;;
            *)
                log_error "Unexpected argument: $1"
                show_help
                ;;
        esac
    done

    # Validate run count
    if ! [[ "$RUNS" =~ ^[0-9]+$ ]]; then
        log_error "Run count must be a number, got: $RUNS"
        exit 2
    fi

    if [[ $RUNS -lt $MIN_RUNS ]]; then
        log_error "Run count must be at least $MIN_RUNS for meaningful analysis, got: $RUNS"
        exit 2
    fi
}

# Parse test results from Gradle output
# Format: "TestClass > Nested > testName PASSED|FAILED|SKIPPED"
parse_test_results() {
    local output_file="$1"
    local run_num="$2"

    while IFS= read -r line; do
        # Trim line
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line%"${line##*[![:space:]]}"}"

        # Parse Gradle test output format:
        # "Config Comprehensive Tests > Getter Tests > Should return visibility config PASSED"
        # "CRC32ChecksumTest > testComputeString() PASSED"
        # "SomeTestClass > NestedClass > testMethodName FAILED"

        # Match lines ending with PASSED, FAILED, or SKIPPED
        if [[ "$line" =~ ^(.+)\ (PASSED|FAILED|SKIPPED)$ ]]; then
            local test_path="${BASH_REMATCH[1]}"
            local status="${BASH_REMATCH[2]}"

            # Clean up test path - remove parentheses from method names
            test_path="${test_path//\(\)/}"

            # Create a unique test name by combining class and method
            # Replace " > " with "." for cleaner names
            local full_name="${test_path// > /./}"

            case "$status" in
                PASSED)
                    ((TEST_PASS_COUNT["$full_name"]++)) || true
                    if [[ "$VERBOSE" == "true" ]]; then
                        echo "    [PASS] $full_name"
                    fi
                    ;;
                FAILED)
                    ((TEST_FAIL_COUNT["$full_name"]++)) || true
                    if [[ "$VERBOSE" == "true" ]]; then
                        echo "    [FAIL] $full_name"
                    fi
                    ;;
                SKIPPED)
                    if [[ "$VERBOSE" == "true" ]]; then
                        echo "    [SKIP] $full_name"
                    fi
                    ;;
            esac
        fi
    done < "$output_file"
}

# Run tests once
run_tests() {
    local run_num="$1"
    local output_file="$2"

    log "Running test suite (run $run_num/$RUNS)..."

    # Note: Don't use --quiet as it suppresses test output
    # Use --rerun-tasks to ensure tests run each time
    if ./gradlew ":$MODULE:test" --console=plain --no-daemon --rerun-tasks > "$output_file" 2>&1; then
        return 0
    else
        return 1
    fi
}

# Analyze results and generate report
analyze_results() {
    log_header "Analysis Results"

    local flaky_tests=()
    local consistent_failures=()
    local consistent_passes=()

    # Categorize each test
    for test_name in "${!TEST_PASS_COUNT[@]}"; do
        local pass_count="${TEST_PASS_COUNT[$test_name]:-0}"
        local fail_count="${TEST_FAIL_COUNT[$test_name]:-0}"
        local total_runs=$((pass_count + fail_count))

        # Skip if no runs (shouldn't happen)
        if [[ $total_runs -eq 0 ]]; then
            continue
        fi

        # Calculate pass rate
        # Using bc for floating point arithmetic
        local pass_rate=$(echo "scale=2; $pass_count / $total_runs" | bc)

        if [[ $fail_count -eq 0 ]]; then
            # Consistently passing
            consistent_passes+=("$test_name")
        elif [[ $pass_count -eq 0 ]]; then
            # Consistently failing
            consistent_failures+=("$test_name")
        else
            # Flaky - sometimes passes, sometimes fails
            flaky_tests+=("$test_name|$pass_count|$fail_count|$pass_rate")
        fi
    done

    # Report flaky tests
    if [[ ${#flaky_tests[@]} -gt 0 ]]; then
        echo ""
        echo -e "${RED}${BOLD}FLAKY TESTS DETECTED (${#flaky_tests[@]}):${RESET}"
        echo "These tests passed sometimes and failed sometimes:"
        echo ""
        printf "  %-60s %8s %8s %10s\n" "TEST NAME" "PASSES" "FAILS" "PASS RATE"
        printf "  %s\n" "$(printf '%.0s-' {1..90})"

        for entry in "${flaky_tests[@]}"; do
            IFS='|' read -r name passes fails rate <<< "$entry"
            printf "  %-60s %8d %8d %9s%%\n" "$name" "$passes" "$fails" "$(echo "$rate * 100" | bc)"
        done
        echo ""
    fi

    # Report consistent failures
    if [[ ${#consistent_failures[@]} -gt 0 ]]; then
        echo ""
        echo -e "${RED}${BOLD}CONSISTENT FAILURES (${#consistent_failures[@]}):${RESET}"
        echo "These tests failed in ALL runs:"
        echo ""
        for test in "${consistent_failures[@]}"; do
            echo "  - $test"
        done
        echo ""
    fi

    # Summary
    log_header "Summary"

    local total_tests=$((${#TEST_PASS_COUNT[@]} + ${#TEST_FAIL_COUNT[@]}))
    # Deduplicate test names
    local unique_tests=0
    declare -A seen_tests
    for test in "${!TEST_PASS_COUNT[@]}"; do
        if [[ -z "${seen_tests[$test]}" ]]; then
            ((unique_tests++)) || true
            seen_tests["$test"]=1
        fi
    done

    echo "  Module:           $MODULE"
    echo "  Runs performed:   $RUNS"
    echo "  Unique tests:     $unique_tests"
    echo "  Consistent passes: ${#consistent_passes[@]}"
    echo "  Consistent fails:  ${#consistent_failures[@]}"
    echo "  Flaky tests:       ${#flaky_tests[@]}"
    echo ""

    # Calculate overall pass rate
    local total_passes=0
    local total_tests_run=0
    for test in "${!TEST_PASS_COUNT[@]}"; do
        total_passes=$((total_passes + ${TEST_PASS_COUNT[$test]:-0}))
        total_tests_run=$((total_tests_run + ${TEST_PASS_COUNT[$test]:-0} + ${TEST_FAIL_COUNT[$test]:-0}))
    done

    if [[ $total_tests_run -gt 0 ]]; then
        local overall_rate=$(echo "scale=1; $total_passes * 100 / $total_tests_run" | bc)
        echo "  Overall pass rate: ${overall_rate}%"
    fi
    echo ""

    # Conclusion
    if [[ ${#flaky_tests[@]} -eq 0 && ${#consistent_failures[@]} -eq 0 ]]; then
        log_success "All tests passed consistently across all $RUNS runs!"
        return 0
    else
        if [[ ${#flaky_tests[@]} -gt 0 ]]; then
            log_warn "Found ${#flaky_tests[@]} flaky test(s) that need investigation"
        fi
        if [[ ${#consistent_failures[@]} -gt 0 ]]; then
            log_error "Found ${#consistent_failures[@]} consistently failing test(s)"
        fi
        return 1
    fi
}

# Main function
main() {
    parse_args "$@"

    log_header "Flaky Test Analysis for ExcaliburDB"

    echo "  Module:      $MODULE"
    echo "  Runs:        $RUNS"
    echo "  Verbose:     $VERBOSE"
    echo "  Working dir: $SCRIPT_DIR"
    echo ""

    # Estimate time
    local estimated_minutes=$((RUNS * 2))
    echo "Estimated time: ~${estimated_minutes} minutes (depending on test count)"
    echo ""

    # Create results directory
    RESULTS_DIR=$(mktemp -d)
    log "Created results directory: $RESULTS_DIR"

    # Change to script directory
    cd "$SCRIPT_DIR"

    # Run tests multiple times
    local run_failures=0
    for ((i=1; i<=RUNS; i++)); do
        local output_file="$RESULTS_DIR/run_${i}.log"

        if run_tests "$i" "$output_file"; then
            parse_test_results "$output_file" "$i"
        else
            parse_test_results "$output_file" "$i"
            ((run_failures++)) || true
        fi
    done

    # Analyze results
    analyze_results
    local result=$?

    # Cleanup
    cleanup

    exit $result
}

# Run main
main "$@"
