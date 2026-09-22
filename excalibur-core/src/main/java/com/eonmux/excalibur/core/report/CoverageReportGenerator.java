package com.eonmux.excalibur.core.report;

import com.eonmux.excalibur.core.report.CoverageGapAnalyzer.ClassGap;
import com.eonmux.excalibur.core.report.CoverageGapAnalyzer.MethodGap;
import com.eonmux.excalibur.core.report.CoverageGapAnalyzer.ModuleGapAnalysis;
import com.eonmux.excalibur.core.report.CoverageGapAnalyzer.PackageGap;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;

/**
 * Generates consolidated HTML coverage reports from JaCoCo XML output.
 *
 * <p>Thread Safety: This class is stateless and thread-safe. All methods are static
 * and operate only on their input parameters.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * CoverageReportGenerator.generate(
 *     Paths.get("/path/to/project"),
 *     Paths.get("/path/to/output/report.html")
 * );
 * }</pre>
 */
public final class CoverageReportGenerator {

    private static final double COVERAGE_THRESHOLD = 0.80;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    /** Where a module leaves the report this reads, relative to the module directory. */
    private static final String REPORT_PATH = "build/reports/jacoco/test/jacocoTestReport.xml";

    private CoverageReportGenerator() {
        // Utility class - no instantiation
    }

    /**
     * Generates a consolidated HTML coverage report from JaCoCo XML reports.
     *
     * @param projectRoot the root directory of the project
     * @param outputPath the path where the HTML report will be written
     * @throws IOException if reading XML or writing HTML fails
     */
    public static void generate(Path projectRoot, Path outputPath) throws IOException {
        generate(projectRoot, outputPath, modulesUnder(projectRoot));
    }

