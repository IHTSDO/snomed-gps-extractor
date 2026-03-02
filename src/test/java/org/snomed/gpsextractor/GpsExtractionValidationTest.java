package org.snomed.gpsextractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-reference validation tests: the GPS extraction output is reconciled
 * against the same source RF2 files that produced it.
 *
 * Each test:
 *  1. Runs the full extraction pipeline with a specific combination of flags.
 *  2. Independently re-reads the three source RF2 files via buildExpectedOutput()
 *     to produce a ground-truth map: conceptId → [active, expectedFsn, expectedTerm].
 *  3. Calls validateOutput(), which compares every row of the actual output to
 *     the ground truth and collects ALL mismatches before asserting.
 *
 * This mirrors what a data analyst would do manually after receiving a GPS file:
 *  - Are all the right concepts present? Are any missing or extra?
 *  - Does the count match what the source RF2 says should be there?
 *  - Is the FSN on each row the *actual* active FSN for that concept?
 *  - Is the preferred term the term designated as Preferred in the language refset?
 *  - Are the active flags faithful to the source?
 *
 * The source data covers seven deliberate scenarios (see setUp() for details).
 * GpsValidator.buildExpectedOutput() and GpsValidator.validateOutput() act as
 * the oracle; they are also the engine behind the standalone CLI validate command.
 */
class GpsExtractionValidationTest {

    // SNOMED CT component identifiers
    private static final String FSN_TYPE_ID     = "900000000000003001";
    private static final String SYNONYM_TYPE_ID = "900000000000013009";
    private static final String PREFERRED_ID    = "900000000000548007";
    private static final String ACCEPTABLE_ID   = "900000000000549004";
    private static final String MODULE_ID       = "900000000000207008";
    private static final String PRIMITIVE_ID    = "900000000000074008";
    private static final String REFSET_ID       = "900000000000509007";

    @TempDir
    Path tempDir;

    private Path conceptsFile;
    private Path descriptionsFile;
    private Path preferencesFile;
    private Path outputFile;

