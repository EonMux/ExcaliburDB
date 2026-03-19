package io.excalibur.core.report;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Analyzes JaCoCo coverage reports to identify missing coverage areas.
 *
 * <p>This analyzer parses JaCoCo XML reports at granular levels to identify:</p>
 * <ul>
 *   <li>Packages with coverage below the threshold</li>
 *   <li>Classes with coverage below the threshold</li>
 *   <li>Methods with zero or low coverage</li>
 *   <li>Specific uncovered line ranges</li>
 * </ul>
 *
 * <p>Thread Safety: This class is stateless and thread-safe. All methods are static
 * and operate only on their input parameters.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * GapAnalysisResult result = CoverageGapAnalyzer.analyze(projectRoot);
 * result.gapsByModule().forEach((module, gaps) -> {
 *     System.out.println("Module: " + module);
 *     gaps.classesBelowThreshold().forEach(cls -> {
 *         System.out.println("  Low coverage class: " + cls.className());
 *     });
 * });
 * }</pre>
 */
public final class CoverageGapAnalyzer {

    private static final double COVERAGE_THRESHOLD = 0.80;
    private static final double LOW_COVERAGE_THRESHOLD = 0.50;
    private static final int MAX_GAPS_PER_MODULE = 50;

    private static final List<String> TARGET_MODULES = List.of(
        "excalibur-core",
        "excalibur-store",
        "excalibur-filter",
        "excalibur-vector"
    );

    private CoverageGapAnalyzer() {
        // Utility class - no instantiation
    }

    /**
     * Analyzes coverage gaps across all target modules.
     *
     * @param projectRoot the root directory of the project
     * @return a map of module names to their gap analysis results
     * @throws IOException if reading JaCoCo XML files fails
     */
    public static Map<String, ModuleGapAnalysis> analyze(Path projectRoot) throws IOException {
        Map<String, ModuleGapAnalysis> results = new LinkedHashMap<>();

        for (String module : TARGET_MODULES) {
            Path xmlReportPath = projectRoot.resolve(module)
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            if (Files.exists(xmlReportPath)) {
                ModuleGapAnalysis analysis = analyzeModule(xmlReportPath, module);
                results.put(module, analysis);
            } else {
                // Module has no coverage data - report as completely missing
                results.put(module, new ModuleGapAnalysis(
                    module,
                    List.of(new ClassGap(
                        module, "(no tests)", 0.0, 0.0, 0.0,
                        List.of(), List.of()
                    )),
                    List.of(),
                    List.of(),
                    0.0
                ));
            }
        }

        return results;
    }

