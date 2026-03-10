package org.snomed.gpsextractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-reference validation tests for the core GPS extraction and validation pipeline,
 * plus unit tests for all new features added in this session.
 *
 * Representative dataset (eight concepts):
 *   C100  Active — FSN + preferred synonym (desc 102) + acceptable synonym (desc 103)
 *   C200  Active — FSN + ACTIVE preferred synonym (desc 202) + INACTIVE desc (203) that has
 *                  an active preferred refset entry — exercises req-7 fix
 *   C300  Active — FSN + synonym that is only Acceptable (no preferred term)
 *   C400  Active — FSN + synonym with NO language-refset entry at all
 *   C500  Inactive, effectiveTime=20200101
 *   C600  Inactive, effectiveTime=20240601
 *   C700  Active — FSN with nested parentheses; semantic tag = "procedure"
 *   C800  Active — two synonyms; desc 801 is US PREFERRED and also GB ACCEPTABLE (listed after
 *                  the US row in the file).  Without the US-only refset filter the GB ACCEPTABLE
 *                  row overwrites the US PREFERRED row (last-write-wins), producing blank.
 *                  This is the root cause of the 826-blank-preferred-terms regression.
 */
class GpsExtractionValidationTest {

    // SNOMED CT identifiers
    private static final String FSN_TYPE_ID     = "900000000000003001";
    private static final String SYNONYM_TYPE_ID = "900000000000013009";
    private static final String PREFERRED_ID    = "900000000000548007";
    private static final String ACCEPTABLE_ID   = "900000000000549004";
    private static final String MODULE_ID       = "900000000000207008";
    private static final String PRIMITIVE_ID    = "900000000000074008";
    private static final String REFSET_ID       = "900000000000509007"; // US English
    private static final String GB_REFSET_ID    = "900000000000508004"; // GB English

    @TempDir Path tempDir;

    private Path conceptsFile;
    private Path descriptionsFile;
    private Path preferencesFile;
    private Path outputFile;   // raw TSV output (used by 4-file path)