    /**
     * Creates seven representative concepts covering all extraction edge cases:
     *
     *  C100  Active — FSN + preferred synonym + acceptable synonym
     *  C200  Active — FSN + one active preferred synonym + one INACTIVE synonym
     *               (the inactive description has an active "Preferred" refset entry,
     *                but must still be excluded because the description itself is inactive)
     *  C300  Active — FSN + synonym that is only 'Acceptable' (never 'Preferred')
     *  C400  Active — FSN + synonym that has NO language-refset entry at all
     *  C500  Inactive, effectiveTime=20200101 (old inactivation — pre-dates any cutoff)
     *  C600  Inactive, effectiveTime=20240601 (recent inactivation)
     *  C700  Active — FSN contains nested parentheses, semantic tag is "procedure"
     */
    @BeforeEach
    void setUp() throws IOException {
        conceptsFile     = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        descriptionsFile = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        preferencesFile  = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        outputFile       = tempDir.resolve("output.tsv");

        // Concept file: id | effectiveTime | active | moduleId | definitionStatusId
        String concepts =
            "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
            "100\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "200\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "300\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "400\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "500\t20200101\t0\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "600\t20240601\t0\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n" +
            "700\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n";
        Files.write(conceptsFile, concepts.getBytes());

        // Description file: id | effectiveTime | active | moduleId | conceptId | languageCode | typeId | term
        String descriptions =
            "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n" +
            // C100: active FSN + active preferred synonym + active acceptable synonym
            "101\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + FSN_TYPE_ID     + "\tDiabetes mellitus (disorder)\n" +
            "102\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + SYNONYM_TYPE_ID + "\tDiabetes\n" +
            "103\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + SYNONYM_TYPE_ID + "\tDM\n" +
            // C200: active FSN + active preferred synonym + INACTIVE synonym with active preferred refset entry
            "201\t20240101\t1\t" + MODULE_ID + "\t200\ten\t" + FSN_TYPE_ID     + "\tEssential hypertension (disorder)\n" +
            "202\t20240101\t1\t" + MODULE_ID + "\t200\ten\t" + SYNONYM_TYPE_ID + "\tEssential hypertension\n" +
            "203\t20240101\t0\t" + MODULE_ID + "\t200\ten\t" + SYNONYM_TYPE_ID + "\tHigh blood pressure\n" + // inactive description
            // C300: active FSN + active synonym that is only acceptable
            "301\t20240101\t1\t" + MODULE_ID + "\t300\ten\t" + FSN_TYPE_ID     + "\tBronchial asthma (disorder)\n" +
            "302\t20240101\t1\t" + MODULE_ID + "\t300\ten\t" + SYNONYM_TYPE_ID + "\tAsthma\n" +
            // C400: active FSN + active synonym with no language-refset entry at all
            "401\t20240101\t1\t" + MODULE_ID + "\t400\ten\t" + FSN_TYPE_ID     + "\tPneumonia (disorder)\n" +
            "402\t20240101\t1\t" + MODULE_ID + "\t400\ten\t" + SYNONYM_TYPE_ID + "\tPneumonia\n" +
            // C500 (inactive concept): descriptions are themselves active
            "501\t20200101\t1\t" + MODULE_ID + "\t500\ten\t" + FSN_TYPE_ID     + "\tOld syndrome (disorder)\n" +
            "502\t20200101\t1\t" + MODULE_ID + "\t500\ten\t" + SYNONYM_TYPE_ID + "\tOld syndrome\n" +
            // C600 (inactive concept): descriptions are themselves active
            "601\t20240601\t1\t" + MODULE_ID + "\t600\ten\t" + FSN_TYPE_ID     + "\tRecent syndrome (disorder)\n" +
            "602\t20240601\t1\t" + MODULE_ID + "\t600\ten\t" + SYNONYM_TYPE_ID + "\tRecent syndrome\n" +
            // C700: FSN with nested parentheses; semantic tag is "procedure"
            "701\t20240101\t1\t" + MODULE_ID + "\t700\ten\t" + FSN_TYPE_ID     + "\tTransplantation of bone marrow (bone marrow transplant) (procedure)\n" +
            "702\t20240101\t1\t" + MODULE_ID + "\t700\ten\t" + SYNONYM_TYPE_ID + "\tBone marrow transplant\n";
        Files.write(descriptionsFile, descriptions.getBytes());

        // Language refset: id | effectiveTime | active | moduleId | refsetId | referencedComponentId | acceptabilityId
        String preferences =
            "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n" +
            // C100: desc 102 preferred, desc 103 acceptable
            "1001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t102\t" + PREFERRED_ID  + "\n" +
            "1002\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t103\t" + ACCEPTABLE_ID + "\n" +
            // C200: desc 202 preferred; desc 203 has an ACTIVE "Preferred" refset entry
            //       but desc 203 itself is inactive — it must therefore be excluded
            "2001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t202\t" + PREFERRED_ID  + "\n" +
            "2002\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t203\t" + PREFERRED_ID  + "\n" +
            // C300: desc 302 is only acceptable — no preferred entry
            "3001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t302\t" + ACCEPTABLE_ID + "\n" +
            // C400: desc 402 has NO refset entry at all (deliberately omitted)
            // C500
            "5001\t20200101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t502\t" + PREFERRED_ID  + "\n" +
            // C600
            "6001\t20240601\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t602\t" + PREFERRED_ID  + "\n" +
            // C700
            "7001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t702\t" + PREFERRED_ID  + "\n";
        Files.write(preferencesFile, preferences.getBytes());
    }

    // =========================================================================
    // Tests
    // =========================================================================