    /**
     * Analyzes a single module's JaCoCo XML report.
     */
    static ModuleGapAnalysis analyzeModule(Path xmlPath, String moduleName) throws IOException {
        String content = Files.readString(xmlPath);

        List<ClassGap> classesBelowThreshold = new ArrayList<>();
        List<MethodGap> methodsWithZeroCoverage = new ArrayList<>();
        List<PackageGap> packagesBelowThreshold = new ArrayList<>();

        // Parse packages
        List<PackageInfo> packages = parsePackages(content);

        for (PackageInfo pkg : packages) {
            double pkgCoverage = calculatePercentage(
                pkg.instructionCovered, pkg.instructionMissed
            );

            if (pkgCoverage < COVERAGE_THRESHOLD * 100) {
                packagesBelowThreshold.add(new PackageGap(
                    pkg.name.replace('/', '.'),
                    pkgCoverage,
                    pkg.classes.size()
                ));
            }

            // Analyze classes in the package
            for (ClassInfo cls : pkg.classes) {
                double classCoverage = calculatePercentage(
                    cls.instructionCovered, cls.instructionMissed
                );
                double branchCoverage = calculatePercentage(
                    cls.branchCovered, cls.branchMissed
                );
                double lineCoverage = calculatePercentage(
                    cls.lineCovered, cls.lineMissed
                );

                // Find methods with zero coverage
                List<MethodGap> uncoveredMethods = cls.methods.stream()
                    .filter(m -> m.instructionCovered == 0 && m.instructionMissed > 0)
                    .map(m -> new MethodGap(
                        cls.name.replace('/', '.'),
                        m.name,
                        m.line,
                        (int) m.instructionMissed
                    ))
                    .collect(Collectors.toList());

                methodsWithZeroCoverage.addAll(uncoveredMethods);

                // Track classes below threshold
                if (classCoverage < COVERAGE_THRESHOLD * 100) {
                    List<LineGap> uncoveredLines = findUncoveredLines(cls);
                    List<MethodGap> lowCoverageMethods = cls.methods.stream()
                        .filter(m -> {
                            double methodCov = calculatePercentage(
                                m.instructionCovered, m.instructionMissed
                            );
                            return methodCov > 0 && methodCov < COVERAGE_THRESHOLD * 100;
                        })
                        .map(m -> new MethodGap(
                            cls.name.replace('/', '.'),
                            m.name,
                            m.line,
                            (int) m.instructionMissed
                        ))
                        .collect(Collectors.toList());

                    classesBelowThreshold.add(new ClassGap(
                        pkg.name.replace('/', '.'),
                        cls.name.substring(cls.name.lastIndexOf('/') + 1),
                        classCoverage,
                        branchCoverage,
                        lineCoverage,
                        uncoveredMethods.stream()
                            .filter(m -> m.className().equals(cls.name.replace('/', '.')))
                            .collect(Collectors.toList()),
                        uncoveredLines
                    ));
                }
            }
        }

        // Sort by coverage (lowest first) and limit results
        classesBelowThreshold.sort(Comparator.comparingDouble(ClassGap::instructionCoverage));
        if (classesBelowThreshold.size() > MAX_GAPS_PER_MODULE) {
            classesBelowThreshold = new ArrayList<>(
                classesBelowThreshold.subList(0, MAX_GAPS_PER_MODULE)
            );
        }

        methodsWithZeroCoverage.sort(Comparator.comparingInt(MethodGap::missedInstructions)
            .reversed());
        if (methodsWithZeroCoverage.size() > MAX_GAPS_PER_MODULE) {
            methodsWithZeroCoverage = new ArrayList<>(
                methodsWithZeroCoverage.subList(0, MAX_GAPS_PER_MODULE)
            );
        }

        // Calculate module-level coverage
        long totalCovered = packages.stream()
            .mapToLong(p -> p.instructionCovered)
            .sum();
        long totalMissed = packages.stream()
            .mapToLong(p -> p.instructionMissed)
            .sum();
        double moduleCoverage = calculatePercentage(totalCovered, totalMissed);

        return new ModuleGapAnalysis(
            moduleName,
            classesBelowThreshold,
            methodsWithZeroCoverage,
            packagesBelowThreshold,
            moduleCoverage
        );
    }

    /**
     * Parses all packages from the JaCoCo XML content.
     */
    private static List<PackageInfo> parsePackages(String xml) {
        List<PackageInfo> packages = new ArrayList<>();

        // Find all package elements
        Pattern packagePattern = Pattern.compile(
            "<package\\s+name=\"([^\"]+)\"[^>]*>(.*?)</package>",
            Pattern.DOTALL
        );
        Matcher pkgMatcher = packagePattern.matcher(xml);

        while (pkgMatcher.find()) {
            String pkgName = pkgMatcher.group(1);
            String pkgContent = pkgMatcher.group(2);

            List<ClassInfo> classes = parseClasses(pkgContent);

            // Get package-level counters
            Coverage pkgInstruction = extractCounter(pkgContent, "INSTRUCTION");
            Coverage pkgBranch = extractCounter(pkgContent, "BRANCH");
            Coverage pkgLine = extractCounter(pkgContent, "LINE");

            packages.add(new PackageInfo(
                pkgName,
                classes,
                pkgInstruction.covered(),
                pkgInstruction.missed(),
                pkgBranch.covered(),
                pkgBranch.missed(),
                pkgLine.covered(),
                pkgLine.missed()
            ));
        }

        return packages;
    }

    /**
     * Parses all classes from a package's XML content.
     */
    private static List<ClassInfo> parseClasses(String packageContent) {
        List<ClassInfo> classes = new ArrayList<>();

        Pattern classPattern = Pattern.compile(
            "<class\\s+name=\"([^\"]+)\"[^>]*>(.*?)</class>",
            Pattern.DOTALL
        );
        Matcher classMatcher = classPattern.matcher(packageContent);

        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            String classContent = classMatcher.group(2);

            List<MethodInfo> methods = parseMethods(classContent);

            Coverage clsInstruction = extractCounter(classContent, "INSTRUCTION");
            Coverage clsBranch = extractCounter(classContent, "BRANCH");
            Coverage clsLine = extractCounter(classContent, "LINE");

            classes.add(new ClassInfo(
                className,
                methods,
                clsInstruction.covered(),
                clsInstruction.missed(),
                clsBranch.covered(),
                clsBranch.missed(),
                clsLine.covered(),
                clsLine.missed()
            ));
        }

