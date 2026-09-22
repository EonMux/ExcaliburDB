#!/bin/bash
#
# ExcaliburDB Coverage Report Generator
# Generates HTML and XML coverage reports for all modules
#
# Usage: ./scripts/generate-coverage-report.sh [options]
#
# Options:
#   --html     Generate HTML report only (default: both)
#   --xml      Generate XML report only (default: both)
#   --aggregate Generate aggregate report for all modules
#   --open     Open HTML report in browser after generation
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
REPORT_DIR="$PROJECT_ROOT/build/reports/jacoco"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default options
GENERATE_HTML=true
GENERATE_XML=true
AGGREGATE=true
OPEN_REPORT=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --html)
            GENERATE_XML=false
            shift
            ;;
        --xml)
            GENERATE_HTML=false
            shift
            ;;
        --aggregate)
            AGGREGATE=true
            shift
            ;;
        --open)
            OPEN_REPORT=true
            shift
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  ExcaliburDB Coverage Report Generator${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""

# Change to project root
cd "$PROJECT_ROOT"

# Clean previous reports
echo -e "${YELLOW}Cleaning previous reports...${NC}"
rm -rf "$REPORT_DIR"
mkdir -p "$REPORT_DIR"

# Every module, read from settings.gradle.kts rather than listed here. The previous hard-coded
# list held four of eighteen, so this script printed a completed coverage run over less than a
# quarter of the codebase -- a number that reads as the project's and is not.
readarray -t MODULES < <(
    sed -n '/^include(/,/^)/p' "$(dirname "$0")/../settings.gradle.kts" \
        | grep -oE '"[^"]+"' | tr -d '"' | sort
)

if [ ${#MODULES[@]} -eq 0 ]; then
    echo "ERROR: no modules found in settings.gradle.kts" >&2
    exit 1
fi

echo -e "${YELLOW}Generating coverage reports for modules:${NC}"
for module in "${MODULES[@]}"; do
    echo -e "  ${GREEN}•${NC} $module"
done
echo ""

# Run tests and generate reports for each module
for module in "${MODULES[@]}"; do
    echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"
    echo -e "${BLUE}Processing: $module${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"

    # Run tests with coverage
    echo -e "${YELLOW}Running tests and generating coverage data...${NC}"
    # PIPESTATUS, not $?: the pipeline ends in grep, and the `|| true` that keeps `set -e` from
    # killing the loop made $? unconditionally 0 - so every module, including one whose tests
    # failed outright, was reported with a green tick.
    set +e
    ./gradlew ":$module:test" ":$module:jacocoTestReport" --no-daemon 2>&1 \
        | grep -E "(BUILD|FAIL|PASS|test)"
    GRADLE_STATUS=${PIPESTATUS[0]}
    set -e

    if [ "$GRADLE_STATUS" -eq 0 ]; then
        echo -e "${GREEN}✓ Coverage report generated for $module${NC}"

        # Check if report exists
        HTML_REPORT="$module/build/reports/jacoco/test/html/index.html"
        XML_REPORT="$module/build/reports/jacoco/test/jacocoTestReport.xml"

        if [ -f "$HTML_REPORT" ]; then
            echo -e "  ${GREEN}HTML:${NC} $HTML_REPORT"
        fi
        if [ -f "$XML_REPORT" ]; then
            echo -e "  ${GREEN}XML:${NC} $XML_REPORT"
        fi
    else
        echo -e "${RED}✗ Failed to generate coverage for $module${NC}"
    fi
    echo ""
done

# Generate aggregate report
if [ "$AGGREGATE" = true ]; then
    echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"
    echo -e "${BLUE}Generating Aggregate Report${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"

    ./gradlew jacocoAggregateReport --no-daemon 2>&1 | grep -E "(BUILD|FAIL)" || true

    AGGREGATE_HTML="$REPORT_DIR/aggregate/html/index.html"
    AGGREGATE_XML="$REPORT_DIR/aggregate/coverage.xml"

    if [ -f "$AGGREGATE_HTML" ]; then
        echo -e "${GREEN}✓ Aggregate HTML report: $AGGREGATE_HTML${NC}"
    fi
    if [ -f "$AGGREGATE_XML" ]; then
        echo -e "${GREEN}✓ Aggregate XML report: $AGGREGATE_XML${NC}"
    fi
fi

# Generate summary report
echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"
echo -e "${BLUE}Coverage Summary${NC}"
echo -e "${BLUE}────────────────────────────────────────────────────────────${NC}"

SUMMARY_FILE="$REPORT_DIR/COVERAGE_SUMMARY.html"

# Try Java-based generator first
if [ -f "$PROJECT_ROOT/excalibur-core/build/classes/java/main/com/eonmux/excalibur/core/report/CoverageReportGenerator.class" ]; then
    echo -e "${YELLOW}Generating summary using Java report generator...${NC}"
    java -cp "$PROJECT_ROOT/excalibur-core/build/classes/java/main" \
         com.eonmux.excalibur.core.report.CoverageReportGenerator \
         "$PROJECT_ROOT" "$SUMMARY_FILE" 2>/dev/null || {
        echo -e "${YELLOW}Java generator failed, falling back to Python...${NC}"
    }
fi

# Fallback to Python if Java didn't work
if [ ! -f "$SUMMARY_FILE" ] && [ -f "$SCRIPT_DIR/coverage_report_generator.py" ]; then
    echo -e "${YELLOW}Generating summary using Python report generator...${NC}"
    python3 "$SCRIPT_DIR/coverage_report_generator.py" "$PROJECT_ROOT" "$SUMMARY_FILE" 2>/dev/null || {
        echo -e "${RED}Python generator also failed, generating basic report...${NC}"
    }
fi

# Final fallback: generate a simple static HTML summary
if [ ! -f "$SUMMARY_FILE" ]; then
    cat > "$SUMMARY_FILE" << 'HTMLEOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ExcaliburDB Coverage Report</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            min-height: 100vh;
            padding: 40px;
            color: #e0e0e0;
        }
        .container { max-width: 1200px; margin: 0 auto; }
        h1 {
            text-align: center;
            margin-bottom: 40px;
            font-size: 2.5rem;
            background: linear-gradient(90deg, #00d4ff, #7b2cbf);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }
        .module-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
            gap: 24px;
            margin-bottom: 40px;
        }
        .module-card {
            background: rgba(255,255,255,0.05);
            border-radius: 16px;
            padding: 24px;
            border: 1px solid rgba(255,255,255,0.1);
            transition: transform 0.3s ease, box-shadow 0.3s ease;
        }
        .module-card:hover {
            transform: translateY(-4px);
            box-shadow: 0 12px 40px rgba(0,212,255,0.15);
        }
        .module-name {
            font-size: 1.25rem;
            font-weight: 600;
            margin-bottom: 16px;
            color: #00d4ff;
        }
        .coverage-bar {
            height: 12px;
            background: rgba(255,255,255,0.1);
            border-radius: 6px;
            overflow: hidden;
            margin-bottom: 12px;
        }
        .coverage-fill {
            height: 100%;
            border-radius: 6px;
            transition: width 0.5s ease;
        }
        .coverage-excellent { background: linear-gradient(90deg, #00c853, #69f0ae); }
        .coverage-good { background: linear-gradient(90deg, #64dd17, #b2ff59); }
        .coverage-warning { background: linear-gradient(90deg, #ffc107, #ffeb3b); }
        .coverage-danger { background: linear-gradient(90deg, #ff5252, #ff8a80); }
        .coverage-text {
            display: flex;
            justify-content: space-between;
            font-size: 0.9rem;
        }
        .links {
            margin-top: 16px;
            display: flex;
            gap: 12px;
        }
        .links a {
            color: #7b2cbf;
            text-decoration: none;
            font-size: 0.85rem;
            padding: 6px 12px;
            background: rgba(123,44,191,0.2);
            border-radius: 6px;
            transition: background 0.2s ease;
        }
        .links a:hover { background: rgba(123,44,191,0.4); }
        .aggregate-section {
            background: rgba(255,255,255,0.05);
            border-radius: 16px;
            padding: 32px;
            border: 1px solid rgba(255,255,255,0.1);
            text-align: center;
        }
        .aggregate-section h2 {
            margin-bottom: 24px;
            color: #00d4ff;
        }
        .aggregate-percentage {
            font-size: 4rem;
            font-weight: 700;
            margin-bottom: 24px;
        }
        .footer {
            text-align: center;
            margin-top: 40px;
            color: #666;
            font-size: 0.85rem;
        }
        .threshold-met { color: #00c853; }
        .threshold-not-met { color: #ff5252; }
    </style>
</head>
<body>
    <div class="container">
        <h1>ExcaliburDB Coverage Report</h1>
        <div class="module-grid" id="modules">
            <!-- Modules will be populated by JavaScript -->
        </div>
        <div class="aggregate-section">
            <h2>Aggregate Coverage</h2>
            <div class="aggregate-percentage" id="aggregate">--</div>
            <div class="links">
                <a href="aggregate/html/index.html">View HTML Report</a>
                <a href="aggregate/coverage.xml">View XML Report</a>
            </div>
        </div>
        <div class="footer">
            <p>Generated: <span id="timestamp"></span></p>
            <p>Threshold: 80% minimum coverage per module</p>
        </div>
    </div>
    <script>
        document.getElementById('timestamp').textContent = new Date().toISOString();

        // Module coverage data (will be populated from XML reports)
        const modules = [
            { name: 'excalibur-core', path: 'excalibur-core/build/reports/jacoco/test' },
            { name: 'excalibur-store', path: 'excalibur-store/build/reports/jacoco/test' },
            { name: 'excalibur-filter', path: 'excalibur-filter/build/reports/jacoco/test' },
            { name: 'excalibur-vector', path: 'excalibur-vector/build/reports/jacoco/test' }
        ];

        function getCoverageClass(percentage) {
            if (percentage >= 90) return 'coverage-excellent';
            if (percentage >= 80) return 'coverage-good';
            if (percentage >= 60) return 'coverage-warning';
            return 'coverage-danger';
        }

        function getThresholdClass(percentage) {
            return percentage >= 80 ? 'threshold-met' : 'threshold-not-met';
        }

        // Placeholder - actual coverage will be read from XML
        modules.forEach((module, index) => {
            const card = document.createElement('div');
            card.className = 'module-card';
            card.innerHTML = `
                <div class="module-name">${module.name}</div>
                <div class="coverage-bar">
                    <div class="coverage-fill" style="width: 0%;"></div>
                </div>
                <div class="coverage-text">
                    <span>Coverage</span>
                    <span class="loading">Loading...</span>
                </div>
                <div class="links">
                    <a href="${module.path}/html/index.html">HTML Report</a>
                    <a href="${module.path}/jacocoTestReport.xml">XML Report</a>
                </div>
            `;
            document.getElementById('modules').appendChild(card);
        });
    </script>
</body>
</html>
HTMLEOF
    echo -e "${GREEN}✓ Summary report: $SUMMARY_FILE${NC}"
fi

echo ""
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}Coverage report generation complete!${NC}"
echo -e "${BLUE}════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}Report locations:${NC}"
echo -e "  ${GREEN}Summary:${NC}    $REPORT_DIR/COVERAGE_SUMMARY.html"
echo -e "  ${GREEN}Aggregate:${NC}  $REPORT_DIR/aggregate/html/index.html"
echo ""
for module in "${MODULES[@]}"; do
    echo -e "  ${GREEN}$module:${NC} $module/build/reports/jacoco/test/html/index.html"
done

# Open report if requested
if [ "$OPEN_REPORT" = true ]; then
    echo ""
    echo -e "${YELLOW}Opening report in browser...${NC}"
    if command -v xdg-open &> /dev/null; then
        xdg-open "$SUMMARY_FILE" 2>/dev/null &
    elif command -v open &> /dev/null; then
        open "$SUMMARY_FILE" 2>/dev/null &
    else
        echo -e "${YELLOW}Please open manually: $SUMMARY_FILE${NC}"
    fi
fi
