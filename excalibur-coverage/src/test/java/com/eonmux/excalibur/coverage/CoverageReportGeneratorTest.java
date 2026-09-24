package com.eonmux.excalibur.core.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link CoverageReportGenerator}.
 *
 * <p>Verifies HTML report generation from JaCoCo XML reports.</p>
 */
@DisplayName("CoverageReportGenerator Tests")
class CoverageReportGeneratorTest {

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
                    <method name="uncoveredMethod" desc="()V" line="30">
                        <counter type="INSTRUCTION" missed="15" covered="0"/>
                        <counter type="LINE" missed="4" covered="0"/>
                        <counter type="COMPLEXITY" missed="1" covered="0"/>
                        <counter type="METHOD" missed="1" covered="0"/>
                    </method>
                    <counter type="INSTRUCTION" missed="15" covered="10"/>
                    <counter type="BRANCH" missed="2" covered="4"/>
                    <counter type="LINE" missed="4" covered="3"/>
                    <counter type="COMPLEXITY" missed="1" covered="1"/>
                    <counter type="METHOD" missed="1" covered="1"/>
                    <counter type="CLASS" missed="0" covered="1"/>
                </class>
                <counter type="INSTRUCTION" missed="15" covered="10"/>
                <counter type="BRANCH" missed="2" covered="4"/>
                <counter type="LINE" missed="4" covered="3"/>
                <counter type="COMPLEXITY" missed="1" covered="1"/>
                <counter type="METHOD" missed="1" covered="1"/>
                <counter type="CLASS" missed="0" covered="1"/>
            </package>
            <counter type="INSTRUCTION" missed="15" covered="10"/>
            <counter type="BRANCH" missed="2" covered="4"/>
            <counter type="LINE" missed="4" covered="3"/>
            <counter type="COMPLEXITY" missed="1" covered="1"/>
            <counter type="METHOD" missed="1" covered="1"/>
            <counter type="CLASS" missed="0" covered="1"/>
        </report>
        """;

    @TempDir
    Path tempDir;

    private Path projectRoot;
    private Path outputPath;

    @BeforeEach
    void setUp() throws IOException {
        projectRoot = tempDir.resolve("project");
        outputPath = tempDir.resolve("reports").resolve("coverage.html");

        // Create module structure
        Path moduleDir = projectRoot.resolve("excalibur-core")
            .resolve("build/reports/jacoco/test");
        Files.createDirectories(moduleDir);

        // Write sample JaCoCo XML
        Path xmlReport = moduleDir.resolve("jacocoTestReport.xml");
        Files.writeString(xmlReport, SAMPLE_JACOCO_XML);
    }

    @Nested
    @DisplayName("generate")
    class GenerateTests {

        @Test
        @DisplayName("Should create output directory if not exists")
        void shouldCreateOutputDirectory() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            assertThat(Files.exists(outputPath.getParent())).isTrue();
        }

        @Test
        @DisplayName("Should generate HTML file")
        void shouldGenerateHtmlFile() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            assertThat(Files.exists(outputPath)).isTrue();
        }

        @Test
        @DisplayName("Should generate valid HTML content")
        void shouldGenerateValidHtmlContent() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content)
                .contains("<!DOCTYPE html>")
                .contains("<html")
                .contains("</html>")
                .contains("<title>ExcaliburDB Coverage Report</title>");
        }

        @Test
        @DisplayName("Should include coverage percentage")
        void shouldIncludeCoveragePercentage() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            // Should contain some percentage value
            assertThat(content).contains("%");
        }

        @Test
        @DisplayName("Should include threshold indicator")
        void shouldIncludeThresholdIndicator() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content)
                .containsAnyOf("Threshold Met", "Threshold Not Met", "Below Threshold");
        }

        @Test
        @DisplayName("Should include module section")
        void shouldIncludeModuleSection() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("excalibur-core");
        }

        @Test
        @DisplayName("Should handle missing module directories gracefully")
        void shouldHandleMissingModuleDirectories() throws IOException {
            // Delete the module directory
            Files.deleteIfExists(projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml"));

            CoverageReportGenerator.generate(projectRoot, outputPath);

            // Should still generate a report
            assertThat(Files.exists(outputPath)).isTrue();
        }
    }

    @Nested
    @DisplayName("parseJaCoCoXml")
    class ParseJaCoCoXmlTests {

        @Test
        @DisplayName("Should parse instruction coverage")
        void shouldParseInstructionCoverage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "excalibur-core");

            assertThat(coverage.instructionCovered()).isEqualTo(10);
            assertThat(coverage.instructionMissed()).isEqualTo(15);
        }

        @Test
        @DisplayName("Should parse branch coverage")
        void shouldParseBranchCoverage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "excalibur-core");

            assertThat(coverage.branchCovered()).isEqualTo(4);
            assertThat(coverage.branchMissed()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should parse line coverage")
        void shouldParseLineCoverage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "excalibur-core");

            assertThat(coverage.lineCovered()).isEqualTo(3);
            assertThat(coverage.lineMissed()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should calculate instruction percentage correctly")
        void shouldCalculateInstructionPercentage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "excalibur-core");

            // 10 covered / (10 + 15) = 40%
            double pct = coverage.getInstructionPercentage();
            assertThat(pct).isCloseTo(40.0, within(0.1));
        }
    }

    @Nested
    @DisplayName("ModuleCoverage Record")
    class ModuleCoverageTests {

        @Test
        @DisplayName("Should store module name")
        void shouldStoreModuleName() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "my-module");

            assertThat(coverage.moduleName()).isEqualTo("my-module");
        }

        @Test
        @DisplayName("Should calculate branch percentage")
        void shouldCalculateBranchPercentage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "test");

            // 4 covered / (4 + 2) = 66.67%
            double pct = coverage.getBranchPercentage();
            assertThat(pct).isCloseTo(66.67, within(0.1));
        }

        @Test
        @DisplayName("Should calculate line percentage")
        void shouldCalculateLinePercentage() throws IOException {
            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");

            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "test");

            // 3 covered / (3 + 4) = 42.86%
            double pct = coverage.getLinePercentage();
            assertThat(pct).isCloseTo(42.86, within(0.1));
        }

        @Test
        @DisplayName("Should handle zero coverage")
        void shouldHandleZeroCoverage() {
            var coverage = new CoverageReportGenerator.ModuleCoverage(
                "empty-module",
                0, 0,  // instruction
                0, 0,  // branch
                0, 0   // line
            );

            assertThat(coverage.getInstructionPercentage()).isEqualTo(0.0);
            assertThat(coverage.getBranchPercentage()).isEqualTo(0.0);
            assertThat(coverage.getLinePercentage()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("HTML Content Structure")
    class HtmlContentTests {

        @Test
        @DisplayName("Should include CSS styles")
        void shouldIncludeCssStyles() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("<style>");
        }

        @Test
        @DisplayName("Should include summary card")
        void shouldIncludeSummaryCard() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("summary-card");
            assertThat(content).contains("Aggregate Code Coverage");
        }

        @Test
        @DisplayName("Should include module cards")
        void shouldIncludeModuleCards() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("module-card");
        }

        @Test
        @DisplayName("Should include detailed table")
        void shouldIncludeDetailedTable() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("Detailed Coverage Breakdown");
        }

        @Test
        @DisplayName("Should include gap analysis section")
        void shouldIncludeGapAnalysisSection() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("Missing Coverage Areas");
        }

        @Test
        @DisplayName("Should include footer with timestamp")
        void shouldIncludeFooterWithTimestamp() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            String content = Files.readString(outputPath);
            assertThat(content).contains("Generated:");
            assertThat(content).contains("Coverage threshold: 80%");
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Coverage percentages should be in valid range")
        void coveragePercentagesShouldBeInValidRange() throws IOException {
            CoverageReportGenerator.generate(projectRoot, outputPath);

            Path xmlPath = projectRoot.resolve("excalibur-core")
                .resolve("build/reports/jacoco/test/jacocoTestReport.xml");
            var coverage = CoverageReportGenerator.parseJaCoCoXml(xmlPath, "test");

            assertThat(coverage.getInstructionPercentage()).isBetween(0.0, 100.0);
            assertThat(coverage.getBranchPercentage()).isBetween(0.0, 100.0);
            assertThat(coverage.getLinePercentage()).isBetween(0.0, 100.0);
        }

        @Test
        @DisplayName("Should handle large coverage values")
        void shouldHandleLargeCoverageValues() throws IOException {
            String largeXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="large-module">
                    <counter type="INSTRUCTION" missed="1000000" covered="9000000"/>
                    <counter type="BRANCH" missed="100000" covered="900000"/>
                    <counter type="LINE" missed="50000" covered="450000"/>
                </report>
                """;

            Path moduleDir = projectRoot.resolve("excalibur-vector")
                .resolve("build/reports/jacoco/test");
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("jacocoTestReport.xml"), largeXml);

            var coverage = CoverageReportGenerator.parseJaCoCoXml(
                moduleDir.resolve("jacocoTestReport.xml"), "excalibur-vector");

            assertThat(coverage.getInstructionPercentage()).isCloseTo(90.0, within(0.1));
            assertThat(coverage.instructionCovered()).isEqualTo(9_000_000L);
            assertThat(coverage.instructionMissed()).isEqualTo(1_000_000L);
        }
    }
}