        return classes;
    }

    /**
     * Parses all methods from a class's XML content.
     */
    private static List<MethodInfo> parseMethods(String classContent) {
        List<MethodInfo> methods = new ArrayList<>();

        Pattern methodPattern = Pattern.compile(
            "<method\\s+name=\"([^\"]+)\"\\s+desc=\"([^\"]+)\"(?:\\s+line=\"(\\d+)\")?[^>]*>(.*?)</method>",
            Pattern.DOTALL
        );
        Matcher methodMatcher = methodPattern.matcher(classContent);

        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(1);
            String desc = methodMatcher.group(2);
            int line = methodMatcher.group(3) != null
                ? Integer.parseInt(methodMatcher.group(3))
                : -1;
            String methodContent = methodMatcher.group(4);

            Coverage methodInstruction = extractCounter(methodContent, "INSTRUCTION");
            Coverage methodLine = extractCounter(methodContent, "LINE");

            methods.add(new MethodInfo(
                methodName,
                desc,
                line,
                methodInstruction.covered(),
                methodInstruction.missed(),
                methodLine.covered(),
                methodLine.missed()
            ));
        }

        return methods;
    }

    /**
     * Finds uncovered line ranges in a class.
     */
    private static List<LineGap> findUncoveredLines(ClassInfo cls) {
        // This is a simplified version - full implementation would parse
        // sourcefile elements for detailed line information
        List<LineGap> gaps = new ArrayList<>();

        // Estimate based on missed line count
        if (cls.lineMissed > 0 && cls.lineCovered == 0) {
            gaps.add(new LineGap(1, (int) cls.lineMissed, "No coverage"));
        }

        return gaps;
    }

    /**
     * Extracts a specific counter type from XML content.
     */
    private static Coverage extractCounter(String content, String type) {
        // Look for counter at the end of the element (class/package level)
        Pattern pattern = Pattern.compile(
            "<counter\\s+type=\"" + Pattern.quote(type) + "\"\\s+" +
            "missed=\"(\\d+)\"\\s+covered=\"(\\d+)\"\\s*/>"
        );
        Matcher matcher = pattern.matcher(content);

        // Get the last matching counter (aggregated at current level)
        long missed = 0;
        long covered = 0;
        while (matcher.find()) {
            missed = Long.parseLong(matcher.group(1));
            covered = Long.parseLong(matcher.group(2));
        }

        return new Coverage(covered, missed);
    }

    /**
     * Calculates percentage from covered and missed counts.
     */
    private static double calculatePercentage(long covered, long missed) {
        long total = covered + missed;
        if (total == 0) return 100.0; // No code means fully covered
        return (covered * 100.0) / total;
    }

    /**
     * Generates a text summary of coverage gaps.
     */
    public static String generateTextSummary(Map<String, ModuleGapAnalysis> results) {
        StringBuilder sb = new StringBuilder();

        sb.append("=".repeat(70)).append("\n");
        sb.append("COVERAGE GAP ANALYSIS REPORT\n");
        sb.append("=".repeat(70)).append("\n\n");

        for (Map.Entry<String, ModuleGapAnalysis> entry : results.entrySet()) {
            String module = entry.getKey();
            ModuleGapAnalysis analysis = entry.getValue();

            sb.append(String.format("MODULE: %s (%.1f%% coverage)%n",
                module, analysis.moduleCoverage()));
            sb.append("-".repeat(50)).append("\n");

            // Packages below threshold
            if (!analysis.packagesBelowThreshold().isEmpty()) {
                sb.append(String.format("%n  Packages Below 80%% Threshold (%d):%n",
                    analysis.packagesBelowThreshold().size()));
                for (PackageGap pkg : analysis.packagesBelowThreshold()) {
                    sb.append(String.format("    - %s: %.1f%% (%d classes)%n",
                        pkg.packageName(), pkg.coverage(), pkg.classCount()));
                }
            }

            // Classes below threshold
            if (!analysis.classesBelowThreshold().isEmpty()) {
                sb.append(String.format("%n  Classes Below 80%% Threshold (%d):%n",
                    analysis.classesBelowThreshold().size()));
                for (ClassGap cls : analysis.classesBelowThreshold()) {
                    sb.append(String.format("    - %s.%s: %.1f%% (lines: %.1f%%, branches: %.1f%%)%n",
                        cls.packageName(), cls.className(),
                        cls.instructionCoverage(), cls.lineCoverage(), cls.branchCoverage()));

                    // Show uncovered methods
                    if (!cls.uncoveredMethods().isEmpty()) {
                        sb.append(String.format("      Uncovered methods (%d):%n",
                            cls.uncoveredMethods().size()));
                        for (MethodGap method : cls.uncoveredMethods()) {
                            sb.append(String.format("        - %s() [line %d, %d instructions missed]%n",
                                method.methodName(), method.lineNumber(), method.missedInstructions()));
                        }
                    }
                }
            }

            // Methods with zero coverage
            if (!analysis.methodsWithZeroCoverage().isEmpty()) {
                sb.append(String.format("%n  Methods With Zero Coverage (%d):%n",
                    analysis.methodsWithZeroCoverage().size()));
                for (MethodGap method : analysis.methodsWithZeroCoverage()) {
                    sb.append(String.format("    - %s.%s() [line %d]%n",
                        method.className(), method.methodName(), method.lineNumber()));
                }
            }

            sb.append("\n");
        }

        // Summary statistics
        sb.append("=".repeat(70)).append("\n");
        sb.append("SUMMARY\n");
        sb.append("=".repeat(70)).append("\n");

        int totalClassesBelowThreshold = results.values().stream()
            .mapToInt(a -> a.classesBelowThreshold().size())
            .sum();
        int totalMethodsWithZeroCoverage = results.values().stream()
            .mapToInt(a -> a.methodsWithZeroCoverage().size())
            .sum();
        int totalPackagesBelowThreshold = results.values().stream()
            .mapToInt(a -> a.packagesBelowThreshold().size())
            .sum();

        sb.append(String.format("Total packages below threshold: %d%n", totalPackagesBelowThreshold));
        sb.append(String.format("Total classes below threshold: %d%n", totalClassesBelowThreshold));
        sb.append(String.format("Total methods with zero coverage: %d%n", totalMethodsWithZeroCoverage));

        return sb.toString();
    }

    // Record types for parsed data

    private record Coverage(long covered, long missed) {}

    private record PackageInfo(
        String name,
        List<ClassInfo> classes,
        long instructionCovered,
        long instructionMissed,
        long branchCovered,
        long branchMissed,
        long lineCovered,
        long lineMissed
    ) {}

    private record ClassInfo(
        String name,
        List<MethodInfo> methods,
        long instructionCovered,
        long instructionMissed,
        long branchCovered,
        long branchMissed,
        long lineCovered,
        long lineMissed
    ) {}

    private record MethodInfo(
        String name,
        String descriptor,
        int line,
        long instructionCovered,
        long instructionMissed,
        long lineCovered,
        long lineMissed
    ) {}

    // Public record types for gap analysis results

    /**
     * Represents coverage gap analysis for a single module.
     *
     * @param moduleName the name of the module
     * @param classesBelowThreshold classes with coverage below 80%
     * @param methodsWithZeroCoverage methods that have no test coverage
     * @param packagesBelowThreshold packages with coverage below 80%
     * @param moduleCoverage the overall module coverage percentage
     */
    public record ModuleGapAnalysis(
        String moduleName,
        List<ClassGap> classesBelowThreshold,
        List<MethodGap> methodsWithZeroCoverage,
        List<PackageGap> packagesBelowThreshold,
        double moduleCoverage
    ) {}

    /**
     * Represents a package with coverage below threshold.
     *
     * @param packageName the fully qualified package name
     * @param coverage the package's coverage percentage
     * @param classCount the number of classes in the package
     */
    public record PackageGap(
        String packageName,
        double coverage,
        int classCount
    ) {}

    /**
     * Represents a class with coverage gaps.
     *
     * @param packageName the package containing the class
     * @param className the simple class name
     * @param instructionCoverage the instruction coverage percentage
     * @param branchCoverage the branch coverage percentage
     * @param lineCoverage the line coverage percentage
     * @param uncoveredMethods list of methods with zero coverage
     * @param uncoveredLines list of line ranges without coverage
     */
    public record ClassGap(
        String packageName,
        String className,
        double instructionCoverage,
        double branchCoverage,
        double lineCoverage,
        List<MethodGap> uncoveredMethods,
        List<LineGap> uncoveredLines
    ) {}

    /**
     * Represents a method with coverage gaps.
     *
     * @param className the fully qualified class name
     * @param methodName the method name
     * @param lineNumber the starting line number (or -1 if unknown)
     * @param missedInstructions the number of missed instructions
     */
    public record MethodGap(
        String className,
        String methodName,
        int lineNumber,
        int missedInstructions
    ) {}

    /**
     * Represents a range of lines without coverage.
     *
     * @param startLine the starting line number
     * @param endLine the ending line number
     * @param reason description of why this gap exists
     */
    public record LineGap(
        int startLine,
        int endLine,
        String reason
    ) {}

    /**
     * Main entry point for command-line usage.
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CoverageGapAnalyzer <project-root>");
            System.exit(1);
        }

        try {
            Path projectRoot = Paths.get(args[0]);
            Map<String, ModuleGapAnalysis> results = analyze(projectRoot);
            System.out.println(generateTextSummary(results));
        } catch (IOException e) {
            System.err.println("Failed to analyze coverage gaps: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
