#!/usr/bin/env python3
"""
ExcaliburDB Coverage Report Generator

Parses JaCoCo XML reports and generates a consolidated HTML summary report.
This script serves as a fallback when the Java-based generator is unavailable.

Usage:
    python3 scripts/coverage_report_generator.py [project_root] [output_path]

Arguments:
    project_root - Root directory of the project (default: current directory)
    output_path  - Path for the HTML output file (default: build/reports/jacoco/COVERAGE_SUMMARY.html)
"""

import os
import re
import sys
from datetime import datetime
from pathlib import Path
from xml.etree import ElementTree as ET


# Coverage threshold (80% minimum per project constraint)
COVERAGE_THRESHOLD = 0.80

# Target modules for coverage reporting
TARGET_MODULES = [
    "excalibur-core",
    "excalibur-store",
    "excalibur-filter",
    "excalibur-vector"
]


class CoverageData:
    """Holds coverage metrics for a single module."""

    def __init__(self, module_name: str):
        self.module_name = module_name
        self.instruction_covered = 0
        self.instruction_missed = 0
        self.branch_covered = 0
        self.branch_missed = 0
        self.line_covered = 0
        self.line_missed = 0
        self.method_covered = 0
        self.method_missed = 0
        self.class_covered = 0
        self.class_missed = 0

    @property
    def instruction_percentage(self) -> float:
        total = self.instruction_covered + self.instruction_missed
        return (self.instruction_covered / total * 100) if total > 0 else 0.0

    @property
    def branch_percentage(self) -> float:
        total = self.branch_covered + self.branch_missed
        return (self.branch_covered / total * 100) if total > 0 else 0.0

    @property
    def line_percentage(self) -> float:
        total = self.line_covered + self.line_missed
        return (self.line_covered / total * 100) if total > 0 else 0.0

    @property
    def passes_threshold(self) -> bool:
        return self.instruction_percentage >= COVERAGE_THRESHOLD * 100


def parse_jacoco_xml(xml_path: Path) -> CoverageData:
    """Parse a JaCoCo XML report and extract coverage metrics."""
    # Path structure: project_root / module / build / reports / jacoco / test / jacocoTestReport.xml
    # Need 5 parents from file to get module name
    module_name = xml_path.parent.parent.parent.parent.parent.name
    data = CoverageData(module_name)

    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()

        # Find all counter elements
        for counter in root.iter('counter'):
            counter_type = counter.get('type', '')
            covered = int(counter.get('covered', 0))
            missed = int(counter.get('missed', 0))

            if counter_type == 'INSTRUCTION':
                data.instruction_covered = covered
                data.instruction_missed = missed
            elif counter_type == 'BRANCH':
                data.branch_covered = covered
                data.branch_missed = missed
            elif counter_type == 'LINE':
                data.line_covered = covered
                data.line_missed = missed
            elif counter_type == 'METHOD':
                data.method_covered = covered
                data.method_missed = missed
            elif counter_type == 'CLASS':
                data.class_covered = covered
                data.class_missed = missed

    except Exception as e:
        print(f"Warning: Failed to parse {xml_path}: {e}")

    return data


def get_coverage_class(percentage: float) -> str:
    """Return CSS class based on coverage percentage."""
    if percentage >= 90:
        return "coverage-excellent"
    elif percentage >= 80:
        return "coverage-good"
    elif percentage >= 60:
        return "coverage-warning"
    else:
        return "coverage-danger"


def get_threshold_class(percentage: float) -> str:
    """Return CSS class for threshold status."""
    return "threshold-met" if percentage >= COVERAGE_THRESHOLD * 100 else "threshold-not-met"


