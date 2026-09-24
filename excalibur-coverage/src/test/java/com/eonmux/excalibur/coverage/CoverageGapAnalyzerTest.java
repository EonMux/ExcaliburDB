package com.eonmux.excalibur.coverage;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.ClassGap;
import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.LineGap;
import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.MethodGap;
import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.ModuleGapAnalysis;
import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.PackageGap;
import com.eonmux.excalibur.coverage.CoverageGapAnalyzer.SourceLine;

/**
 * Tests for {@link CoverageGapAnalyzer}.
 *
 * <p>Thread Safety: Each test is independent and operates on isolated test data.</p>
 */
class CoverageGapAnalyzerTest {

    private static final String SAMPLE_JACOCO_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
        <report name="test-module">
            <package name="com/example/pkg1">
                <class name="com/example/pkg1/ClassA" sourcefilename="ClassA.java">
                    <method name="&lt;init&gt;" desc="()V" line="10">
                        <counter type="INSTRUCTION" missed="0" covered="10"/>
                        <counter type="LINE" missed="0" covered="3"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="coveredMethod" desc="()V" line="20">
                        <counter type="INSTRUCTION" missed="0" covered="20"/>
                        <counter type="LINE" missed="0" covered="5"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="uncoveredMethod" desc="()V" line="30">
                        <counter type="INSTRUCTION" missed="15" covered="0"/>
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="COMPLEXITY" missed="1" covered="0"/>
                        <counter type="METHOD" missed="1" covered="0"/>
                    </method>
                    <counter type="INSTRUCTION" missed="15" covered="30"/>
                    <counter type="BRANCH" missed="2" covered="4"/>
                    <counter type="LINE" missed="4" covered="8"/>
                    <counter type="COMPLEXITY" missed="1" covered="2"/>
                    <counter type="METHOD" missed="1" covered="2"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                </class>
                <sourcefile name="ClassA.java">
                    <line nr="40" mi="2" ci="0" mb="0" cb="0"/>
                    <line nr="10" mi="0" ci="3" mb="0" cb="0"/>
                    <line nr="20" mi="0" ci="5" mb="0" cb="0"/>
                    <line nr="30" mi="4" ci="0" mb="0" cb="0"/>
                    <line nr="31" mi="4" ci="0" mb="0" cb="0"/>
                    <line nr="33" mi="3" ci="0" mb="0" cb="0"/>
                    <line nr="35" mi="1" ci="2" mb="1" cb="1"/>
                    <counter type="INSTRUCTION" missed="15" covered="30"/>
                    <counter type="LINE" missed="4" covered="8"/>
                </sourcefile>
                <counter type="INSTRUCTION" missed="15" covered="30"/>
                <counter type="BRANCH" missed="2" covered="4"/>
                <counter type="LINE" missed="4" covered="8"/>
                <counter type="COMPLEXITY" missed="1" covered="2"/>
                <counter type="METHOD" missed="1" covered="2"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </package>
            <package name="com/example/pkg2">
                <class name="com/example/pkg2/ClassB" sourcefilename="ClassB.java">
                    <method name="&lt;init&gt;" desc="()V" line="5">
                        <counter type="INSTRUCTION" missed="0" covered="5"/>
                        <counter type="LINE" missed="0" covered="2"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <method name="fullyCovered" desc="()I" line="15">
                        <counter type="INSTRUCTION" missed="0" covered="10"/>
                        <counter type="LINE" missed="0" covered="3"/>
                        <counter type="COMPLEXITY" missed="0" covered="1"/>
                        <counter type="METHOD" missed="0" covered="1"/>
                    </method>
                    <counter type="INSTRUCTION" missed="0" covered="15"/>
                    <counter type="BRANCH" missed="0" covered="2"/>
                    <counter type="LINE" missed="0" covered="5"/>
                    <counter type="COMPLEXITY" missed="0" covered="2"/>
                    <counter type="METHOD" missed="0" covered="2"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                </class>
                <counter type="INSTRUCTION" missed="0" covered="15"/>
                <counter type="BRANCH" missed="0" covered="2"/>
                <counter type="LINE" missed="0" covered="5"/>
                <counter type="COMPLEXITY" missed="0" covered="2"/>
                <counter type="METHOD" missed="0" covered="2"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </package>
            <counter type="INSTRUCTION" missed="15" covered="45"/>
            <counter type="BRANCH" missed="2" covered="6"/>
            <counter type="LINE" missed="4" covered="13"/>
            <counter type="COMPLEXITY" missed="1" covered="4"/>
            <counter type="METHOD" missed="1" covered="4"/>
            <counter type="CLASS" missed="0" covered="2"/>
        </report>
        """;

    @TempDir
    Path tempDir;

    private Path jacocoReportPath;

    @BeforeEach
    void setUp() throws IOException {
        jacocoReportPath = tempDir.resolve("jacocoTestReport.xml");
        Files.writeString(jacocoReportPath, SAMPLE_JACOCO_XML);
    }

    @Test
    void analyzeModule_shouldIdentifyClassesBelowThreshold() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        assertThat(analysis.moduleName()).isEqualTo("test-module");
        assertThat(analysis.moduleCoverage()).isGreaterThan(0.0);

        // ClassA has 33% uncovered (15/45 instructions), so it should be flagged
        assertThat(analysis.classesBelowThreshold())
            .hasSize(1)
            .first()
            .satisfies(cls -> {
                assertThat(cls.className()).isEqualTo("ClassA");
                assertThat(cls.instructionCoverage()).isCloseTo(66.67, within(0.01)); // 30/(30+15) = 66.67%
            });
    }

    @Test
    void analyzeModule_shouldIdentifyUncoveredMethods() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        assertThat(analysis.methodsWithZeroCoverage())
            .hasSize(1)
            .first()
            .satisfies(method -> {
                assertThat(method.methodName()).isEqualTo("uncoveredMethod");
                assertThat(method.lineNumber()).isEqualTo(30);
                assertThat(method.missedInstructions()).isEqualTo(15);
            });
    }

    @Test
    void analyzeModule_shouldIdentifyPackagesBelowThreshold() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        // pkg1 has 66.67% coverage (30/45 instructions), below 80%
        assertThat(analysis.packagesBelowThreshold())
            .hasSize(1)
            .first()
            .satisfies(pkg -> {
                assertThat(pkg.packageName()).isEqualTo("com.example.pkg1");
                assertThat(pkg.coverage()).isCloseTo(66.67, within(0.01));
                assertThat(pkg.classCount()).isEqualTo(1);
            });
    }

    @Test
    void analyzeModule_shouldCalculateCorrectModuleCoverage() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        // Total: 45 covered, 15 missed = 75% coverage
        assertThat(analysis.moduleCoverage()).isEqualTo(75.0);
    }

    @Test
    void generateTextSummary_shouldProduceReadableOutput() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");
        Map<String, ModuleGapAnalysis> results = Map.of("test-module", analysis);

        String summary = CoverageGapAnalyzer.generateTextSummary(results);

        assertThat(summary)
            .contains("COVERAGE GAP ANALYSIS REPORT")
            .contains("MODULE: test-module")
            .contains("Packages Below 80% Threshold")
            .contains("Classes Below 80% Threshold")
            .contains("Methods With Zero Coverage")
            .contains("uncoveredMethod")
            .contains("Unexecuted lines in ClassA.java: 30-33, 40")
            .contains("SUMMARY");
    }

    @Test
    void analyzeModule_shouldReportTheSourceFileRangesNoTestExecuted() throws IOException {
        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(jacocoReportPath, "test-module");

        ClassGap classA = analysis.classesBelowThreshold().getFirst();

        assertThat(classA.sourceFile()).isEqualTo("ClassA.java");
        // 30, 31 and 33 run together: 32 carries no instructions, so it is not in the report.
        // 35 executed in part, which ends the range; 40 opens a new one.
        assertThat(classA.uncoveredLines()).containsExactly(
            new LineGap(30, 33, CoverageGapAnalyzer.UNEXECUTED),
            new LineGap(40, 40, CoverageGapAnalyzer.UNEXECUTED));
    }

    @Test
    void analyzeModule_shouldReportNoRangesForAClassWithoutASourceFile() throws IOException {
        String xml = SAMPLE_JACOCO_XML.replace(" sourcefilename=\"ClassA.java\"", "");
        Path report = tempDir.resolve("noSourceFile.xml");
        Files.writeString(report, xml);

        ClassGap classA = CoverageGapAnalyzer.analyzeModule(report, "test-module")
            .classesBelowThreshold().getFirst();

        assertThat(classA.sourceFile()).isNull();
        assertThat(classA.uncoveredLines()).isEmpty();
    }

    @Test
    void unexecutedRanges_shouldBeEmptyWhenEveryLineRan() {
        assertThat(CoverageGapAnalyzer.unexecutedRanges(List.of())).isEmpty();
        assertThat(CoverageGapAnalyzer.unexecutedRanges(List.of(
            new SourceLine(1, 0, 4),
            new SourceLine(2, 1, 1)))).isEmpty();
    }

    @Test
    void unexecutedRanges_shouldCloseARangeRunningToTheEndOfTheFile() {
        assertThat(CoverageGapAnalyzer.unexecutedRanges(List.of(
            new SourceLine(7, 0, 2),
            new SourceLine(9, 3, 0),
            new SourceLine(12, 1, 0))))
            .containsExactly(new LineGap(9, 12, CoverageGapAnalyzer.UNEXECUTED));
    }

    @Test
    void describeRanges_shouldNameSingleLinesAndCountWhatItLeavesOut() {
        List<LineGap> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new LineGap(i * 10, i * 10 + (i % 2), CoverageGapAnalyzer.UNEXECUTED));
        }

        assertThat(CoverageGapAnalyzer.describeRanges(twelve.subList(0, 2))).isEqualTo("0, 10-11");
        assertThat(CoverageGapAnalyzer.describeRanges(twelve))
            .startsWith("0, 10-11, 20, 30-31")
            .endsWith("90-91 and 2 more");
    }

    @Test
    void classGap_shouldProvideAccessToCoverageMetrics() {
        ClassGap gap = new ClassGap(
            "com.example",
            "TestClass",
            50.0,  // instruction
            40.0,  // branch
            60.0,  // line
            List.of(),
            "TestClass.java",
            List.of()
        );

        assertThat(gap.packageName()).isEqualTo("com.example");
        assertThat(gap.className()).isEqualTo("TestClass");
        assertThat(gap.instructionCoverage()).isEqualTo(50.0);
        assertThat(gap.branchCoverage()).isEqualTo(40.0);
        assertThat(gap.lineCoverage()).isEqualTo(60.0);
        assertThat(gap.uncoveredMethods()).isEmpty();
        assertThat(gap.uncoveredLines()).isEmpty();
    }

    @Test
    void methodGap_shouldProvideAccessToDetails() {
        MethodGap gap = new MethodGap(
            "com.example.TestClass",
            "testMethod",
            42,
            100
        );

        assertThat(gap.className()).isEqualTo("com.example.TestClass");
        assertThat(gap.methodName()).isEqualTo("testMethod");
        assertThat(gap.lineNumber()).isEqualTo(42);
        assertThat(gap.missedInstructions()).isEqualTo(100);
    }

    @Test
    void packageGap_shouldProvideAccessToDetails() {
        PackageGap gap = new PackageGap(
            "com.example.subpackage",
            55.5,
            10
        );

        assertThat(gap.packageName()).isEqualTo("com.example.subpackage");
        assertThat(gap.coverage()).isEqualTo(55.5);
        assertThat(gap.classCount()).isEqualTo(10);
    }

    @Test
    void moduleGapAnalysis_shouldAggregateAllGapTypes() {
        ModuleGapAnalysis analysis = new ModuleGapAnalysis(
            "test-module",
            List.of(new ClassGap("pkg", "cls", 50.0, 40.0, 30.0, List.of(), "cls.java", List.of())),
            List.of(new MethodGap("pkg.cls", "method", 10, 50)),
            List.of(new PackageGap("pkg", 60.0, 1)),
            70.0
        );

        assertThat(analysis.moduleName()).isEqualTo("test-module");
        assertThat(analysis.classesBelowThreshold()).hasSize(1);
        assertThat(analysis.methodsWithZeroCoverage()).hasSize(1);
        assertThat(analysis.packagesBelowThreshold()).hasSize(1);
        assertThat(analysis.moduleCoverage()).isEqualTo(70.0);
    }

    @Test
    void analyzeModule_shouldHandleEmptyReport() throws IOException {
        String emptyXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
            <report name="empty-module">
                <counter type="INSTRUCTION" missed="0" covered="0"/>
                <counter type="BRANCH" missed="0" covered="0"/>
                <counter type="LINE" missed="0" covered="0"/>
                <counter type="METHOD" missed="0" covered="0"/>
                <counter type="CLASS" missed="0" covered="0"/>
            </report>
            """;

        Path emptyReportPath = tempDir.resolve("emptyReport.xml");
        Files.writeString(emptyReportPath, emptyXml);

        ModuleGapAnalysis analysis = CoverageGapAnalyzer.analyzeModule(emptyReportPath, "empty-module");

        assertThat(analysis.moduleName()).isEqualTo("empty-module");
        // Domain correctness: No code to cover means 0% coverage (nothing was covered)
        assertThat(analysis.moduleCoverage()).isEqualTo(0.0);
        assertThat(analysis.classesBelowThreshold()).isEmpty();
        assertThat(analysis.methodsWithZeroCoverage()).isEmpty();
    }
}
