package com.eonmux.excalibur.coverage;

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

    /** Where a module leaves the report this reads, relative to the module directory. */
    private static final String REPORT_PATH = "build/reports/jacoco/test/jacocoTestReport.xml";

    /** Why a {@link LineGap} is reported: no instruction on its lines ever ran. */
    static final String UNEXECUTED = "not executed";

    /** The most unexecuted ranges the text summary prints for one class. */
    private static final int MAX_RANGES_PRINTED = 10;

    private static final Pattern SOURCE_FILE_ATTRIBUTE =
        Pattern.compile("sourcefilename=\"([^\"]+)\"");

    private static final Pattern SOURCE_FILE_ELEMENT = Pattern.compile(
        "<sourcefile\\s+name=\"([^\"]+)\"[^>]*>(.*?)</sourcefile>", Pattern.DOTALL);

    private static final Pattern LINE_ELEMENT = Pattern.compile(
        "<line\\s+nr=\"(\\d+)\"\\s+mi=\"(\\d+)\"\\s+ci=\"(\\d+)\"[^>]*/>");

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
        return analyze(projectRoot, modulesUnder(projectRoot));
    }

    /**
     * Analyzes coverage gaps across the modules named.
     *
     * <p>The caller's list wins where there is one, because the build knows which subprojects its
     * gates ran; discovery is the answer for a hand invocation. Neither can go stale the way a
     * list compiled into the analyzer would as modules are added.</p>
     *
     * @param projectRoot the root directory of the project
     * @param modules the module directory names to analyze
     * @return a map of module names to their gap analysis results
     * @throws IOException if reading JaCoCo XML files fails
     */
    public static Map<String, ModuleGapAnalysis> analyze(Path projectRoot, List<String> modules)
            throws IOException {
        Map<String, ModuleGapAnalysis> results = new LinkedHashMap<>();

        for (String module : modules) {
            Path xmlReportPath = projectRoot.resolve(module).resolve(REPORT_PATH);

            if (Files.exists(xmlReportPath)) {
                ModuleGapAnalysis analysis = analyzeModule(xmlReportPath, module);
                results.put(module, analysis);
            } else {
                // Module has no coverage data - report as completely missing
                results.put(module, new ModuleGapAnalysis(
                    module,
                    List.of(new ClassGap(
                        module, "(no tests)", 0.0, 0.0, 0.0,
                        List.of(), null, List.of()
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
            Map<String, List<LineGap>> unexecutedBySourceFile = new HashMap<>();
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
                    List<LineGap> uncoveredLines = cls.sourceFile() == null
                        ? List.of()
                        : unexecutedBySourceFile.computeIfAbsent(cls.sourceFile(),
                            file -> unexecutedRanges(pkg.sourceLines().getOrDefault(file, List.of())));
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
                        cls.sourceFile(),
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
            Map<String, List<SourceLine>> sourceLines = parseSourceFiles(pkgContent);

            // Get package-level counters
            Coverage pkgInstruction = extractCounter(pkgContent, "INSTRUCTION");
            Coverage pkgBranch = extractCounter(pkgContent, "BRANCH");
            Coverage pkgLine = extractCounter(pkgContent, "LINE");

            packages.add(new PackageInfo(
                pkgName,
                classes,
                sourceLines,
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
            "<class\\s+name=\"([^\"]+)\"([^>]*)>(.*?)</class>",
            Pattern.DOTALL
        );
        Matcher classMatcher = classPattern.matcher(packageContent);

        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            Matcher sourceFile = SOURCE_FILE_ATTRIBUTE.matcher(classMatcher.group(2));
            String classContent = classMatcher.group(3);

            List<MethodInfo> methods = parseMethods(classContent);

            Coverage clsInstruction = extractCounter(classContent, "INSTRUCTION");
            Coverage clsBranch = extractCounter(classContent, "BRANCH");
            Coverage clsLine = extractCounter(classContent, "LINE");

            classes.add(new ClassInfo(
                className,
                sourceFile.find() ? sourceFile.group(1) : null,
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
     * Parses the per-line data of every source file in a package.
     *
     * <p>JaCoCo records line coverage per source file rather than per class, in
     * {@code <sourcefile>} elements that follow the package's classes.</p>
     *
     * @return The lines of each source file, keyed by file name, in the order the report lists
     *         them
     */
    private static Map<String, List<SourceLine>> parseSourceFiles(String packageContent) {
        Map<String, List<SourceLine>> byFile = new HashMap<>();
        Matcher fileMatcher = SOURCE_FILE_ELEMENT.matcher(packageContent);
        while (fileMatcher.find()) {
            List<SourceLine> lines = new ArrayList<>();
            Matcher lineMatcher = LINE_ELEMENT.matcher(fileMatcher.group(2));
            while (lineMatcher.find()) {
                lines.add(new SourceLine(
                    Integer.parseInt(lineMatcher.group(1)),
                    Long.parseLong(lineMatcher.group(2)),
                    Long.parseLong(lineMatcher.group(3))));
            }
            byFile.put(fileMatcher.group(1), lines);
        }
        return byFile;
    }

    /**
     * Groups a source file's lines into the ranges no test executed.
     *
     * <p>A line is unexecuted when it carries instructions and none of them ran. JaCoCo lists
     * only lines that carry instructions, so blank lines, comments and declarations never
     * interrupt a range; a range ends at the next line that did execute.</p>
     *
     * @param lines The file's lines as the report lists them
     * @return The unexecuted ranges in line order
     */
    static List<LineGap> unexecutedRanges(List<SourceLine> lines) {
        List<SourceLine> ordered = lines.stream()
            .sorted(Comparator.comparingInt(SourceLine::number))
            .toList();
        List<LineGap> gaps = new ArrayList<>();
        int start = -1;
        int end = -1;
        for (SourceLine line : ordered) {
            if (line.coveredInstructions() == 0 && line.missedInstructions() > 0) {
                if (start < 0) {
                    start = line.number();
                }
                end = line.number();
            } else if (line.coveredInstructions() > 0 && start >= 0) {
                gaps.add(new LineGap(start, end, UNEXECUTED));
                start = -1;
            }
        }
        if (start >= 0) {
            gaps.add(new LineGap(start, end, UNEXECUTED));
        }
        return List.copyOf(gaps);
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
     *
     * <p>Delegates to {@link CoverageValidator#calculatePercentage(long, long)}
     * to ensure consistent behavior across all coverage calculations.</p>
     *
     * <p>Note: Empty code (total = 0) returns 0.0% coverage as per project convention.</p>
     */
    private static double calculatePercentage(long covered, long missed) {
        return CoverageValidator.calculatePercentage(covered, missed);
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

                    if (!cls.uncoveredLines().isEmpty()) {
                        sb.append(String.format("      Unexecuted lines in %s: %s%n",
                            cls.sourceFile(), describeRanges(cls.uncoveredLines())));
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

    /**
     * Renders line ranges as {@code 12-15, 40}, naming how many more there are past the cap.
     *
     * @param gaps The ranges, in line order
     * @return The rendered list
     */
    static String describeRanges(List<LineGap> gaps) {
        String shown = gaps.stream()
            .limit(MAX_RANGES_PRINTED)
            .map(gap -> gap.startLine() == gap.endLine()
                ? Integer.toString(gap.startLine())
                : gap.startLine() + "-" + gap.endLine())
            .collect(Collectors.joining(", "));
        int hidden = gaps.size() - MAX_RANGES_PRINTED;
        return hidden > 0 ? shown + " and " + hidden + " more" : shown;
    }

    // Record types for parsed data

    private record Coverage(long covered, long missed) {}

    private record PackageInfo(
        String name,
        List<ClassInfo> classes,
        Map<String, List<SourceLine>> sourceLines,
        long instructionCovered,
        long instructionMissed,
        long branchCovered,
        long branchMissed,
        long lineCovered,
        long lineMissed
    ) {}

    private record ClassInfo(
        String name,
        String sourceFile,
        List<MethodInfo> methods,
        long instructionCovered,
        long instructionMissed,
        long branchCovered,
        long branchMissed,
        long lineCovered,
        long lineMissed
    ) {}

    /**
     * One line of a source file as JaCoCo reports it.
     *
     * @param number The line number
     * @param missedInstructions Instructions on the line that no test executed
     * @param coveredInstructions Instructions on the line that a test executed
     */
    record SourceLine(int number, long missedInstructions, long coveredInstructions) {}

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
     * @param sourceFile the source file the class was compiled from, or null when the report
     *                   does not name one
     * @param uncoveredLines the ranges of {@code sourceFile} that no test executed. JaCoCo
     *                       records lines per source file, so a nested class and the class
     *                       enclosing it report the same ranges
     */
    public record ClassGap(
        String packageName,
        String className,
        double instructionCoverage,
        double branchCoverage,
        double lineCoverage,
        List<MethodGap> uncoveredMethods,
        String sourceFile,
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
    /**
     * The modules under {@code projectRoot} that carry a JaCoCo report.
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

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: CoverageGapAnalyzer <project-root> [module...]");
            System.exit(1);
        }

        try {
            Path projectRoot = Paths.get(args[0]);
            List<String> modules = List.of(args).subList(1, args.length);
            Map<String, ModuleGapAnalysis> results = modules.isEmpty()
                ? analyze(projectRoot)
                : analyze(projectRoot, modules);
            System.out.println(generateTextSummary(results));
        } catch (IOException e) {
            System.err.println("Failed to analyze coverage gaps: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