def generate_html_report(coverages: list[CoverageData], output_path: Path) -> None:
    """Generate the consolidated HTML report."""

    # Calculate aggregate coverage
    total_instruction_covered = sum(c.instruction_covered for c in coverages)
    total_instruction_missed = sum(c.instruction_missed for c in coverages)
    total_branch_covered = sum(c.branch_covered for c in coverages)
    total_branch_missed = sum(c.branch_missed for c in coverages)
    total_line_covered = sum(c.line_covered for c in coverages)
    total_line_missed = sum(c.line_missed for c in coverages)

    total_covered = total_instruction_covered + total_branch_covered
    total_missed = total_instruction_missed + total_branch_missed
    aggregate_percentage = (total_covered / (total_covered + total_missed) * 100) if (total_covered + total_missed) > 0 else 0.0

    threshold_met = aggregate_percentage >= COVERAGE_THRESHOLD * 100

    html = f'''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ExcaliburDB Coverage Report</title>
    <style>
        * {{ margin: 0; padding: 0; box-sizing: border-box; }}

        body {{
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
            min-height: 100vh;
            padding: 40px 20px;
            color: #e0e0e0;
            line-height: 1.6;
        }}

        .container {{
            max-width: 1400px;
            margin: 0 auto;
        }}

        h1 {{
            text-align: center;
            margin-bottom: 12px;
            font-size: 2.5rem;
            font-weight: 700;
            background: linear-gradient(90deg, #00d4ff, #7b2cbf);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }}

        .subtitle {{
            text-align: center;
            color: #888;
            margin-bottom: 40px;
            font-size: 1rem;
        }}

        .summary-card {{
            background: rgba(255,255,255,0.05);
            border-radius: 20px;
            padding: 40px;
            border: 1px solid rgba(255,255,255,0.1);
            text-align: center;
            margin-bottom: 40px;
            backdrop-filter: blur(10px);
        }}

        .aggregate-percentage {{
            font-size: 5rem;
            font-weight: 700;
            margin-bottom: 16px;
        }}

        .aggregate-label {{
            font-size: 1.1rem;
            color: #888;
            margin-bottom: 24px;
        }}

        .threshold-badge {{
            display: inline-block;
            padding: 8px 24px;
            border-radius: 24px;
            font-weight: 600;
            font-size: 0.9rem;
        }}

        .threshold-met {{
            background: rgba(0, 200, 83, 0.2);
            color: #00c853;
            border: 1px solid rgba(0, 200, 83, 0.3);
        }}

        .threshold-not-met {{
            background: rgba(255, 82, 82, 0.2);
            color: #ff5252;
            border: 1px solid rgba(255, 82, 82, 0.3);
        }}

        .stats-row {{
            display: flex;
            justify-content: center;
            gap: 40px;
            margin-top: 32px;
            flex-wrap: wrap;
        }}

        .stat-item {{
            text-align: center;
        }}

        .stat-value {{
            font-size: 1.8rem;
            font-weight: 600;
            color: #00d4ff;
        }}

        .stat-label {{
            font-size: 0.85rem;
            color: #888;
            margin-top: 4px;
        }}

        .module-grid {{
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
            gap: 24px;
            margin-bottom: 40px;
        }}

        .module-card {{
            background: rgba(255,255,255,0.05);
            border-radius: 16px;
            padding: 28px;
            border: 1px solid rgba(255,255,255,0.1);
            transition: transform 0.3s ease, box-shadow 0.3s ease;
        }}

        .module-card:hover {{
            transform: translateY(-4px);
            box-shadow: 0 12px 40px rgba(0,212,255,0.15);
        }}

        .module-header {{
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 20px;
        }}

        .module-name {{
            font-size: 1.3rem;
            font-weight: 600;
            color: #fff;
        }}

        .module-percentage {{
            font-size: 1.5rem;
            font-weight: 700;
        }}

        .coverage-bar {{
            height: 10px;
            background: rgba(255,255,255,0.1);
            border-radius: 5px;
            overflow: hidden;
            margin-bottom: 16px;
        }}

        .coverage-fill {{
            height: 100%;
            border-radius: 5px;
            transition: width 0.8s ease;
        }}

        .coverage-excellent {{ background: linear-gradient(90deg, #00c853, #69f0ae); }}
        .coverage-good {{ background: linear-gradient(90deg, #64dd17, #b2ff59); }}
        .coverage-warning {{ background: linear-gradient(90deg, #ffc107, #ffeb3b); }}
        .coverage-danger {{ background: linear-gradient(90deg, #ff5252, #ff8a80); }}

        .metrics-grid {{
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 12px;
        }}

        .metric {{
            background: rgba(0,0,0,0.2);
            padding: 12px;
            border-radius: 8px;
        }}

        .metric-label {{
            font-size: 0.75rem;
            color: #888;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}

        .metric-value {{
            font-size: 1rem;
            font-weight: 600;
            margin-top: 4px;
        }}

        .links {{
            margin-top: 20px;
            display: flex;
            gap: 12px;
        }}

        .links a {{
            color: #7b2cbf;
            text-decoration: none;
            font-size: 0.85rem;
            padding: 8px 16px;
            background: rgba(123,44,191,0.2);
            border-radius: 8px;
            transition: background 0.2s ease;
            flex: 1;
            text-align: center;
        }}

        .links a:hover {{ background: rgba(123,44,191,0.4); }}

        .detail-section {{
            background: rgba(255,255,255,0.05);
            border-radius: 16px;
            padding: 32px;
            border: 1px solid rgba(255,255,255,0.1);
            margin-bottom: 40px;
        }}

        .detail-section h2 {{
            color: #00d4ff;
            margin-bottom: 24px;
            font-size: 1.3rem;
        }}

        table {{
            width: 100%;
            border-collapse: collapse;
        }}

        th, td {{
            padding: 16px;
            text-align: left;
            border-bottom: 1px solid rgba(255,255,255,0.1);
        }}

        th {{
            color: #888;
            font-weight: 600;
            font-size: 0.85rem;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }}

        td {{
            font-size: 0.95rem;
        }}

        tr:hover td {{
            background: rgba(255,255,255,0.03);
        }}

        .footer {{
            text-align: center;
            color: #666;
            font-size: 0.85rem;
            padding: 20px;
        }}

        .footer a {{
            color: #00d4ff;
            text-decoration: none;
        }}

        @media (max-width: 768px) {{
            h1 {{ font-size: 1.8rem; }}
            .aggregate-percentage {{ font-size: 3.5rem; }}
            .module-grid {{ grid-template-columns: 1fr; }}
            .stats-row {{ gap: 20px; }}
        }}
    </style>
</head>
<body>
    <div class="container">
        <h1>ExcaliburDB Coverage Report</h1>
        <p class="subtitle">Test coverage analysis for Phases 1-4</p>

        <div class="summary-card">
            <div class="aggregate-percentage {get_coverage_class(aggregate_percentage)}">{aggregate_percentage:.1f}%</div>
            <div class="aggregate-label">Aggregate Code Coverage</div>
            <span class="threshold-badge {get_threshold_class(aggregate_percentage)}">
                {"✓ Threshold Met (80%)" if threshold_met else "✗ Below Threshold (80%)"}
            </span>
            <div class="stats-row">
                <div class="stat-item">
                    <div class="stat-value">{total_covered:,}</div>
                    <div class="stat-label">Instructions Covered</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">{total_missed:,}</div>
                    <div class="stat-label">Instructions Missed</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">{total_line_covered:,}</div>
                    <div class="stat-label">Lines Covered</div>
                </div>
                <div class="stat-item">
                    <div class="stat-value">{len(coverages)}</div>
                    <div class="stat-label">Modules Analyzed</div>
                </div>
            </div>
        </div>

        <div class="module-grid">
'''

    # Add module cards
    for coverage in coverages:
        pct = coverage.instruction_percentage
        coverage_class = get_coverage_class(pct)
        threshold_class = get_threshold_class(pct)
        status = "✓ Pass" if coverage.passes_threshold else "✗ Fail"

        html += f'''
            <div class="module-card">
                <div class="module-header">
                    <span class="module-name">{coverage.module_name}</span>
                    <span class="module-percentage {threshold_class}">{pct:.1f}%</span>
                </div>
                <div class="coverage-bar">
                    <div class="coverage-fill {coverage_class}" style="width: {pct:.1f}%;"></div>
                </div>
                <div class="metrics-grid">
                    <div class="metric">
                        <div class="metric-label">Instructions</div>
                        <div class="metric-value">{coverage.instruction_covered:,} / {coverage.instruction_covered + coverage.instruction_missed:,}</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Branches</div>
                        <div class="metric-value">{coverage.branch_covered:,} / {coverage.branch_covered + coverage.branch_missed:,}</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Lines</div>
                        <div class="metric-value">{coverage.line_covered:,} / {coverage.line_covered + coverage.line_missed:,}</div>
                    </div>
                    <div class="metric">
                        <div class="metric-label">Status</div>
                        <div class="metric-value {threshold_class}">{status}</div>
                    </div>
                </div>
                <div class="links">
                    <a href="{coverage.module_name}/build/reports/jacoco/test/html/index.html">HTML Report</a>
                    <a href="{coverage.module_name}/build/reports/jacoco/test/jacocoTestReport.xml">XML Report</a>
                </div>
            </div>
'''

    html += '''
        </div>

        <div class="detail-section">
            <h2>Detailed Coverage Breakdown</h2>
            <table>
                <thead>
                    <tr>
                        <th>Module</th>
                        <th>Instruction Coverage</th>
                        <th>Branch Coverage</th>
                        <th>Line Coverage</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
'''

    # Add table rows
    for coverage in coverages:
        inst_pct = coverage.instruction_percentage
        branch_pct = coverage.branch_percentage
        line_pct = coverage.line_percentage
        status_class = get_threshold_class(inst_pct)
        status = "✓ PASS" if coverage.passes_threshold else "✗ FAIL"

        html += f'''
                    <tr>
                        <td><strong>{coverage.module_name}</strong></td>
                        <td>{inst_pct:.1f}% ({coverage.instruction_covered:,} / {coverage.instruction_covered + coverage.instruction_missed:,})</td>
                        <td>{branch_pct:.1f}% ({coverage.branch_covered:,} / {coverage.branch_covered + coverage.branch_missed:,})</td>
                        <td>{line_pct:.1f}% ({coverage.line_covered:,} / {coverage.line_covered + coverage.line_missed:,})</td>
                        <td class="{status_class}">{status}</td>
                    </tr>
'''

    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S %Z")

    html += f'''
                </tbody>
            </table>
        </div>

        <div class="footer">
            <p>Generated: {timestamp}</p>
            <p>Coverage threshold: 80% minimum per module |
               <a href="aggregate/html/index.html">Aggregate Report</a> |
               <a href="aggregate/coverage.xml">XML Report</a>
            </p>
        </div>
    </div>
</body>
</html>
'''

    # Ensure output directory exists
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Write the report
    output_path.write_text(html, encoding='utf-8')
    print(f"Coverage report generated: {output_path}")


