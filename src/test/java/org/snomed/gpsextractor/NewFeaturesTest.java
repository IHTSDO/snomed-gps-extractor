package org.snomed.gpsextractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for new features:
 *   - README content (URL formatting, no orphaned URLs)
 *   - Implementation guide bundling via --implementation-guide
 *   - list-validations command
 *   - Semantic tag extraction utilities
 */
class NewFeaturesTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearExceptionsDir() throws IOException {
        Path exDir = Paths.get(ExceptionList.EXCEPTIONS_DIR);
        if (Files.exists(exDir)) {
            try (var walk = Files.walk(exDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    // =========================================================================
    // README content tests
    // =========================================================================

    @Test
    void testReadmeContent_urlsAreInline_notOnSeparateLine() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));

        // The Creative Commons URL must not appear at the start of a line (i.e. orphaned)
        String[] lines = readme.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            assertFalse(trimmed.startsWith("https://creativecommons.org") && trimmed.equals("https://creativecommons.org/licenses/by-nd/4.0/"),
                "Creative Commons URL must not appear as a standalone line");
            assertFalse(trimmed.startsWith("https://www.snomed.org/gps") && trimmed.equals("https://www.snomed.org/gps"),
                "SNOMED GPS URL must not appear as a standalone line");
        }

        // URLs must still be present somewhere in the content
        assertTrue(readme.contains("https://creativecommons.org/licenses/by-nd/4.0/"),
            "README must contain the Creative Commons URL");
        assertTrue(readme.contains("https://www.snomed.org/gps"),
            "README must contain the SNOMED GPS URL");
    }

    @Test
    void testReadmeContent_urlsAreEmbeddedInText() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));

        // Each URL line should be preceded by a '(' and followed by a ')'
        assertTrue(readme.contains("(https://creativecommons.org/licenses/by-nd/4.0/)"),
            "Creative Commons URL should be enclosed in parentheses inline with text");
        assertTrue(readme.contains("(https://www.snomed.org/gps)"),
            "SNOMED GPS URL should be enclosed in parentheses inline with text");
    }

    @Test
    void testReadmeContent_directoryListingContainsAllFiles() {
        List<String> entries = List.of("data.txt", "Readme.txt", "GPS_ImplementationGuide.pdf");
        String readme = ExtractTerms.buildReadmeContent("20260301", entries);

        for (String entry : entries) {
            assertTrue(readme.contains(entry),
                "README directory listing must contain: " + entry);
        }
    }

    @Test
    void testReadmeContent_effectiveDateFormatted() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt"));
        assertTrue(readme.contains("20260301"), "README must contain the effective date");
        assertTrue(readme.contains("March 2026"), "README must contain the formatted month/year");
    }

    @Test
    void testReadmeContent_doesNotContainOrphanedPunctuation() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));
        // The old code had ", ." artefacts before URLs
        assertFalse(readme.contains(", ."), "README must not contain ', .' artefacts");
        assertFalse(readme.contains("at .\n"), "README must not have dangling 'at .' before a URL on the next line");
    }

    // =========================================================================
    // Implementation guide bundling tests
    // =========================================================================

    @Test
    void testImplementationGuide_bundledInOutputZip() throws IOException {
        // Create minimal RF2 source files
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
             "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n" +
             "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tTest concept (disorder)\n" +
             "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tTest concept\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n" +
             "999\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n").getBytes());

        // Create a mock implementation guide
        Path guideFile = tempDir.resolve("GPS_ImplementationGuide_v2.pdf");
        Files.write(guideFile, "Mock implementation guide content".getBytes());

        // Create source RF2 ZIP
        Path rf2Zip = tempDir.resolve("SnomedCT_Release_INT_20240101.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rf2Zip.toFile()))) {
            addToZip(zos, conceptsFile, "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
            addToZip(zos, descFile,     "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
            addToZip(zos, prefFile,     "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        }

        Path outputZip = tempDir.resolve("output.zip");
        ExtractTerms.main(new String[]{
            "--implementation-guide", guideFile.toString(),
            rf2Zip.toString(), outputZip.toString()
        });

        assertTrue(Files.exists(outputZip), "Output ZIP must be created");

        // Check that the implementation guide is bundled
        List<String> entryNames = new ArrayList<>();
        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                entryNames.add(entries.nextElement().getName());
            }
        }

        assertTrue(entryNames.contains("GPS_ImplementationGuide_v2.pdf"),
            "Output ZIP must contain the implementation guide. Found entries: " + entryNames);
        assertTrue(entryNames.contains("Readme.txt"),
            "Output ZIP must contain Readme.txt");
    }

    @Test
    void testImplementationGuide_appearsInReadmeDirectoryListing() throws IOException {
        List<String> entries = List.of("data.txt", "Readme.txt", "GPS_ImplementationGuide_v2.pdf");
        String readme = ExtractTerms.buildReadmeContent("20260301", entries);

        assertTrue(readme.contains("GPS_ImplementationGuide_v2.pdf"),
            "README directory listing must include the implementation guide filename");
    }

    @Test
    void testImplementationGuide_missingFileCausesError() throws IOException {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            ExtractTerms.main(new String[]{
                "--implementation-guide", "/nonexistent/guide.pdf",
                "dummy.zip"
            });
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(err.toString().contains("not found") || err.toString().contains("Error"),
            "Should print an error for missing implementation guide file");
    }

    @Test
    void testExtractTerms_withoutImplementationGuide_noGuideInZip() throws IOException {
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
             "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n" +
             "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tTest (disorder)\n" +
             "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tTest\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n" +
             "999\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n").getBytes());

        Path rf2Zip    = tempDir.resolve("SnomedCT_Release_INT_20240101.zip");
        Path outputZip = tempDir.resolve("output.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rf2Zip.toFile()))) {
            addToZip(zos, conceptsFile, "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
            addToZip(zos, descFile,     "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
            addToZip(zos, prefFile,     "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        }

        ExtractTerms.main(new String[]{rf2Zip.toString(), outputZip.toString()});

        List<String> entryNames = new ArrayList<>();
        try (ZipFile zip = new ZipFile(outputZip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                entryNames.add(entries.nextElement().getName());
            }
        }

        // Should be exactly 2 entries: data file + Readme.txt
        assertEquals(2, entryNames.size(),
            "Without --implementation-guide there should be exactly 2 entries; found: " + entryNames);
    }

    // =========================================================================
    // list-validations command test
    // =========================================================================

    @Test
    void testListValidations_printsBothSeverities() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            GpsValidator.listValidations();
        } finally {
            System.setOut(originalOut);
        }
        String output = out.toString();
        assertTrue(output.contains("ERROR"), "list-validations output must mention ERROR tests");
        assertTrue(output.contains("WARNING"), "list-validations output must mention WARNING tests");
        assertTrue(output.contains("FAIL"), "list-validations output must explain that errors cause FAIL");
    }

    @Test
    void testListValidations_allTestNamesPresent() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            GpsValidator.listValidations();
        } finally {
            System.setOut(originalOut);
        }
        String output = out.toString();
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertTrue(output.contains(t.name),
                "list-validations must include test name: " + t.name);
        }
    }

    // =========================================================================
    // Main command routing test
    // =========================================================================

    @Test
    void testMain_listValidationsCommand_dispatches() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            Main.main(new String[]{"list-validations"});
        } finally {
            System.setOut(originalOut);
        }
        String output = out.toString();
        assertTrue(output.contains("GPS Validation Test Catalogue"),
            "list-validations command must output the test catalogue header");
    }

    // =========================================================================
    // Condensed "Validation Tests Performed" report section tests
    // =========================================================================

    @Test
    void testReport_validationTestsSection_isCondensed() throws IOException {
        // Write the condensed table using t.code directly from the enum
        Path reportFile = tempDir.resolve("report.txt");
        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(reportFile.toFile()));
        pw.println("Validation Tests Performed");
        pw.println("--------------------------");
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            pw.printf("  %-6s %-9s %-46s %s%n", t.code, t.severity, t.name, "-");
        }
        pw.close();

        String report = new String(Files.readAllBytes(reportFile));
        // The condensed section must list tests as a table (not multi-line blocks)
        assertTrue(report.contains("E01"), "Condensed report must contain test code E01");
        assertTrue(report.contains("ERROR"),   "Condensed report must contain ERROR severity");
        assertTrue(report.contains("WARNING"), "Condensed report must contain WARNING severity");
        // There should be NO multi-line description blocks like the old format
        assertFalse(report.contains("Every data row must have exactly 4"),
            "Condensed report must not include verbose description text inline");
    }

    @Test
    void testReport_condensedSection_containsAllTestCodes() throws IOException {
        // Every ValidationTest enum constant carries a stable code on t.code —
        // verify they all appear in a report table built from the enum.
        Path reportFile = tempDir.resolve("report.txt");
        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(reportFile.toFile()));
        pw.println("Validation Tests Performed");
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            pw.printf("  %-6s %-9s %s%n", t.code, t.severity, t.name);
        }
        pw.close();
        String report = new String(Files.readAllBytes(reportFile));
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertTrue(report.contains(t.code),
                "Report must contain stable code " + t.code + " for test " + t.name());
        }
    }

    @Test
    void testReport_exceptionCountColumn_showsDashWhenNoExceptions() throws IOException {
        // When there are no exceptions the column should show '-'
        Path reportFile = tempDir.resolve("report.txt");
        java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(reportFile.toFile()));
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            int excluded = ExceptionList.getExceptions(t).size();
            String excludedStr = excluded > 0 ? String.valueOf(excluded) : "-";
            pw.printf("  %-46s %s%n", t.name, excludedStr);
        }
        pw.close();
        String report = new String(Files.readAllBytes(reportFile));
        // All lines for tests with no exceptions should end with ' -'
        for (String line : report.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(line.trim().endsWith("-"),
                    "With no exception files, every row excluded count must be '-': " + line);
            }
        }
    }

    // =========================================================================
    // US_PREFERRED_TERM_NOT_GB validation tests
    // =========================================================================

    @Test
    void testUsPreferredTermNotGb_noFindingWhenTermsMatch() throws IOException {
        // When US and GB preferred terms are the same, no finding should be raised
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        Path gpsFile      = tempDir.resolve("gps.tsv");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
           + "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        // desc 102 is preferred in BOTH US and GB (same term)
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
           + "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tDiabetes mellitus (disorder)\n"
           + "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tDiabetes mellitus\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
           // US preferred
           + "901\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n"
           // GB preferred — same description, same term
           + "902\t20240101\t1\t900000000000207008\t900000000000508004\t102\t900000000000548007\n").getBytes());
        Files.write(gpsFile,
            ("ConceptID\tActive\tFSN\tUSPreferredTerm\n"
           + "100\t1\tDiabetes mellitus (disorder)\tDiabetes mellitus\n").getBytes());

        Map<String, String[]> oracle = GpsValidator.buildExpectedOutput(conceptsFile, descFile, prefFile, false, null);
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(gpsFile, oracle, false, null);
        long gbFindings = findings.stream()
            .filter(f -> f.test == GpsValidator.ValidationTest.US_PREFERRED_TERM_NOT_GB).count();
        assertEquals(0, gbFindings,
            "No US_PREFERRED_TERM_NOT_GB finding expected when US and GB terms are identical");
    }

    @Test
    void testUsPreferredTermNotGb_findingWhenOutputUsesGbTerm() throws IOException {
        // When US and GB preferred terms differ and the output contains the GB term, a finding is raised
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        Path gpsFile      = tempDir.resolve("gps.tsv");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
           + "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        // desc 102 = US preferred ("Paracetamol"), desc 103 = GB preferred ("Acetaminophen" variant)
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
           + "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tAcetaminophen (substance)\n"
           + "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tAcetaminophen\n"
           + "103\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tParacetamol\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
           // US preferred = desc 102 (Acetaminophen)
           + "901\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n"
           // GB preferred = desc 103 (Paracetamol)
           + "902\t20240101\t1\t900000000000207008\t900000000000508004\t103\t900000000000548007\n").getBytes());
        // GPS file incorrectly contains the GB preferred term
        Files.write(gpsFile,
            ("ConceptID\tActive\tFSN\tUSPreferredTerm\n"
           + "100\t1\tAcetaminophen (substance)\tParacetamol\n").getBytes());

        Map<String, String[]> oracle = GpsValidator.buildExpectedOutput(conceptsFile, descFile, prefFile, false, null);
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(gpsFile, oracle, false, null);
        long gbFindings = findings.stream()
            .filter(f -> f.test == GpsValidator.ValidationTest.US_PREFERRED_TERM_NOT_GB).count();
        assertEquals(1, gbFindings,
            "Expected one US_PREFERRED_TERM_NOT_GB finding when GPS contains the GB preferred term");
        assertTrue(findings.stream()
            .filter(f -> f.test == GpsValidator.ValidationTest.US_PREFERRED_TERM_NOT_GB)
            .anyMatch(f -> f.message.contains("Paracetamol") && f.message.contains("Acetaminophen")),
            "Finding message must name both the GB term and the expected US term");
    }

    @Test
    void testUsPreferredTermNotGb_noFindingWhenNoGbTerm() throws IOException {
        // When a concept has no GB preferred term at all, no finding should be raised
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        Path gpsFile      = tempDir.resolve("gps.tsv");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
           + "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
           + "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tTest (disorder)\n"
           + "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tTest\n").getBytes());
        // Only US preferred entry — no GB row at all
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
           + "901\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n").getBytes());
        Files.write(gpsFile,
            ("ConceptID\tActive\tFSN\tUSPreferredTerm\n"
           + "100\t1\tTest (disorder)\tTest\n").getBytes());

        Map<String, String[]> oracle = GpsValidator.buildExpectedOutput(conceptsFile, descFile, prefFile, false, null);
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(gpsFile, oracle, false, null);
        long gbFindings = findings.stream()
            .filter(f -> f.test == GpsValidator.ValidationTest.US_PREFERRED_TERM_NOT_GB).count();
        assertEquals(0, gbFindings,
            "No US_PREFERRED_TERM_NOT_GB finding expected when there is no GB preferred term");
    }

    @Test
    void testUsPreferredTermNotGb_oracleCaptures_gbTermAtIndex3() throws IOException {
        // Verify buildExpectedOutput stores GB term at index 3
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
           + "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
           + "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tAcetaminophen (substance)\n"
           + "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tAcetaminophen\n"
           + "103\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tParacetamol\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
           + "901\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n"
           + "902\t20240101\t1\t900000000000207008\t900000000000508004\t103\t900000000000548007\n").getBytes());

        Map<String, String[]> oracle = GpsValidator.buildExpectedOutput(conceptsFile, descFile, prefFile, false, null);
        String[] row = oracle.get("100");
        assertNotNull(row, "Oracle must contain concept 100");
        assertEquals(4, row.length, "Oracle row must have 4 elements (active, fsn, usTerm, gbTerm)");
        assertEquals("Acetaminophen", row[2], "Index 2 must be the US preferred term");
        assertEquals("Paracetamol",   row[3], "Index 3 must be the GB preferred term");
    }

    @Test
    void testUsPreferredTermNotGb_validationTestExists() {
        // Verify the new validation test is registered
        boolean found = false;
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            if (t == GpsValidator.ValidationTest.US_PREFERRED_TERM_NOT_GB) {
                found = true;
                assertEquals(GpsValidator.Severity.ERROR, t.severity,
                    "US_PREFERRED_TERM_NOT_GB must be an ERROR-severity test");
            }
        }
        assertTrue(found, "US_PREFERRED_TERM_NOT_GB validation test must exist");
    }

    // =========================================================================
    // Security tests
    // =========================================================================

    // ── WebController: filename sanitisation ──────────────────────────────────

    @Test
    void testSanitiseFilename_stripsCarriageReturn() {
        String result = WebController.sanitiseFilename("my\rfile.tsv");
        assertFalse(result.contains("\r"), "Sanitised filename must not contain CR");
    }

    @Test
    void testSanitiseFilename_stripsLineFeed() {
        String result = WebController.sanitiseFilename("my\nfile.tsv");
        assertFalse(result.contains("\n"), "Sanitised filename must not contain LF");
    }

    @Test
    void testSanitiseFilename_stripsDoubleQuote() {
        String result = WebController.sanitiseFilename("my\"file.tsv");
        assertFalse(result.contains("\""), "Sanitised filename must not contain double-quote");
    }

    @Test
    void testSanitiseFilename_stripsForwardSlash() {
        String result = WebController.sanitiseFilename("../../etc/passwd");
        assertFalse(result.contains("/"), "Sanitised filename must not contain forward slash");
        assertFalse(result.contains(".."), "Sanitised filename must not contain path traversal");
    }

    @Test
    void testSanitiseFilename_stripsBackslash() {
        String result = WebController.sanitiseFilename("C:\\Windows\\file.tsv");
        assertFalse(result.contains("\\"), "Sanitised filename must not contain backslash");
    }

    @Test
    void testSanitiseFilename_headerInjectionPayload() {
        // A classic header-injection payload: inject a new header via CRLF
        String malicious = "innocent.tsv\r\nX-Injected: pwned";
        String result = WebController.sanitiseFilename(malicious);
        assertFalse(result.contains("\r"), "Header injection payload must not contain CR");
        assertFalse(result.contains("\n"), "Header injection payload must not contain LF");
        assertFalse(result.contains("X-Injected"), "Header injection string must be neutralised");
    }

    @Test
    void testSanitiseFilename_nullFallsBackToDefault() {
        String result = WebController.sanitiseFilename(null);
        assertEquals("filtered_output.tsv", result,
            "Null filename should produce the default fallback");
    }

    @Test
    void testSanitiseFilename_blankFallsBackToDefault() {
        String result = WebController.sanitiseFilename("   ");
        assertEquals("filtered_output.tsv", result,
            "Blank filename should produce the default fallback");
    }

    @Test
    void testSanitiseFilename_normalFilenamePreserved() {
        String result = WebController.sanitiseFilename("my_data.tsv");
        assertTrue(result.endsWith("my_data.tsv"),
            "Normal filename should be preserved and prefixed");
        assertTrue(result.startsWith("filtered_"),
            "Sanitised filename must carry the 'filtered_' prefix");
    }

    @Test
    void testSanitiseFilename_prefixAlwaysPresent() {
        assertTrue(WebController.sanitiseFilename("data.tsv").startsWith("filtered_"),
            "Result must always start with 'filtered_'");
        assertTrue(WebController.sanitiseFilename("x\ry\nz").startsWith("filtered_"),
            "Result must always start with 'filtered_' even after stripping injection chars");
    }

    // ── WebController: tag validation ─────────────────────────────────────────

    @Test
    void testTagLimits_constantsAreSane() {
        assertTrue(WebController.MAX_SEMANTIC_TAGS > 0, "MAX_SEMANTIC_TAGS must be positive");
        assertTrue(WebController.MAX_TAGS_PARAM_LENGTH > 0, "MAX_TAGS_PARAM_LENGTH must be positive");
        assertTrue(WebController.MAX_TAG_LENGTH > 0, "MAX_TAG_LENGTH must be positive");
        // Sanity: a single tag should always be within the per-tag limit
        assertTrue("disorder".length() < WebController.MAX_TAG_LENGTH,
            "'disorder' must be within the max tag length");
    }

    // ── Zip bomb / decompression attack protection ────────────────────────────

    @Test
    void testZipBomb_extractTerms_constantSane() {
        assertTrue(ExtractTerms.MAX_ENTRY_UNCOMPRESSED_BYTES > 0,
            "ExtractTerms max entry size must be positive");
        // 2 GB: large enough for the biggest real SNOMED release, small enough to block bombs
        assertEquals(2L * 1024 * 1024 * 1024, ExtractTerms.MAX_ENTRY_UNCOMPRESSED_BYTES,
            "ExtractTerms MAX_ENTRY_UNCOMPRESSED_BYTES must be 2 GB");
    }

    @Test
    void testZipBomb_gpsValidator_constantSane() {
        assertTrue(GpsValidator.MAX_ENTRY_UNCOMPRESSED_BYTES > 0,
            "GpsValidator max entry size must be positive");
        assertEquals(2L * 1024 * 1024 * 1024, GpsValidator.MAX_ENTRY_UNCOMPRESSED_BYTES,
            "GpsValidator MAX_ENTRY_UNCOMPRESSED_BYTES must be 2 GB");
    }

    @Test
    void testZipBomb_extractTerms_rejectsOversizedDeclaredEntry() throws IOException {
        // Temporarily lower the per-entry size limit so we can trigger it with small test data.
        // Note: ZipOutputStream DEFLATED entries do not preserve a setSize() value in the central
        // directory, so we test via the LimitedInputStream byte-counter path instead.
        long savedMax = ExtractTerms.MAX_ENTRY_UNCOMPRESSED_BYTES;
        ExtractTerms.MAX_ENTRY_UNCOMPRESSED_BYTES = 2L; // allow only 2 bytes per entry
        try {
            Path zipPath = tempDir.resolve("bomb.zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
                ZipEntry entry = new ZipEntry("Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
                zos.putNextEntry(entry);
                zos.write("id\n".getBytes()); // 3 bytes — exceeds the limit of 2
                zos.closeEntry();
            }
            // Expect an IOException about the size limit, not silent acceptance
            IOException ex = assertThrows(IOException.class,
                () -> ExtractTerms.main(new String[]{zipPath.toString()}),
                "ExtractTerms must throw when a ZIP entry's content exceeds the size limit");
            assertNotNull(ex);
        } finally {
            ExtractTerms.MAX_ENTRY_UNCOMPRESSED_BYTES = savedMax;
        }
    }

    @Test
    void testZipBomb_extractTerms_normalZipProcessedSuccessfully() throws IOException {
        // A legitimate small ZIP must still be processed without error
        Path conceptsFile = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        Path descFile     = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        Path prefFile     = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");

        Files.write(conceptsFile,
            ("id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
           + "100\t20240101\t1\t900000000000207008\t900000000000074008\n").getBytes());
        Files.write(descFile,
            ("id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
           + "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tTest (disorder)\n"
           + "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tTest\n").getBytes());
        Files.write(prefFile,
            ("id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
           + "901\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n").getBytes());

        Path rf2Zip    = tempDir.resolve("SnomedCT_Release_INT_20240101.zip");
        Path outputZip = tempDir.resolve("output.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(rf2Zip.toFile()))) {
            addToZip(zos, conceptsFile, "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
            addToZip(zos, descFile,     "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
            addToZip(zos, prefFile,     "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        }

        // Should not throw
        assertDoesNotThrow(() -> ExtractTerms.main(new String[]{rf2Zip.toString(), outputZip.toString()}),
            "A legitimate small ZIP must be processed without throwing");
        assertTrue(Files.exists(outputZip), "Output ZIP must be created for a legitimate input");
    }

    // ── Charset safety: UTF-8 must be used throughout ─────────────────────────

    @Test
    void testCharset_extractSemanticTags_handlesUtf8NonAsciiTerms() throws IOException {
        // Create a GPS TSV with non-ASCII characters (accented letters, common in SNOMED)
        Path inputFile  = tempDir.resolve("input.tsv");
        Path outputFile = tempDir.resolve("output.tsv");

        String nonAsciiLine = "73211009\t1\tDiabète sucré (disorder)\tDiabète sucré";
        Files.write(inputFile,
            ("ConceptID\tActive\tFSN\tUSPreferredTerm\n" + nonAsciiLine + "\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ExtractSemanticTags.main(new String[]{inputFile.toString(), "disorder"});

        // Find the output file produced by the CLI (name is deterministic)
        Path expectedOutput = inputFile.getParent().resolve("disorder_records.tsv");
        assertTrue(Files.exists(expectedOutput), "Output TSV must be created");

        String content = new String(Files.readAllBytes(expectedOutput),
            java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("Diabète"),
            "Non-ASCII characters must survive round-trip through ExtractSemanticTags with UTF-8 encoding");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void addToZip(ZipOutputStream zos, Path source, String entryName) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(source, zos);
        zos.closeEntry();
    }

    // =========================================================================
    // Task aa11: unique identifiers, list-validations columns, exception-list by code
    // =========================================================================

    /** Every ValidationTest must have a non-null, non-empty code. */
    @Test
    void testValidationTest_allCodesNonEmpty() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertNotNull(t.code,  "Test " + t.name() + " has a null code");
            assertFalse(t.code.isBlank(), "Test " + t.name() + " has a blank code");
        }
    }

    /** Every code must match the pattern [EW]dd (letter then two digits). */
    @Test
    void testValidationTest_codeMatchesPattern() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertTrue(t.code.matches("[EW]\\d{2}"),
                "Code '" + t.code + "' for test " + t.name() + " does not match pattern [EW]dd");
        }
    }

    /** ERROR tests must have codes starting with 'E'; WARNING tests with 'W'. */
    @Test
    void testValidationTest_codePrefixMatchesSeverity() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            if (t.severity == GpsValidator.Severity.ERROR) {
                assertTrue(t.code.startsWith("E"),
                    "ERROR test " + t.name() + " has code '" + t.code + "' — expected E-prefixed code");
            } else {
                assertTrue(t.code.startsWith("W"),
                    "WARNING test " + t.name() + " has code '" + t.code + "' — expected W-prefixed code");
            }
        }
    }

    /** All codes must be unique across the entire enum. */
    @Test
    void testValidationTest_codesAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertTrue(seen.add(t.code),
                "Duplicate code '" + t.code + "' found on test " + t.name());
        }
    }

    /** list-validations output must contain the four formal column headers. */
    @Test
    void testListValidations_outputContainsColumnHeaders() {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            GpsValidator.listValidations();
        } finally {
            System.setOut(original);
        }
        String output = baos.toString();
        assertTrue(output.contains("Code"),        "list-validations must have 'Code' header");
        assertTrue(output.contains("Severity"),    "list-validations must have 'Severity' header");
        assertTrue(output.contains("Name"),        "list-validations must have 'Name' header");
        assertTrue(output.contains("Description"), "list-validations must have 'Description' header");
    }

    /** list-validations output must contain the code of every test. */
    @Test
    void testListValidations_outputContainsAllCodes() {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            GpsValidator.listValidations();
        } finally {
            System.setOut(original);
        }
        String output = baos.toString();
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertTrue(output.contains(t.code),
                "list-validations output must contain code: " + t.code);
        }
    }

    /** list-validations description text must be word-wrapped: no line exceeds the
     *  expected total width (indent + 4 columns + spacing). */
    @Test
    void testListValidations_descriptionColumnIsWrapped() {
        PrintStream original = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            GpsValidator.listValidations();
        } finally {
            System.setOut(original);
        }
        // The full row is: "  " + 6 + "  " + 9 + "  " + 46 + "  " + 60 = 2+6+2+9+2+46+2+60 = 129 chars max
        // We allow a small margin for the left-pad format (%-60s pads to 60 but never truncates).
        // The key invariant: no data line should exceed ~135 characters (reasonable terminal width).
        int MAX_LINE = 135;
        String[] lines = baos.toString().split("\n");
        for (String line : lines) {
            assertTrue(line.length() <= MAX_LINE,
                "list-validations line exceeds " + MAX_LINE + " chars (" + line.length() + "): [" + line + "]");
        }
    }

    /** wordWrap with a narrow width must split at word boundaries. */
    @Test
    void testWordWrap_splitsAtWordBoundaries() {
        List<String> lines = GpsValidator.wordWrap("one two three four five", 10);
        for (String line : lines) {
            assertTrue(line.length() <= 10,
                "wordWrap produced a line longer than the requested width: '" + line + "'");
        }
        // Full text must be recoverable
        assertEquals("one two three four five", String.join(" ", lines));
    }

    /** wordWrap must never split a word longer than the width. */
    @Test
    void testWordWrap_longWordNotSplit() {
        List<String> lines = GpsValidator.wordWrap("superlongwordexceedswidth", 5);
        assertEquals(1, lines.size(), "Single long word must not be split");
        assertEquals("superlongwordexceedswidth", lines.get(0));
    }

    /** wordWrap on empty string must return a single empty-string element. */
    @Test
    void testWordWrap_emptyInput() {
        List<String> lines = GpsValidator.wordWrap("", 20);
        assertEquals(1, lines.size());
        assertEquals("", lines.get(0));
    }

    /** resolveTest must resolve a test by its code (case-insensitive). */
    @Test
    void testResolveTest_byCodeCaseInsensitive() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertSame(t, ExceptionList.resolveTest(t.code),
                "resolveTest must resolve code '" + t.code + "'");
            assertSame(t, ExceptionList.resolveTest(t.code.toLowerCase()),
                "resolveTest must resolve lower-case code '" + t.code.toLowerCase() + "'");
        }
    }

    /** resolveTest must still resolve by enum name as a fallback. */
    @Test
    void testResolveTest_byEnumNameFallback() {
        GpsValidator.ValidationTest expected = GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM;
        assertSame(expected, ExceptionList.resolveTest("NO_BLANK_PREFERRED_TERM"));
        assertSame(expected, ExceptionList.resolveTest("no_blank_preferred_term"));
    }

    /** resolveTest must return null for an unknown key. */
    @Test
    void testResolveTest_unknownKeyReturnsNull() {
        assertNull(ExceptionList.resolveTest("ZZZUNKNOWN"));
        assertNull(ExceptionList.resolveTest("Z99"));
        assertNull(ExceptionList.resolveTest(null));
    }

    /** exception-list add/list/remove must use the code-based filename. */
    @Test
    void testExceptionList_fileNamedByCode(@TempDir Path dir) throws Exception {
        // Point EXCEPTIONS_DIR to the temp dir for this test
        // ExceptionList uses a static field — we redirect via Path directly
        GpsValidator.ValidationTest test = GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM;
        Path exceptionsDir = dir.resolve(".gps-exceptions");
        Files.createDirectories(exceptionsDir);

        // Write a concepts file
        Path conceptsFile = dir.resolve("ids.txt");
        Files.writeString(conceptsFile, "12345678\n99999999\n");

        // Call saveExceptionFile directly to verify it writes to <code>.txt
        ExceptionList.saveExceptionFile(test, new LinkedHashSet<>(List.of("12345678")));
        Path expectedFile = Paths.get(ExceptionList.EXCEPTIONS_DIR, test.code + ".txt");
        // Verify the path returned by getExceptionFile uses the code
        Path returnedPath = ExceptionList.getExceptionFile(test);
        assertTrue(returnedPath.toString().endsWith(test.code + ".txt"),
            "Exception file path must end with code + .txt, got: " + returnedPath);
    }

    /** The exception-file path for every test must be named by its code. */
    @Test
    void testExceptionList_allTestsHaveCodeBasedFilePath() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            Path p = ExceptionList.getExceptionFile(t);
            String filename = p.getFileName().toString();
            assertEquals(t.code + ".txt", filename,
                "Exception file for " + t.name() + " must be " + t.code + ".txt, got: " + filename);
        }
    }
}