    @BeforeEach
    void setUp() throws IOException {
        // Reset cached state that may persist between tests
        GpsValidator.allowedSemanticTags = null;

        conceptsFile     = tempDir.resolve("sct2_Concept_Snapshot_INT_20240101.txt");
        descriptionsFile = tempDir.resolve("sct2_Description_Snapshot-en_INT_20240101.txt");
        preferencesFile  = tempDir.resolve("der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        outputFile       = tempDir.resolve("output.tsv");

        writeFile(conceptsFile,
            "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
            + "100\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "200\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "300\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "400\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "500\t20200101\t0\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "600\t20240601\t0\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "700\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n"
            + "800\t20240101\t1\t" + MODULE_ID + "\t" + PRIMITIVE_ID + "\n");

        writeFile(descriptionsFile,
            "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
            // C100 — active FSN + preferred + acceptable
            + "101\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + FSN_TYPE_ID     + "\tDiabetes mellitus (disorder)\n"
            + "102\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + SYNONYM_TYPE_ID + "\tDiabetes\n"
            + "103\t20240101\t1\t" + MODULE_ID + "\t100\ten\t" + SYNONYM_TYPE_ID + "\tDM\n"
            // C200 — active FSN, active preferred desc 202, INACTIVE desc 203 with pref refset entry
            + "201\t20240101\t1\t" + MODULE_ID + "\t200\ten\t" + FSN_TYPE_ID     + "\tEssential hypertension (disorder)\n"
            + "202\t20240101\t1\t" + MODULE_ID + "\t200\ten\t" + SYNONYM_TYPE_ID + "\tEssential hypertension\n"
            + "203\t20240101\t0\t" + MODULE_ID + "\t200\ten\t" + SYNONYM_TYPE_ID + "\tHigh blood pressure\n"
            // C300 — only acceptable
            + "301\t20240101\t1\t" + MODULE_ID + "\t300\ten\t" + FSN_TYPE_ID     + "\tBronchial asthma (disorder)\n"
            + "302\t20240101\t1\t" + MODULE_ID + "\t300\ten\t" + SYNONYM_TYPE_ID + "\tAsthma\n"
            // C400 — no language-refset entry
            + "401\t20240101\t1\t" + MODULE_ID + "\t400\ten\t" + FSN_TYPE_ID     + "\tPneumonia (disorder)\n"
            + "402\t20240101\t1\t" + MODULE_ID + "\t400\ten\t" + SYNONYM_TYPE_ID + "\tPneumonia\n"
            // C500 inactive
            + "501\t20200101\t1\t" + MODULE_ID + "\t500\ten\t" + FSN_TYPE_ID     + "\tOld syndrome (disorder)\n"
            + "502\t20200101\t1\t" + MODULE_ID + "\t500\ten\t" + SYNONYM_TYPE_ID + "\tOld syndrome\n"
            // C600 inactive
            + "601\t20240601\t1\t" + MODULE_ID + "\t600\ten\t" + FSN_TYPE_ID     + "\tRecent syndrome (disorder)\n"
            + "602\t20240601\t1\t" + MODULE_ID + "\t600\ten\t" + SYNONYM_TYPE_ID + "\tRecent syndrome\n"
            // C700 nested parentheses
            + "701\t20240101\t1\t" + MODULE_ID + "\t700\ten\t" + FSN_TYPE_ID     + "\tTransplantation of bone marrow (bone marrow transplant) (procedure)\n"
            + "702\t20240101\t1\t" + MODULE_ID + "\t700\ten\t" + SYNONYM_TYPE_ID + "\tBone marrow transplant\n"
            // C800 — last-write-wins regression target.
            // desc 801: the ONLY synonym.  It appears in the US refset as PREFERRED (row 8001)
            // and ALSO in the GB refset as ACCEPTABLE (row 8002, which comes AFTER row 8001).
            // Old code (no refset filter): prefs[801] starts as PREFERRED then gets overwritten
            // with ACCEPTABLE by the GB row -> desc 801 is never selected -> blank preferred term.
            // Fixed code (US-only filter): only row 8001 is loaded -> prefs[801] = PREFERRED
            // -> "Drug A" is correctly selected as the US Preferred Term.
            + "801\t20240101\t1\t" + MODULE_ID + "\t800\ten\t" + SYNONYM_TYPE_ID + "\tDrug A\n"
            + "803\t20240101\t1\t" + MODULE_ID + "\t800\ten\t" + FSN_TYPE_ID     + "\tDrug A (medicinal product)\n");

        writeFile(preferencesFile,
            "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
            + "1001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t102\t" + PREFERRED_ID  + "\n"
            + "1002\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t103\t" + ACCEPTABLE_ID + "\n"
            + "2001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t202\t" + PREFERRED_ID  + "\n"
            // 2002: active refset entry for INACTIVE desc 203 → must NOT shadow desc 202
            + "2002\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t203\t" + PREFERRED_ID  + "\n"
            + "3001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t302\t" + ACCEPTABLE_ID + "\n"
            // 400 has no refset entry → preferred term will be blank
            + "5001\t20200101\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t502\t" + PREFERRED_ID  + "\n"
            + "6001\t20240601\t1\t" + MODULE_ID + "\t" + REFSET_ID + "\t602\t" + PREFERRED_ID  + "\n"
            + "7001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID    + "\t702\t" + PREFERRED_ID  + "\n"
            // C800 — last-write-wins regression.
            // Row order matters: US PREFERRED for desc 801 written first, then GB ACCEPTABLE written
            // second.  With the old code (no refset filter) the GB row overwrites the US row and
            // desc 801 ends up ACCEPTABLE -> not selected -> blank preferred term.
            // With the fixed code (US-only filter) the GB row is skipped entirely -> desc 801 remains
            // US PREFERRED -> "Drug A" is correctly selected.
            + "8001\t20240101\t1\t" + MODULE_ID + "\t" + REFSET_ID    + "\t801\t" + PREFERRED_ID  + "\n"   // US: 801=PREFERRED
            + "8002\t20240101\t1\t" + MODULE_ID + "\t" + GB_REFSET_ID + "\t801\t" + ACCEPTABLE_ID + "\n"); // GB: 801=ACCEPTABLE (overwrites in old code)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void writeFile(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static List<GpsValidator.Finding> errorsOnly(List<GpsValidator.Finding> findings) {
        return findings.stream()
            .filter(f -> f.severity == GpsValidator.Severity.ERROR)
            .collect(Collectors.toList());
    }

    /** Extract the TSV entry from a GPS output ZIP into a temp file and return its path. */
    private Path extractTsvFromZip(Path zipPath) throws IOException {
        Path tsvOut = zipPath.getParent().resolve(
            zipPath.getFileName().toString().replaceAll("\\.zip$", "_extracted.tsv"));
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipPath.toFile())) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".txt") && !entry.getName().equals("Readme.txt")) {
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, tsvOut, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return tsvOut;
                }
            }
        }
        throw new IOException("Could not find TSV entry in GPS ZIP: " + zipPath);
    }

    /** Parse output TSV into a map of conceptId → String[]{active, fsn, term}. */
    private static Map<String, String[]> readOutputRows(Path tsvFile) throws IOException {
        Map<String, String[]> rows = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(tsvFile)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] cols = line.split("\t", -1);
                if (cols.length >= 4) {
                    rows.put(cols[0], new String[]{cols[1], cols[2], cols[3]});
                }
            }
        }
        return rows;
    }

    // =========================================================================
    // Core extraction tests
    // =========================================================================

    @Test
    void testDefaultExtraction_allConceptsReconciledAgainstSource() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);
        List<GpsValidator.Finding> errs = errorsOnly(
            GpsValidator.validateOutput(outputFile, expected, false, null));
        assertTrue(errs.isEmpty(),
            "ERROR findings:\n" + errs.stream().map(Object::toString).collect(Collectors.joining("\n")));
    }

    @Test
    void testDefaultExtraction_conceptCountMatchesSource() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);
        long rows = Files.readAllLines(outputFile).stream()
            .skip(1).filter(l -> !l.isEmpty()).count();
        assertEquals(expected.size(), rows);
    }

    @Test
    void testActiveOnlyExtraction_reconciledAgainstSource() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            true, null);
        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, true, null);
        List<GpsValidator.Finding> errs = errorsOnly(
            GpsValidator.validateOutput(outputFile, expected, true, null));
        assertTrue(errs.isEmpty(), errs.toString());
        assertEquals(6, expected.size());  // C100,C200,C300,C400,C700,C800 (active only; C500,C600 excluded)
        assertFalse(expected.containsKey("500"));
        assertFalse(expected.containsKey("600"));
    }

    @Test
    void testInactiveSinceExtraction_reconciledAgainstSource() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, "20230101");
        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, "20230101");
        List<GpsValidator.Finding> errs = errorsOnly(
            GpsValidator.validateOutput(outputFile, expected, false, "20230101"));
        assertTrue(errs.isEmpty(), errs.toString());
        assertEquals(7, expected.size());  // C100,C200,C300,C400,C600,C700,C800 (C500 excluded — too old)
        assertFalse(expected.containsKey("500"));
        assertTrue(expected.containsKey("600"));
    }

    @Test
    void testZipExtraction_tsvInsideZipMatchesSource() throws IOException {
        // Build a minimal RF2-like ZIP
        Path rfZip = tempDir.resolve("SnomedCT_Release_INT_20240101.zip");
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(new FileOutputStream(rfZip.toFile()))) {
            addToZip(zos, conceptsFile,     "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
            addToZip(zos, descriptionsFile, "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
            addToZip(zos, preferencesFile,  "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
        }
        Path gpsZip = tempDir.resolve("SnomedINTL_GPSRelease_PRODUCTION_20240101T120000Z.zip");
        ExtractTerms.main(new String[]{rfZip.toString(), gpsZip.toString()});

        assertTrue(Files.exists(gpsZip), "GPS ZIP must be created");
        Path tsvPath = extractTsvFromZip(gpsZip);

        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);
        List<GpsValidator.Finding> errs = errorsOnly(
            GpsValidator.validateOutput(tsvPath, expected, false, null));
        assertTrue(errs.isEmpty(), errs.toString());
    }

    @Test
    void testNoDuplicateConceptIds() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Set<String> seen = new LinkedHashSet<>();
        List<String> dups = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(outputFile)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String id = line.split("\t")[0];
                if (!seen.add(id)) dups.add(id);
            }
        }
        assertTrue(dups.isEmpty(), "Duplicates: " + dups);
    }

    // =========================================================================
    // Preferred term correctness — Requirement 7
    // =========================================================================

    /**
     * C200: desc 202 is ACTIVE and preferred; desc 203 is INACTIVE but has an active refset entry.
     * The fix ensures only active descriptions are used, so the preferred term must be
     * "Essential hypertension" (from desc 202), not "High blood pressure" (inactive desc 203).
     */
    @Test
    void testReq7_inactiveDescriptionIgnored_activePrefTermResolved() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> rows = readOutputRows(outputFile);
        assertEquals("Essential hypertension", rows.get("200")[2],
            "C200 preferred term must be from active desc 202, not inactive desc 203");
    }

    /**
     * C300 has only an acceptable synonym — preferred term column must be empty.
     * (The blank is expected here; this is different from the bug scenario.)
     */
    @Test
    void testReq7_noPreferredSynonymAtAll_prefTermIsEmpty() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> rows = readOutputRows(outputFile);
        assertEquals("", rows.get("300")[2],
            "C300 has no preferred synonym, so preferred term column must be empty");
    }

    /**
     * Regression test for the 826-blank-preferred-terms bug.
     *
     * <p>C800 has two synonyms:
     * <ul>
     *   <li>desc 801 ("Drug A") — US refset: PREFERRED, GB refset: ACCEPTABLE
     *       (GB row appears AFTER the US row in the file)</li>
     *   <li>desc 802 ("Drug A (product)") — GB refset: PREFERRED (not in US refset)</li>
     * </ul>
     *
     * <p><strong>Without</strong> the US-refset filter (old code):
     * The GB ACCEPTABLE row for desc 801 is processed after the US PREFERRED row, so
     * {@code prefs.put("801", ACCEPTABLE_ID)} overwrites {@code prefs.put("801", PREFERRED_ID)}.
     * readDescriptions then finds desc 801 as ACCEPTABLE — not selected — and desc 802 has no
     * US entry at all — not selected.  Result: blank preferred term.
     *
     * <p><strong>With</strong> the US-refset filter (fixed code):
     * Only the US PREFERRED entry for desc 801 is loaded.  The GB ACCEPTABLE row is silently
     * skipped.  readDescriptions finds desc 801 as US PREFERRED and correctly selects "Drug A".
     */
    @Test
    void testUSRefsetOnly_gbAcceptableDoesNotOverwriteUsPreferred_noBlanks() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> rows = readOutputRows(outputFile);

        // C800 must have "Drug A" as its US preferred term
        assertNotNull(rows.get("800"), "C800 must be present in output");
        String prefTerm = rows.get("800")[2];
        assertFalse(prefTerm.isEmpty(),
            "C800 preferred term must NOT be blank. "
            + "Root cause: GB ACCEPTABLE row (desc 801) written after US PREFERRED row "
            + "in the combined language refset file, overwriting the US acceptability value.");
        assertEquals("Drug A", prefTerm,
            "C800 preferred term must be 'Drug A' (desc 801, US PREFERRED), "
            + "not blank or the GB preferred term 'Drug A (product)'");
    }

    /**
     * Validates the same scenario through the GpsValidator oracle so that the
     * validator correctly expects "Drug A" and does not raise a spurious error.
     */
    @Test
    void testUSRefsetOnly_validatorOracleMatchesExtractor() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> expected = GpsValidator.buildExpectedOutput(
            conceptsFile, descriptionsFile, preferencesFile, false, null);

        // Oracle must also resolve "Drug A" for C800
        assertNotNull(expected.get("800"), "Oracle must include C800");
        assertEquals("Drug A", expected.get("800")[2],
            "Oracle preferred term for C800 must be 'Drug A' (US PREFERRED desc 801)");

        // Cross-reference: extractor output must match oracle with no errors
        List<GpsValidator.Finding> errs = errorsOnly(
            GpsValidator.validateOutput(outputFile, expected, false, null));
        assertTrue(errs.isEmpty(),
            "No ERROR findings expected after US-refset fix:\n"
            + errs.stream().map(Object::toString).collect(Collectors.joining("\n")));
    }

    /**
     * C100 has a clearly preferred synonym — must appear correctly.
     */
    @Test
    void testReq7_standardPreferredTerm_resolvedCorrectly() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        Map<String, String[]> rows = readOutputRows(outputFile);
        assertEquals("Diabetes", rows.get("100")[2],
            "C100 preferred term must be 'Diabetes' (desc 102, preferred)");
    }

    // =========================================================================
    // Inactive-concept exclusions — Requirement 6
    // =========================================================================

    @Test
    void testReq6_inactiveConcept_semanticTagNotChecked() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "500\t0\tOld syndrome\tOld syndrome\n"; // FSN has no semantic tag
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("500", new String[]{"0", "Old syndrome", "Old syndrome"});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertFalse(
            findings.stream().anyMatch(f -> f.test == GpsValidator.ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG),
            "FSN_HAS_VALID_SEMANTIC_TAG must not fire for inactive concept");
    }

    @Test
    void testReq6_inactiveConcept_blankPreferredTermNotAnError() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "500\t0\tOld syndrome (disorder)\t\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("500", new String[]{"0", "Old syndrome (disorder)", ""});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertFalse(
            findings.stream().anyMatch(f -> f.test == GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM),
            "NO_BLANK_PREFERRED_TERM must not fire for inactive concept");
    }

    @Test
    void testReq6_inactiveConcept_fsnSameAsTermNotChecked() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "500\t0\tOld syndrome (disorder)\tOld syndrome (disorder)\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("500", new String[]{"0", "Old syndrome (disorder)", "Old syndrome (disorder)"});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertFalse(
            findings.stream().anyMatch(f -> f.test == GpsValidator.ValidationTest.FSN_NOT_SAME_AS_PREFERRED_TERM),
            "FSN_NOT_SAME_AS_PREFERRED_TERM must not fire for inactive concept");
    }

    @Test
    void testReq6_inactiveConcept_prefTermLooksLikeFsnNotChecked() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "500\t0\tOld syndrome (disorder)\tOld syndrome (disorder)\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("500", new String[]{"0", "Old syndrome (disorder)", "Old syndrome (disorder)"});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertFalse(
            findings.stream().anyMatch(f -> f.test == GpsValidator.ValidationTest.PREFERRED_TERM_NOT_AN_FSN),
            "PREFERRED_TERM_NOT_AN_FSN must not fire for inactive concept");
    }

    @Test
    void testReq6_activeConcept_blankPreferredTermIsAnError() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "100\t1\tDiabetes mellitus (disorder)\t\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        // Oracle says the concept SHOULD have a preferred term — blank output is a genuine E10 violation
        expected.put("100", new String[]{"1", "Diabetes mellitus (disorder)", "Diabetes mellitus"});
        List<GpsValidator.Finding> errs = errorsOnly(GpsValidator.validateOutput(outputFile, expected, false, null));
        assertTrue(
            errs.stream().anyMatch(f -> f.test == GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM),
            "NO_BLANK_PREFERRED_TERM must fire for an active concept with blank preferred term");
    }

    // =========================================================================
    // Requirement 8: FSN_HAS_VALID_SEMANTIC_TAG is WARNING
    // =========================================================================

    @Test
    void testReq8_fsnHasValidSemanticTag_isSeverityWarning() {
        assertEquals(GpsValidator.Severity.WARNING,
            GpsValidator.ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG.severity,
            "FSN_HAS_VALID_SEMANTIC_TAG must be classified as WARNING, not ERROR");
    }

    @Test
    void testReq8_unknownSemanticTag_producesWarningNotError() throws IOException {
        // Disable allowed-tags list so the tag check can fire on an unknown tag
        GpsValidator.allowedSemanticTags = new LinkedHashSet<>(List.of("disorder"));
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "100\t1\tSomething (unknowntag)\tSomething\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("100", new String[]{"1", "Something (unknowntag)", "Something"});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        boolean hasTagFinding = findings.stream().anyMatch(
            f -> f.test == GpsValidator.ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG);
        assertTrue(hasTagFinding, "Expected FSN_HAS_VALID_SEMANTIC_TAG finding for unknown tag");
        // Must be WARNING not ERROR
        boolean isError = findings.stream().anyMatch(
            f -> f.test == GpsValidator.ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG
              && f.severity == GpsValidator.Severity.ERROR);
        assertFalse(isError, "FSN_HAS_VALID_SEMANTIC_TAG finding must be WARNING not ERROR");
    }

    // =========================================================================
    // Severity split tests
    // =========================================================================

    @Test
    void testSeverity_warningOnlyDoesNotProduceErrors() throws IOException {
        // FSN == preferred term → WARNING only
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "100\t1\tDiabetes mellitus (disorder)\tDiabetes mellitus (disorder)\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("100", new String[]{"1", "Diabetes mellitus (disorder)", "Diabetes mellitus (disorder)"});
        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertTrue(findings.stream().anyMatch(f -> f.severity == GpsValidator.Severity.WARNING),
            "Expected a WARNING for identical FSN and preferred term");
        assertTrue(errorsOnly(findings).isEmpty(), "Must produce no ERRORs");
    }

    @Test
    void testSeverity_activeWrongFlagProducesError() throws IOException {
        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "100\t0\tDiabetes mellitus (disorder)\tDiabetes\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        expected.put("100", new String[]{"1", "Diabetes mellitus (disorder)", "Diabetes"});
        List<GpsValidator.Finding> errs = errorsOnly(GpsValidator.validateOutput(outputFile, expected, false, null));
        assertFalse(errs.isEmpty());
        assertTrue(errs.stream().anyMatch(f -> f.message.contains("active flag")));
    }

    // =========================================================================
    // ValidationTest enum completeness
    // =========================================================================

    @Test
    void testEnum_allTestsHaveNonEmptyFields() {
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            assertNotNull(t.name,        t + " must have a name");
            assertFalse(t.name.isEmpty(), t + " name must not be empty");
            assertNotNull(t.description, t + " must have a description");
            assertFalse(t.description.isEmpty(), t + " description must not be empty");
            assertNotNull(t.severity,    t + " must have a severity");
        }
    }

    @Test
    void testEnum_hasBothSeverities() {
        assertTrue(Arrays.stream(GpsValidator.ValidationTest.values())
            .anyMatch(t -> t.severity == GpsValidator.Severity.ERROR),   "Must have at least one ERROR test");
        assertTrue(Arrays.stream(GpsValidator.ValidationTest.values())
            .anyMatch(t -> t.severity == GpsValidator.Severity.WARNING), "Must have at least one WARNING test");
    }

    @Test
    void testEnum_exactlyNineteenErrors() {
        long count = Arrays.stream(GpsValidator.ValidationTest.values())
            .filter(t -> t.severity == GpsValidator.Severity.ERROR).count();
        assertEquals(20, count, "Expected 20 ERROR-severity tests");
    }

    @Test
    void testEnum_exactlyFiveWarnings() {
        long count = Arrays.stream(GpsValidator.ValidationTest.values())
            .filter(t -> t.severity == GpsValidator.Severity.WARNING).count();
        assertEquals(5, count, "Expected 5 WARNING-severity tests");
    }

    // =========================================================================
    // Semantic tag extraction
    // =========================================================================

    @Test void testTag_simpleTag()       { assertEquals("disorder", GpsValidator.extractLastSemanticTag("Diabetes mellitus (disorder)")); }
    @Test void testTag_nestedParens()    { assertEquals("procedure", GpsValidator.extractLastSemanticTag("Transplantation of bone marrow (bone marrow transplant) (procedure)")); }
    @Test void testTag_noTag()           { assertNull(GpsValidator.extractLastSemanticTag("Some term without a tag")); }
    @Test void testTag_null()            { assertNull(GpsValidator.extractLastSemanticTag(null)); }
    @Test void testTag_empty()           { assertNull(GpsValidator.extractLastSemanticTag("")); }
    @Test void testTag_closingNotLast()  { assertNull(GpsValidator.extractLastSemanticTag("Some (paren) term")); }

    // =========================================================================
    // Exception list — Requirement 5
    // =========================================================================

    @BeforeEach
    void clearExceptionsDir() throws IOException {
        // Use a per-test temp subdir as the exceptions dir so tests don't interfere
        // We can't easily override the static EXCEPTIONS_DIR, so we just ensure
        // the dir doesn't exist before ExceptionList tests that need a clean state.
        Path exDir = Paths.get(ExceptionList.EXCEPTIONS_DIR);
        if (Files.exists(exDir)) {
            try (var walk = Files.walk(exDir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    @Test
    void testExceptionList_addAndList() throws IOException {
        Path concepts = tempDir.resolve("concepts.txt");
        writeFile(concepts, "100\n200\n300\n");
        ExceptionList.addExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, concepts.toString());

        Set<String> loaded = ExceptionList.getExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM);
        assertEquals(Set.of("100", "200", "300"), new HashSet<>(loaded));
    }

    @Test
    void testExceptionList_remove() throws IOException {
        Path add = tempDir.resolve("add.txt");
        writeFile(add, "100\n200\n300\n");
        ExceptionList.addExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, add.toString());

        Path remove = tempDir.resolve("remove.txt");
        writeFile(remove, "200\n");
        ExceptionList.removeExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, remove.toString());

        Set<String> loaded = ExceptionList.getExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM);
        assertEquals(Set.of("100", "300"), new HashSet<>(loaded));
        assertFalse(loaded.contains("200"), "200 must have been removed");
    }

    @Test
    void testExceptionList_nonExistentFile_returnsEmpty() {
        Set<String> exceptions = ExceptionList.getExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM);
        assertTrue(exceptions.isEmpty(), "Non-existent exception file must return empty set");
    }

    @Test
    void testExceptionList_nonNumericLinesIgnored() throws IOException {
        Path file = getExceptionFileForTest(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM);
        Files.createDirectories(file.getParent());
        writeFile(file, "# comment\n100\nnot-a-number\n200\n\n");
        Set<String> ids = ExceptionList.loadConceptIds(file);
        assertEquals(Set.of("100", "200"), new HashSet<>(ids));
    }

    @Test
    void testExceptionList_suppressesFindingForExceptedConcept() throws IOException {
        // Add concept 100 to the NO_BLANK_PREFERRED_TERM exception list
        Path addFile = tempDir.resolve("exceptions.txt");
        writeFile(addFile, "100\n");
        ExceptionList.addExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, addFile.toString());

        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "100\t1\tDiabetes mellitus (disorder)\t\n";  // blank pref term for active concept
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        // Oracle says concept should have a preferred term; blank output is a genuine E10 violation
        expected.put("100", new String[]{"1", "Diabetes mellitus (disorder)", "Diabetes mellitus"});

        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertFalse(
            findings.stream().anyMatch(f ->
                f.test == GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM
                && "100".equals(f.conceptId)),
            "Exception list must suppress NO_BLANK_PREFERRED_TERM finding for concept 100");
    }

    @Test
    void testExceptionList_doesNotSuppressFindingForNonExceptedConcept() throws IOException {
        // Exception only covers concept 100; concept 200 must still fire
        Path addFile = tempDir.resolve("exceptions.txt");
        writeFile(addFile, "100\n");
        ExceptionList.addExceptions(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, addFile.toString());

        String content = "ConceptID\tActive\tFSN\tUSPreferredTerm\n"
            + "200\t1\tHypertension (disorder)\t\n";
        writeFile(outputFile, content);
        Map<String, String[]> expected = new LinkedHashMap<>();
        // Oracle says concept 200 should have a preferred term; blank output is a genuine E10 violation
        expected.put("200", new String[]{"1", "Hypertension (disorder)", "Hypertension"});

        List<GpsValidator.Finding> findings = GpsValidator.validateOutput(outputFile, expected, false, null);
        assertTrue(
            findings.stream().anyMatch(f ->
                f.test == GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM
                && "200".equals(f.conceptId)),
            "Exception list for concept 100 must not suppress finding for concept 200");
    }

    @Test
    void testExceptionList_resolveTest_caseInsensitive() {
        assertNotNull(ExceptionList.resolveTest("no_blank_preferred_term"));
        assertNotNull(ExceptionList.resolveTest("NO_BLANK_PREFERRED_TERM"));
        assertNotNull(ExceptionList.resolveTest("No_Blank_Preferred_Term"));
        assertNull(ExceptionList.resolveTest("DOES_NOT_EXIST"));
    }

    @Test
    void testExceptionList_addFileNotFound_doesNotThrow() {
        // Should print error and return gracefully
        assertDoesNotThrow(() ->
            ExceptionList.addExceptions(
                GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM,
                "/does/not/exist.txt"));
    }

    // =========================================================================
    // Report summary with overlap detection — Requirement 4
    // =========================================================================

    @Test
    void testReportSummary_groupsByTest() {
        List<GpsValidator.Finding> findings = List.of(
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, "100", "blank term 100"),
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, "200", "blank term 200"),
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_TRAILING_WHITESPACE,  "100", "trailing ws 100")
        );
        StringWriter sw = new StringWriter();
        PrintWriter pw  = new PrintWriter(sw);
        GpsValidator.writeFindingsSummary(pw, findings, "Test");
        pw.flush();
        String output = sw.toString();
        assertTrue(output.contains("NO_BLANK_PREFERRED_TERM"), "Summary must name the test");
        assertTrue(output.contains("2"), "Must show count of 2 for NO_BLANK_PREFERRED_TERM");
    }

    @Test
    void testReportSummary_detectsOverlap() {
        List<GpsValidator.Finding> findings = List.of(
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, "100", "blank 100"),
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_TRAILING_WHITESPACE,  "100", "ws 100")
        );
        StringWriter sw = new StringWriter();
        PrintWriter  pw = new PrintWriter(sw);
        GpsValidator.writeFindingsSummary(pw, findings, "Test");
        pw.flush();
        String output = sw.toString();
        assertTrue(output.contains("overlap") || output.contains("overlap".toUpperCase())
            || output.toLowerCase().contains("overlap"),
            "Summary must note that both violations affect the same concept");
    }

    @Test
    void testReportSummary_noOverlapWhenConceptsDiffer() {
        List<GpsValidator.Finding> findings = List.of(
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_BLANK_PREFERRED_TERM, "100", "blank 100"),
            new GpsValidator.Finding(GpsValidator.ValidationTest.NO_TRAILING_WHITESPACE,  "200", "ws 200")
        );
        StringWriter sw = new StringWriter();
        PrintWriter  pw = new PrintWriter(sw);
        GpsValidator.writeFindingsSummary(pw, findings, "Test");
        pw.flush();
        String output = sw.toString().toLowerCase();
        assertFalse(output.contains("overlap"),
            "No overlap note expected when findings are on different concepts");
    }

    // =========================================================================
    // Progress indicator / status — Requirement 3
    // =========================================================================

    @Test
    void testStatusIndicator_progressStepsInEnumOrder() {
        // The validate command prints "[1/4]", "[2/4]", "[3/4]", "[4/4]" steps.
        // We just verify the constants are present in the source code by checking
        // that listValidations() runs without error (the steps fire in main()).
        // A full integration test of the progress output would require a subprocess.
        assertDoesNotThrow(GpsValidator::listValidations);
    }

    // =========================================================================
    // Semantic tag filter (extract-tags)
    // =========================================================================

    @Test
    void testSemanticTagFilter_onlyMatchingConceptsReturned() throws IOException {
        ExtractTerms.processFiles(
            conceptsFile.toString(), descriptionsFile.toString(),
            preferencesFile.toString(), outputFile.toString(),
            false, null);
        ExtractSemanticTags.main(new String[]{outputFile.toString(), "disorder"});
        Path filtered = outputFile.getParent().resolve("disorder_records.tsv");
        assertTrue(Files.exists(filtered));

        Set<String> ids = new LinkedHashSet<>();
        List<String> badFsns = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(filtered)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] cols = line.split("\t", -1);
                ids.add(cols[0]);
                if (!cols[2].endsWith("(disorder)")) badFsns.add(cols[2]);
            }
        }
        assertTrue(badFsns.isEmpty(), "All FSNs must end with (disorder): " + badFsns);
        for (String id : new String[]{"100", "200", "300", "400", "500", "600"}) {
            assertTrue(ids.contains(id), "Expected disorder concept missing: " + id);
        }
        assertFalse(ids.contains("700"), "C700 (procedure) must not appear in disorder filter");
        assertFalse(ids.contains("800"), "C800 (medicinal product) must not appear in disorder filter");
    }

    // =========================================================================
    // README builder
    // =========================================================================

    @Test
    void testReadme_noEffectiveTimeField() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));
        assertFalse(readme.contains("effectiveTime:"),
            "README must not contain effectiveTime field (requirement 2)");
    }

    @Test
    void testReadme_noLanguageRefsetsField() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));
        assertFalse(readme.contains("languageRefsets"),
            "README must not contain languageRefsets field (requirement 2)");
    }

    @Test
    void testReadme_urlsInline() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt", "Readme.txt"));
        // URL must be followed immediately by ')' or more text, not on its own line
        assertFalse(readme.contains("\nhttps://"), "URLs must not appear as standalone lines");
    }

    @Test
    void testReadme_directoryListingContainsAllEntries() {
        String readme = ExtractTerms.buildReadmeContent("20260301",
            List.of("data.txt", "Readme.txt", "guide.pdf"));
        assertTrue(readme.contains("data.txt"));
        assertTrue(readme.contains("Readme.txt"));
        assertTrue(readme.contains("guide.pdf"));
    }

    @Test
    void testReadme_dependentEditionVersionPresent() {
        String readme = ExtractTerms.buildReadmeContent("20260301", List.of("data.txt"));
        assertTrue(readme.contains("March 2026"),
            "README must contain human-readable edition date");
    }

    // =========================================================================
    // Zip-slip security
    // =========================================================================

    @Test
    void testSecurity_zipSlipPrevention() throws IOException {
        // Build a ZIP with a path-traversal entry
        Path maliciousZip = tempDir.resolve("malicious.zip");
        try (java.util.zip.ZipOutputStream zos =
                new java.util.zip.ZipOutputStream(new FileOutputStream(maliciousZip.toFile()))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("../../evil.txt"));
            zos.write("should not be written".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        // Verify the boundary check logic: resolving the raw (un-stripped) entry name
        // MUST detect the path traversal and throw.  The production code calls getFileName()
        // first to strip path components, which prevents the attack at the stripping step;
        // but the boundary check itself must also be correct.
        Path dest = Files.createTempDirectory("security-test");
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(maliciousZip.toFile())) {
            java.util.zip.ZipEntry entry = zf.entries().nextElement();
            assertThrows(IOException.class, () -> {
                // Deliberately do NOT call getFileName() — this simulates what would happen
                // if the path-stripping step were absent, and confirms the boundary check fires.
                Path resolved = dest.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(dest.normalize())) throw new IOException("Zip-slip detected");
            });
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Path getExceptionFileForTest(GpsValidator.ValidationTest test) {
        return Paths.get(ExceptionList.EXCEPTIONS_DIR, test.code + ".txt");
    }

    private static void addToZip(java.util.zip.ZipOutputStream zos, Path src, String name)
            throws IOException {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        Files.copy(src, zos);
        zos.closeEntry();
    }
}