    /**
     * Default extraction (no flags): all 7 concepts must appear, each row
     * carrying the FSN and preferred term that the RF2 source files specify.
     *
     * This is the core reconciliation test — it drives buildExpectedOutput()
     * and validateOutput() against the same data and asserts zero violations.
     */
    @Test
    void testDefaultExtraction_allConceptsReconciledAgainstSource() throws IOException {
        ExtractTerms.main(new String[]{
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);
        List<String> violations = GpsValidator.validateOutput(outputFile, expected);

        assertTrue(violations.isEmpty(),
            "Cross-reference violations found:\n" + String.join("\n", violations));
    }

    /**
     * The number of rows in the output (excluding the header) must exactly equal
     * the number of concepts that the source RF2 file contains.
     */
    @Test
    void testDefaultExtraction_conceptCountMatchesSource() throws IOException {
        ExtractTerms.main(new String[]{
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);

        long actualDataRows = Files.readAllLines(outputFile).stream()
            .skip(1)                    // skip header
            .filter(l -> !l.isEmpty())
            .count();

        assertEquals(expected.size(), actualDataRows,
            "Output row count must equal the number of concepts in source that pass the filter");
    }

    /**
     * Active-only extraction: only the 5 active concepts may appear.
     * The 2 inactive concepts (C500, C600) must be absent from both the
     * expected map and the actual output.
     */
    @Test
    void testActiveOnlyExtraction_reconciledAgainstSource() throws IOException {
        ExtractTerms.main(new String[]{
            "--active-only",
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, true, null);
        List<String> violations = GpsValidator.validateOutput(outputFile, expected);

        assertTrue(violations.isEmpty(),
            "Cross-reference violations found:\n" + String.join("\n", violations));

        // Explicit count guard: 5 active concepts
        assertEquals(5, expected.size(),
            "Active-only extraction must yield exactly the 5 active concepts from the source");
        assertFalse(expected.containsKey("500"), "Inactive C500 must not appear");
        assertFalse(expected.containsKey("600"), "Inactive C600 must not appear");
    }

    /**
     * Inactive-since cutoff of 20230101:
     *  - C500 (effectiveTime=20200101, before cutoff) must be excluded
     *  - C600 (effectiveTime=20240601, on or after cutoff) must be included
     *  - All 5 active concepts must be present
     * Expected total: 6 concepts.
     */
    @Test
    void testInactiveSinceExtraction_reconciledAgainstSource() throws IOException {
        String cutoff = "20230101";
        ExtractTerms.main(new String[]{
            "--inactive-since", cutoff,
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, cutoff);
        List<String> violations = GpsValidator.validateOutput(outputFile, expected);

        assertTrue(violations.isEmpty(),
            "Cross-reference violations found:\n" + String.join("\n", violations));

        assertEquals(6, expected.size(),
            "Inactive-since 20230101 must yield 5 active + 1 recent-inactive concept");
        assertFalse(expected.containsKey("500"),
            "C500 inactivated before the cutoff must be excluded");
        assertTrue(expected.containsKey("600"),
            "C600 inactivated after the cutoff must be included");
    }

    /**
     * ZIP-based extraction must produce output identical to extraction from
     * individual files.  The ZIP entry paths follow the real RF2 naming convention.
     */
    @Test
    void testZipExtraction_reconciledAgainstSource() throws IOException {
        Path zipPath = tempDir.resolve("SnomedCT_Release_INT_20240101.zip");
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new FileOutputStream(zipPath.toFile()))) {
            addToZip(zos, conceptsFile,     "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
            addToZip(zos, descriptionsFile, "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
            addToZip(zos, preferencesFile,  "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        }

        Path zipOutput = tempDir.resolve("zip_output.tsv");
        ExtractTerms.main(new String[]{zipPath.toString(), zipOutput.toString()});

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);
        List<String> violations = GpsValidator.validateOutput(zipOutput, expected);

        assertTrue(violations.isEmpty(),
            "Cross-reference violations from ZIP extraction:\n" + String.join("\n", violations));
    }

    /**
     * No concept ID may appear more than once in the output.
     * This guards against edge cases in HashMap iteration or write logic.
     */
    @Test
    void testNoDuplicateConceptIds() throws IOException {
        ExtractTerms.main(new String[]{
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(outputFile)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String id = line.split("\t")[0];
                if (!seen.add(id)) duplicates.add(id);
            }
        }

        assertTrue(duplicates.isEmpty(),
            "Duplicate concept IDs in output: " + duplicates);
    }

    /**
     * Semantic tag filter pipeline (end-to-end):
     *  1. Extract all concepts to GPS TSV.
     *  2. Filter by tag "disorder" using ExtractSemanticTags.
     *  3. Validate:
     *     - Every row in the filtered output has an FSN ending with "(disorder)".
     *     - All 6 disorder concepts (C100–C600) are present.
     *     - C700 (procedure) is absent.
     *     - The record count in the filtered output is exactly 6.
     */
    @Test
    void testSemanticTagFilter_onlyMatchingTagsPresent() throws IOException {
        // Step 1: full extraction
        ExtractTerms.main(new String[]{
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        // Step 2: filter by "disorder"
        ExtractSemanticTags.main(new String[]{outputFile.toString(), "disorder"});

        Path filteredOutput = outputFile.getParent().resolve("disorder_records.tsv");
        assertTrue(Files.exists(filteredOutput), "Filtered output file must be created");

        // Step 3: parse and validate
        List<String> violations  = new ArrayList<>();
        Set<String>  outputIds   = new LinkedHashSet<>();
        long         dataRowCount = 0;

        try (BufferedReader r = Files.newBufferedReader(filteredOutput)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                dataRowCount++;
                String[] cols = line.split("\t", -1);
                if (cols.length < 3) {
                    violations.add("Row has fewer than 3 columns: " + line);
                    continue;
                }
                String conceptId = cols[0];
                String fsn       = cols[2];
                outputIds.add(conceptId);

                if (!fsn.endsWith("(disorder)")) {
                    violations.add(String.format(
                        "Concept %s: FSN does not end with '(disorder)': '%s'", conceptId, fsn));
                }
            }
        }

        // All 6 disorder concepts must be present
        for (String expectedId : new String[]{"100", "200", "300", "400", "500", "600"}) {
            if (!outputIds.contains(expectedId)) {
                violations.add("Disorder concept " + expectedId + " is missing from filtered output");
            }
        }

        // C700 (procedure) must be absent
        if (outputIds.contains("700")) {
            violations.add("Concept 700 (procedure) must not appear in disorder-filtered output");
        }

        assertTrue(violations.isEmpty(),
            "Semantic tag filter violations:\n" + String.join("\n", violations));
        assertEquals(6, dataRowCount,
            "Filtered output must contain exactly 6 disorder concepts");
    }

    /**
     * Semantic tag filter with activeOnly: after full extraction, filtering by
     * "disorder" with --active-only must exclude inactive concepts C500 and C600.
     * Expected: 4 disorder concepts (C100, C200, C300, C400).
     */
    @Test
    void testSemanticTagFilterWithActiveOnly_excludesInactiveConcepts() throws IOException {
        // Full extraction first
        ExtractTerms.main(new String[]{
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString()
        });

        // Filter by "disorder" with active-only flag
        ExtractSemanticTags.main(new String[]{"--active-only", outputFile.toString(), "disorder"});

        Path filteredOutput = outputFile.getParent().resolve("disorder_records.tsv");
        assertTrue(Files.exists(filteredOutput), "Filtered output file must be created");

        Set<String> outputIds = new LinkedHashSet<>();
        try (BufferedReader r = Files.newBufferedReader(filteredOutput)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                outputIds.add(line.split("\t")[0]);
            }
        }

        // Active disorder concepts must be present
        for (String id : new String[]{"100", "200", "300", "400"}) {
            assertTrue(outputIds.contains(id),
                "Active disorder concept " + id + " must appear in active-only filtered output");
        }
        // Inactive disorder concepts must be absent
        assertFalse(outputIds.contains("500"),
            "Inactive concept C500 must be excluded by --active-only");
        assertFalse(outputIds.contains("600"),
            "Inactive concept C600 must be excluded by --active-only");
        // Procedure concept must be absent
        assertFalse(outputIds.contains("700"),
            "Procedure concept C700 must not match the 'disorder' tag");

        assertEquals(4, outputIds.size(),
            "Active-only disorder filter must yield exactly 4 concepts");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void addToZip(java.util.zip.ZipOutputStream zos, Path source, String entryName)
            throws IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
        Files.copy(source, zos);
        zos.closeEntry();
    }
}