def main():
    """Main entry point."""
    # Parse arguments
    project_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else project_root / "build/reports/jacoco/COVERAGE_SUMMARY.html"

    print("=" * 60)
    print("  ExcaliburDB Coverage Report Generator")
    print("=" * 60)
    print()

    # Parse coverage data from each module
    coverages = []
    for module in TARGET_MODULES:
        xml_path = project_root / module / "build/reports/jacoco/test/jacocoTestReport.xml"

        if xml_path.exists():
            coverage = parse_jacoco_xml(xml_path)
            coverages.append(coverage)
            print(f"  Parsed {module}: {coverage.instruction_percentage:.1f}% instruction coverage")
        else:
            # Create empty coverage data for missing reports
            coverages.append(CoverageData(module))
            print(f"  Warning: No coverage data found for {module}")

    print()

    # Generate the HTML report
    generate_html_report(coverages, output_path)

    # Print summary
    print()
    print("=" * 60)
    print("  Coverage Summary")
    print("=" * 60)

    for coverage in coverages:
        status = "✓ PASS" if coverage.passes_threshold else "✗ FAIL"
        print(f"  {coverage.module_name}: {coverage.instruction_percentage:.1f}% {status}")

    print()
    print(f"Report saved to: {output_path}")


if __name__ == "__main__":
    main()