    /**
     * Generates the report over the modules named, rather than the ones discovered.
     *
     * <p>The build passes its own list, which is authoritative: it is the set of subprojects the
     * coverage gates actually ran. Discovery is for a hand invocation, where there is no build to
     * ask. Both beat a list compiled into this file, which is what this used to carry - it named
     * four of the twenty-one modules, so the summary described a fifth of the repository while
     * reporting itself complete.</p>
     *
     * @param projectRoot the root directory of the project
     * @param outputPath the path where the HTML report will be written
     * @param modules the module directory names to include, in report order
     * @throws IOException if reading XML or writing HTML fails
     */
    public static void generate(Path projectRoot, Path outputPath, List<String> modules)
            throws IOException {
        List<ModuleCoverage> coverages = new ArrayList<>();

        for (String module : modules) {
            Path xmlReportPath = projectRoot.resolve(module).resolve(REPORT_PATH);

            if (Files.exists(xmlReportPath)) {
                ModuleCoverage coverage = parseJaCoCoXml(xmlReportPath, module);
                coverages.add(coverage);
            } else {
                coverages.add(new ModuleCoverage(module, 0, 0, 0, 0, 0, 0));
            }
        }

        // Perform gap analysis to identify missing coverage areas
        Map<String, CoverageGapAnalyzer.ModuleGapAnalysis> gapAnalysis =
            CoverageGapAnalyzer.analyze(projectRoot);

        String html = generateHtml(coverages, gapAnalysis);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, html);
    }

    /**
     * The modules under {@code projectRoot} that carry a JaCoCo report.
     *
     * <p>A directory is a module here because it holds a report, not because it matches a name:
     * a module whose tests have not run yet is simply absent from the summary, which is honest,
     * where a hardcoded name would have reported it as zero and indistinguishable from a module
     * with no covered lines.</p>
     *
     * @param projectRoot the root directory of the project
     * @return the module directory names, sorted
     * @throws IOException if the root cannot be listed
     */
    static List<String> modulesUnder(Path projectRoot) throws IOException {
        try (var entries = Files.list(projectRoot)) {
            return entries
                .filter(Files::isDirectory)
                .filter(dir -> Files.exists(dir.resolve(REPORT_PATH)))
                .map(dir -> dir.getFileName().toString())
                .sorted()
                .toList();
        }
    }

    /**
     * Parses a JaCoCo XML report and extracts coverage metrics.
     */
    static ModuleCoverage parseJaCoCoXml(Path xmlPath, String moduleName) throws IOException {
        String content = Files.readString(xmlPath);

        // Extract INSTRUCTION coverage (most comprehensive metric)
        Coverage instructionCoverage = extractCoverage(content, "INSTRUCTION");
        Coverage branchCoverage = extractCoverage(content, "BRANCH");
        Coverage lineCoverage = extractCoverage(content, "LINE");
        Coverage methodCoverage = extractCoverage(content, "METHOD");
        Coverage classCoverage = extractCoverage(content, "CLASS");

        return new ModuleCoverage(
            moduleName,
            instructionCoverage.covered(),
            instructionCoverage.missed(),
            branchCoverage.covered(),
            branchCoverage.missed(),
            lineCoverage.covered(),
            lineCoverage.missed()
        );
    }

    /**
     * Extracts coverage data for a specific counter type from JaCoCo XML.
     *
     * <p>JaCoCo XML contains counter elements at multiple levels (method, class,
     * package, report). The aggregate coverage is in the LAST counter element
     * of each type, which appears at the end of the file within the root
     * {@code <report>} element.</p>
     *
     * <p>This method finds all matching counters and returns the last one,
     * which represents the aggregate coverage for the entire module.</p>
     *
     * @param xml the JaCoCo XML content
     * @param type the counter type (INSTRUCTION, BRANCH, LINE, METHOD, CLASS)
     * @return the aggregate coverage for the module
     */
    private static Coverage extractCoverage(String xml, String type) {
        // Pattern matches: <counter type="INSTRUCTION" missed="100" covered="500"/>
        Pattern pattern = Pattern.compile(
            "<counter\\s+type=\"" + Pattern.quote(type) + "\"\\s+" +
            "missed=\"(\\d+)\"\\s+covered=\"(\\d+)\""
        );
        Matcher matcher = pattern.matcher(xml);

        // Find ALL matches and take the LAST one (the aggregate counter)
        long lastMissed = 0;
        long lastCovered = 0;
        boolean found = false;

        while (matcher.find()) {
            found = true;
            lastMissed = Long.parseLong(matcher.group(1));
            lastCovered = Long.parseLong(matcher.group(2));
        }

        return found ? new Coverage(lastCovered, lastMissed) : new Coverage(0, 0);
    }

    /**
     * Generates the complete HTML report.
     */
    private static String generateHtml(List<ModuleCoverage> coverages,
            Map<String, ModuleGapAnalysis> gapAnalysis) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>ExcaliburDB Coverage Report</title>\n");
        html.append(generateStyles());
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append(generateHeader());
        html.append(generateSummaryCard(coverages));
        html.append(generateModuleCards(coverages));
        html.append(generateDetailedTable(coverages));
        html.append(generateGapAnalysisSection(gapAnalysis));
        html.append(generateFooter());
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>\n");
        return html.toString();
    }

    /**
     * Generates CSS styles for the report.
     */
    private static String generateStyles() {
        return """
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }

                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                    background: linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%);
                    min-height: 100vh;
                    padding: 40px 20px;
                    color: #e0e0e0;
                    line-height: 1.6;
                }

                .container {
                    max-width: 1400px;
                    margin: 0 auto;
                }

                h1 {
                    text-align: center;
                    margin-bottom: 12px;
                    font-size: 2.5rem;
                    font-weight: 700;
                    background: linear-gradient(90deg, #00d4ff, #7b2cbf);
                    -webkit-background-clip: text;
                    -webkit-text-fill-color: transparent;
                    background-clip: text;
                }

                .subtitle {
                    text-align: center;
                    color: #888;
                    margin-bottom: 40px;
                    font-size: 1rem;
                }

                .summary-card {
                    background: rgba(255,255,255,0.05);
                    border-radius: 20px;
                    padding: 40px;
                    border: 1px solid rgba(255,255,255,0.1);
                    text-align: center;
                    margin-bottom: 40px;
                    backdrop-filter: blur(10px);
                }

                .aggregate-percentage {
                    font-size: 5rem;
                    font-weight: 700;
                    margin-bottom: 16px;
                }

                .aggregate-label {
                    font-size: 1.1rem;
                    color: #888;
                    margin-bottom: 24px;
                }

                .threshold-badge {
                    display: inline-block;
                    padding: 8px 24px;
                    border-radius: 24px;
                    font-weight: 600;
                    font-size: 0.9rem;
                }

                .threshold-met {
                    background: rgba(0, 200, 83, 0.2);
                    color: #00c853;
                    border: 1px solid rgba(0, 200, 83, 0.3);
                }

                .threshold-not-met {
                    background: rgba(255, 82, 82, 0.2);
                    color: #ff5252;
                    border: 1px solid rgba(255, 82, 82, 0.3);
                }

                .stats-row {
                    display: flex;
                    justify-content: center;
                    gap: 40px;
                    margin-top: 32px;
                    flex-wrap: wrap;
                }

                .stat-item {
                    text-align: center;
                }

                .stat-value {
                    font-size: 1.8rem;
                    font-weight: 600;
                    color: #00d4ff;
                }

                .stat-label {
                    font-size: 0.85rem;
                    color: #888;
                    margin-top: 4px;
                }

                .module-grid {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
                    gap: 24px;
                    margin-bottom: 40px;
                }

                .module-card {
                    background: rgba(255,255,255,0.05);
                    border-radius: 16px;
                    padding: 28px;
                    border: 1px solid rgba(255,255,255,0.1);
                    transition: transform 0.3s ease, box-shadow 0.3s ease;
                }

                .module-card:hover {
                    transform: translateY(-4px);
                    box-shadow: 0 12px 40px rgba(0,212,255,0.15);
                }

                .module-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin-bottom: 20px;
                }

                .module-name {
                    font-size: 1.3rem;
                    font-weight: 600;
                    color: #fff;
                }

                .module-percentage {
                    font-size: 1.5rem;
                    font-weight: 700;
                }

                .coverage-bar {
                    height: 10px;
                    background: rgba(255,255,255,0.1);
                    border-radius: 5px;
                    overflow: hidden;
                    margin-bottom: 16px;
                }

                .coverage-fill {
                    height: 100%;
                    border-radius: 5px;
                    transition: width 0.8s ease;
                }

                .coverage-excellent { background: linear-gradient(90deg, #00c853, #69f0ae); }
                .coverage-good { background: linear-gradient(90deg, #64dd17, #b2ff59); }
                .coverage-warning { background: linear-gradient(90deg, #ffc107, #ffeb3b); }
                .coverage-danger { background: linear-gradient(90deg, #ff5252, #ff8a80); }

                .metrics-grid {
                    display: grid;
                    grid-template-columns: 1fr 1fr;
                    gap: 12px;
                }

                .metric {
                    background: rgba(0,0,0,0.2);
                    padding: 12px;
                    border-radius: 8px;
                }

                .metric-label {
                    font-size: 0.75rem;
                    color: #888;
                    text-transform: uppercase;
                    letter-spacing: 0.5px;
                }

                .metric-value {
                    font-size: 1rem;
                    font-weight: 600;
                    margin-top: 4px;
                }

                .links {
                    margin-top: 20px;
                    display: flex;
                    gap: 12px;
                }

                .links a {
                    color: #7b2cbf;
                    text-decoration: none;
                    font-size: 0.85rem;
                    padding: 8px 16px;
                    background: rgba(123,44,191,0.2);
                    border-radius: 8px;
                    transition: background 0.2s ease;
                    flex: 1;
                    text-align: center;
                }

                .links a:hover { background: rgba(123,44,191,0.4); }

                .detail-section {
                    background: rgba(255,255,255,0.05);
                    border-radius: 16px;
                    padding: 32px;
                    border: 1px solid rgba(255,255,255,0.1);
                    margin-bottom: 40px;
                }

                .detail-section h2 {
                    color: #00d4ff;
                    margin-bottom: 24px;
                    font-size: 1.3rem;
                }

                table {
                    width: 100%;
                    border-collapse: collapse;
                }

                th, td {
                    padding: 16px;
                    text-align: left;
                    border-bottom: 1px solid rgba(255,255,255,0.1);
                }

                th {
                    color: #888;
                    font-weight: 600;
                    font-size: 0.85rem;
                    text-transform: uppercase;
                    letter-spacing: 0.5px;
                }

                td {
                    font-size: 0.95rem;
                }

                tr:hover td {
                    background: rgba(255,255,255,0.03);
                }

                .footer {
                    text-align: center;
                    color: #666;
                    font-size: 0.85rem;
                    padding: 20px;
                }

                .footer a {
                    color: #00d4ff;
                    text-decoration: none;
                }

                .gap-section {
                    background: rgba(255,255,255,0.05);
                    border-radius: 16px;
                    padding: 32px;
                    border: 1px solid rgba(255,255,255,0.1);
                    margin-bottom: 40px;
                }

                .gap-section h2 {
                    color: #ff6b6b;
                    margin-bottom: 24px;
                    font-size: 1.3rem;
                    display: flex;
                    align-items: center;
                    gap: 10px;
                }

                .gap-section h2::before {
                    content: "⚠";
                }

                .gap-module {
                    margin-bottom: 24px;
                    padding: 20px;
                    background: rgba(0,0,0,0.2);
                    border-radius: 12px;
                    border-left: 4px solid #ff6b6b;
                }

                .gap-module h3 {
                    color: #ff8a80;
                    margin-bottom: 16px;
                    font-size: 1.1rem;
                }

                .gap-list {
                    list-style: none;
                    padding: 0;
                    margin: 0;
                }

                .gap-list li {
                    padding: 8px 12px;
                    margin-bottom: 4px;
                    background: rgba(255,255,255,0.03);
                    border-radius: 6px;
                    font-size: 0.9rem;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                }

                .gap-list li:hover {
                    background: rgba(255,255,255,0.06);
                }

                .gap-name {
                    color: #e0e0e0;
                    font-family: 'Fira Code', 'Consolas', monospace;
                }

                .gap-coverage {
                    font-weight: 600;
                    padding: 2px 8px;
                    border-radius: 4px;
                    font-size: 0.8rem;
                }

                .gap-coverage.critical {
                    background: rgba(255,82,82,0.3);
                    color: #ff5252;
                }

                .gap-coverage.warning {
                    background: rgba(255,193,7,0.3);
                    color: #ffc107;
                }

                .gap-coverage.moderate {
                    background: rgba(255,152,0,0.3);
                    color: #ff9800;
                }

                .method-list {
                    margin-top: 12px;
                    padding-left: 20px;
                }

                .method-list li {
                    font-size: 0.85rem;
                    padding: 4px 8px;
                }

                .no-gaps {
                    text-align: center;
                    padding: 40px;
                    color: #00c853;
                    font-size: 1.1rem;
                }

                .no-gaps::before {
                    content: "✓ ";
                }

                .gap-summary {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                    gap: 16px;
                    margin-bottom: 24px;
                }

                .gap-summary-item {
                    background: rgba(0,0,0,0.3);
                    padding: 16px;
                    border-radius: 8px;
                    text-align: center;
                }

                .gap-summary-value {
                    font-size: 2rem;
                    font-weight: 700;
                    color: #ff6b6b;
                }

                .gap-summary-label {
                    font-size: 0.8rem;
                    color: #888;
                    margin-top: 4px;
                }

                @media (max-width: 768px) {
                    h1 { font-size: 1.8rem; }
                    .aggregate-percentage { font-size: 3.5rem; }
                    .module-grid { grid-template-columns: 1fr; }
                    .stats-row { gap: 20px; }
                }
            </style>
        """;
    }

    /**
     * Generates the header section.
     */
    private static String generateHeader() {
        return String.format("""
            <h1>ExcaliburDB Coverage Report</h1>
            <p class="subtitle">Test coverage analysis for Phases 1-4</p>
        """);
    }

    /**
     * Generates the summary card with aggregate coverage.
     */
    private static String generateSummaryCard(List<ModuleCoverage> coverages) {
        long totalCovered = coverages.stream()
            .mapToLong(c -> c.instructionCovered + c.branchCovered)
            .sum();
        long totalMissed = coverages.stream()
            .mapToLong(c -> c.instructionMissed + c.branchMissed)
            .sum();
        double aggregatePercentage = calculatePercentage(totalCovered, totalMissed);

        String percentageClass = getCoverageClass(aggregatePercentage);
        String thresholdClass = aggregatePercentage >= COVERAGE_THRESHOLD * 100
            ? "threshold-met" : "threshold-not-met";
        String thresholdText = aggregatePercentage >= COVERAGE_THRESHOLD * 100
            ? "✓ Threshold Met (80%)" : "✗ Below Threshold (80%)";

        long totalLinesCovered = coverages.stream().mapToLong(c -> c.lineCovered).sum();
        long totalLinesMissed = coverages.stream().mapToLong(c -> c.lineMissed).sum();

        return String.format("""
            <div class="summary-card">
                <div class="aggregate-percentage %s">%.1f%%</div>
                <div class="aggregate-label">Aggregate Code Coverage</div>
                <span class="threshold-badge %s">%s</span>
                <div class="stats-row">
                    <div class="stat-item">
                        <div class="stat-value">%,d</div>
                        <div class="stat-label">Instructions Covered</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value">%,d</div>
                        <div class="stat-label">Instructions Missed</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value">%,d</div>
                        <div class="stat-label">Lines Covered</div>
                    </div>
                    <div class="stat-item">
                        <div class="stat-value">%d</div>
                        <div class="stat-label">Modules Analyzed</div>
                    </div>
                </div>
            </div>
        """, percentageClass, aggregatePercentage, thresholdClass, thresholdText,
            totalCovered, totalMissed, totalLinesCovered, coverages.size());
    }

    /**
     * Generates module cards.
     */
    private static String generateModuleCards(List<ModuleCoverage> coverages) {
        StringBuilder cards = new StringBuilder();
        cards.append("<div class=\"module-grid\">\n");

        for (ModuleCoverage coverage : coverages) {
            double percentage = calculatePercentage(
                coverage.instructionCovered + coverage.branchCovered,
                coverage.instructionMissed + coverage.branchMissed
            );
            String coverageClass = getCoverageClass(percentage);
            String percentageColorClass = getPercentageColorClass(percentage);

            cards.append(String.format("""
                <div class="module-card">
                    <div class="module-header">
                        <span class="module-name">%s</span>
                        <span class="module-percentage %s">%.1f%%</span>
                    </div>
                    <div class="coverage-bar">
                        <div class="coverage-fill %s" style="width: %.1f%%;"></div>
                    </div>
                    <div class="metrics-grid">
                        <div class="metric">
                            <div class="metric-label">Instructions</div>
                            <div class="metric-value">%,d / %,d</div>
                        </div>
                        <div class="metric">
                            <div class="metric-label">Branches</div>
                            <div class="metric-value">%,d / %,d</div>
                        </div>
                        <div class="metric">
                            <div class="metric-label">Lines</div>
                            <div class="metric-value">%,d / %,d</div>
                        </div>
                        <div class="metric">
                            <div class="metric-label">Status</div>
                            <div class="metric-value">%s</div>
                        </div>
                    </div>
                    <div class="links">
                        <a href="%s/build/reports/jacoco/test/html/index.html">HTML Report</a>
                        <a href="%s/build/reports/jacoco/test/jacocoTestReport.xml">XML Report</a>
                    </div>
                </div>
            """,
                coverage.moduleName,
                percentageColorClass, percentage,
                coverageClass, percentage,
                coverage.instructionCovered,
                coverage.instructionCovered + coverage.instructionMissed,
                coverage.branchCovered,
                coverage.branchCovered + coverage.branchMissed,
                coverage.lineCovered,
                coverage.lineCovered + coverage.lineMissed,
                percentage >= COVERAGE_THRESHOLD * 100 ? "✓ Pass" : "✗ Fail",
                coverage.moduleName,
                coverage.moduleName
            ));
        }

        cards.append("</div>\n");
        return cards.toString();
    }

    /**
     * Generates the detailed coverage table.
     */
    private static String generateDetailedTable(List<ModuleCoverage> coverages) {
        StringBuilder table = new StringBuilder();
        table.append("""
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
        """);

        for (ModuleCoverage coverage : coverages) {
            double instructionPct = calculatePercentage(
                coverage.instructionCovered, coverage.instructionMissed);
            double branchPct = calculatePercentage(
                coverage.branchCovered, coverage.branchMissed);
            double linePct = calculatePercentage(
                coverage.lineCovered, coverage.lineMissed);
            boolean passesThreshold = instructionPct >= COVERAGE_THRESHOLD * 100;

            table.append(String.format("""
                <tr>
                    <td><strong>%s</strong></td>
                    <td>%.1f%% (%,d / %,d)</td>
                    <td>%.1f%% (%,d / %,d)</td>
                    <td>%.1f%% (%,d / %,d)</td>
                    <td class="%s">%s</td>
                </tr>
            """,
                coverage.moduleName,
                instructionPct, coverage.instructionCovered,
                    coverage.instructionCovered + coverage.instructionMissed,
                branchPct, coverage.branchCovered,
                    coverage.branchCovered + coverage.branchMissed,
                linePct, coverage.lineCovered,
                    coverage.lineCovered + coverage.lineMissed,
                passesThreshold ? "threshold-met" : "threshold-not-met",
                passesThreshold ? "✓ PASS" : "✗ FAIL"
            ));
        }

        table.append("""
                    </tbody>
                </table>
            </div>
        """);
        return table.toString();
    }

    /**
     * Generates the gap analysis section identifying missing coverage areas.
     */
    private static String generateGapAnalysisSection(
            Map<String, ModuleGapAnalysis> gapAnalysis) {

        StringBuilder section = new StringBuilder();
        section.append("""
            <div class="gap-section">
                <h2>Missing Coverage Areas</h2>
        """);

        // Calculate summary statistics
        int totalClassesBelowThreshold = gapAnalysis.values().stream()
            .mapToInt(a -> a.classesBelowThreshold().size())
            .sum();
        int totalMethodsWithZeroCoverage = gapAnalysis.values().stream()
            .mapToInt(a -> a.methodsWithZeroCoverage().size())
            .sum();
        int totalPackagesBelowThreshold = gapAnalysis.values().stream()
            .mapToInt(a -> a.packagesBelowThreshold().size())
            .sum();

        // Summary cards
        section.append("""
            <div class="gap-summary">
        """);
        section.append(String.format("""
                <div class="gap-summary-item">
                    <div class="gap-summary-value">%d</div>
                    <div class="gap-summary-label">Packages Below 80%%</div>
                </div>
        """, totalPackagesBelowThreshold));
        section.append(String.format("""
                <div class="gap-summary-item">
                    <div class="gap-summary-value">%d</div>
                    <div class="gap-summary-label">Classes Below 80%%</div>
                </div>
        """, totalClassesBelowThreshold));
        section.append(String.format("""
                <div class="gap-summary-item">
                    <div class="gap-summary-value">%d</div>
                    <div class="gap-summary-label">Uncovered Methods</div>
                </div>
        """, totalMethodsWithZeroCoverage));
        section.append("""
            </div>
        """);

        if (totalClassesBelowThreshold == 0 && totalMethodsWithZeroCoverage == 0) {
            section.append("""
                <div class="no-gaps">All modules meet the 80% coverage threshold</div>
            </div>
            """);
            return section.toString();
        }

        // Detailed breakdown by module
        for (Map.Entry<String, ModuleGapAnalysis> entry : gapAnalysis.entrySet()) {
            String moduleName = entry.getKey();
            ModuleGapAnalysis analysis = entry.getValue();

            boolean hasGaps = !analysis.classesBelowThreshold().isEmpty()
                || !analysis.methodsWithZeroCoverage().isEmpty();

            if (!hasGaps) {
                continue;
            }

            section.append(String.format("""
                <div class="gap-module">
                    <h3>%s (%.1f%% coverage)</h3>
            """, moduleName, analysis.moduleCoverage()));

            // Classes below threshold
            if (!analysis.classesBelowThreshold().isEmpty()) {
                section.append(String.format("""
                    <p style="color: #888; margin-bottom: 12px;">
                        Classes below 80%% threshold (%d):
                    </p>
                    <ul class="gap-list">
                """, analysis.classesBelowThreshold().size()));

                for (ClassGap cls : analysis.classesBelowThreshold()) {
                    String coverageClass = getGapCoverageClass(cls.instructionCoverage());
                    section.append(String.format("""
                        <li>
                            <span class="gap-name">%s.%s</span>
                            <span class="gap-coverage %s">%.1f%%</span>
                        </li>
                    """, cls.packageName(), cls.className(), coverageClass, cls.instructionCoverage()));

                    // Show uncovered methods if any
                    if (!cls.uncoveredMethods().isEmpty()) {
                        section.append("""
                            <li style="flex-direction: column; align-items: flex-start;">
                                <span style="color: #888; font-size: 0.8rem;">Uncovered methods:</span>
                                <ul class="method-list gap-list">
                        """);
                        for (MethodGap method : cls.uncoveredMethods()) {
                            section.append(String.format("""
                                    <li>
                                        <span class="gap-name">%s()</span>
                                        <span style="color: #666;">line %d</span>
                                    </li>
                            """, method.methodName(), method.lineNumber()));
                        }
                        section.append("""
                                </ul>
                            </li>
                        """);
                    }
                }
                section.append("""
                    </ul>
                """);
            }

            // Methods with zero coverage (standalone, not in classes already listed)
            List<MethodGap> standaloneUncovered = analysis.methodsWithZeroCoverage().stream()
                .filter(m -> analysis.classesBelowThreshold().stream()
                    .noneMatch(c -> (c.packageName() + "." + c.className()).equals(m.className())))
                .collect(java.util.stream.Collectors.toList());

            if (!standaloneUncovered.isEmpty()) {
                section.append(String.format("""
                    <p style="color: #888; margin-bottom: 12px; margin-top: 16px;">
                        Additional methods with zero coverage (%d):
                    </p>
                    <ul class="gap-list">
                """, standaloneUncovered.size()));

                for (MethodGap method : standaloneUncovered) {
                    section.append(String.format("""
                        <li>
                            <span class="gap-name">%s.%s()</span>
                            <span style="color: #666;">line %d</span>
                        </li>
                    """, method.className(), method.methodName(), method.lineNumber()));
                }
                section.append("""
                    </ul>
                """);
            }

            section.append("""
                </div>
            """);
        }

        section.append("""
            </div>
        """);

        return section.toString();
    }

    /**
     * Returns the CSS class for gap coverage display.
     */
    private static String getGapCoverageClass(double percentage) {
        if (percentage < 30) return "critical";
        if (percentage < 50) return "warning";
        return "moderate";
    }

    /**
     * Generates the footer.
     */
    private static String generateFooter() {
        String timestamp = ZonedDateTime.now().format(TIMESTAMP_FORMAT);
        return String.format("""
            <div class="footer">
                <p>Generated: %s</p>
                <p>Coverage threshold: 80%% minimum per module |
                   <a href="aggregate/html/index.html">Aggregate Report</a> |
                   <a href="aggregate/coverage.xml">XML Report</a>
                </p>
            </div>
        """, timestamp);
    }

    /**
     * Calculates percentage from covered and missed counts.
     *
     * <p>Delegates to {@link CoverageValidator#calculatePercentage(long, long)}
     * to ensure consistent behavior across all coverage calculations.</p>
     */
    private static double calculatePercentage(long covered, long missed) {
        return CoverageValidator.calculatePercentage(covered, missed);
    }

    /**
     * Returns the CSS class for coverage visualization based on percentage.
     */
    private static String getCoverageClass(double percentage) {
        if (percentage >= 90) return "coverage-excellent";
        if (percentage >= 80) return "coverage-good";
        if (percentage >= 60) return "coverage-warning";
        return "coverage-danger";
    }

    /**
     * Returns the CSS class for percentage text color.
     */
    private static String getPercentageColorClass(double percentage) {
        if (percentage >= 80) return "threshold-met";
        return "threshold-not-met";
    }

    // Record types for coverage data

    private record Coverage(long covered, long missed) {}

    /**
     * Coverage data for a single module.
     */
    public record ModuleCoverage(
        String moduleName,
        long instructionCovered,
        long instructionMissed,
        long branchCovered,
        long branchMissed,
        long lineCovered,
        long lineMissed
    ) {
        /**
         * Returns the instruction coverage percentage.
         */
        public double getInstructionPercentage() {
            long total = instructionCovered + instructionMissed;
            return total == 0 ? 0.0 : (instructionCovered * 100.0) / total;
        }

        /**
         * Returns the branch coverage percentage.
         */
        public double getBranchPercentage() {
            long total = branchCovered + branchMissed;
            return total == 0 ? 0.0 : (branchCovered * 100.0) / total;
        }

        /**
         * Returns the line coverage percentage.
         */
        public double getLinePercentage() {
            long total = lineCovered + lineMissed;
            return total == 0 ? 0.0 : (lineCovered * 100.0) / total;
        }
    }

    /**
     * Main entry point for command-line usage.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println(
                "Usage: CoverageReportGenerator <project-root> <output-path> [module...]");
            System.exit(1);
        }

        try {
            Path projectRoot = Paths.get(args[0]);
            Path outputPath = Paths.get(args[1]);
            List<String> modules = List.of(args).subList(2, args.length);
            if (modules.isEmpty()) {
                generate(projectRoot, outputPath);
            } else {
                generate(projectRoot, outputPath, modules);
            }
            System.out.println("Coverage report generated: " + outputPath);
        } catch (IOException e) {
            System.err.println("Failed to generate coverage report: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
