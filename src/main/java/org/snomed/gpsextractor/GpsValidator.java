package org.snomed.gpsextractor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Standalone GPS extraction validator.
 *
 * Given an RF2 release ZIP and a GPS output TSV, this class independently
 * re-reads the three required RF2 files to build a ground-truth oracle, then
 * cross-references every row of the GPS output against it.
 *
 * CLI usage (via Main.java):
 *   java -jar snomed-gps-extractor.jar validate [--active-only] [--inactive-since YYYYMMDD]
 *            <rf2-zip> <gps-output-tsv> <report-file>
 *
 *   java -jar snomed-gps-extractor.jar list-validations
 */
public class GpsValidator {

    // SNOMED CT component identifiers
    private static final String FSN_TYPE_ID          = "900000000000003001";
    private static final String SYNONYM_TYPE_ID      = "900000000000013009";
    private static final String PREFERRED_ID         = "900000000000548007";

    /**
     * US English language refset ID.  The combined INT language refset file
     * contains both GB (900000000000508004) and US (900000000000509007) entries.
     * A description can appear in both with different acceptability values.
     * We filter strictly to US English to prevent GB entries from corrupting
     * the US acceptability lookup and producing blank preferred terms.
     */
    private static final String US_ENGLISH_REFSET_ID = "900000000000509007";

    /**
     * GB English language refset ID.  Used by the US_PREFERRED_TERM_NOT_GB
     * validation to detect cases where the output contains the GB preferred term
     * rather than the US preferred term.
     */
    static final String GB_ENGLISH_REFSET_ID = "900000000000508004";

    /** Severity levels used to classify each validation finding. */
    public enum Severity { ERROR, WARNING }

    /** A single validation finding: the test that triggered it, the concept ID (may be null
     *  for file-level checks), and a human-readable message. */
    public static class Finding {
        public final Severity       severity;
        public final ValidationTest test;
        public final String         conceptId; // null for file-level findings
        public final String         message;

        public Finding(ValidationTest test, String conceptId, String message) {
            this.severity  = test.severity;
            this.test      = test;
            this.conceptId = conceptId;
            this.message   = message;
        }

        /** Convenience constructor for file-level findings with no concept ID. */
        public Finding(ValidationTest test, String message) {
            this(test, null, message);
        }

        @Override public String toString() { return "[" + severity + "] " + message; }
    }

    /**
     * Catalogue of all validation tests: stable unique code, severity, human-readable name,
     * and full description.
     *
     * Each constant carries a permanent {@code code} string (e.g. {@code "E01"}, {@code "W03"})
     * that is used everywhere a test must be identified by users — exception-list filenames,
     * CLI arguments, report columns, and the {@code list-validations} output.  Codes are stable:
     * adding a new test never renumbers existing ones.
     *
     * Severity assignments:
     *   - All structural / data-correctness checks → ERROR
     *   - FSN tag validity, FSN==term, preferred-term-looks-like-FSN,
     *     trailing whitespace → WARNING
     */
    public enum ValidationTest {
        // ── ERROR tests ───────────────────────────────────────────────────────
        HEADER_CORRECT(
            "E01", Severity.ERROR,
            "Correct output file header",
            "Output file header must be exactly: ConceptID<TAB>Active<TAB>FSN<TAB>USPreferredTerm"),
        FOUR_COLUMNS(
            "E02", Severity.ERROR,
            "Four tab-separated columns per row",
            "Every data row must have exactly 4 tab-separated columns"),
        NO_DUPLICATE_IDS(
            "E03", Severity.ERROR,
            "No duplicate concept IDs",
            "No concept ID may appear more than once in the output"),
        ALL_EXPECTED_CONCEPTS_PRESENT(
            "E04", Severity.ERROR,
            "All expected concepts present",
            "Every concept in the source RF2 (after applying filter flags) must appear in the output"),
        NO_UNEXPECTED_CONCEPTS(
            "E05", Severity.ERROR,
            "No unexpected concepts",
            "No concept may appear in the output that is absent from the source RF2 or was filtered out"),
        ACTIVE_FLAG_CORRECT(
            "E06", Severity.ERROR,
            "Active flag matches source",
            "The active flag on each row must match the concept's active flag in the source RF2 file"),
        FSN_CORRECT(
            "E07", Severity.ERROR,
            "FSN matches source",
            "The FSN on each row must be the active FSN from the source descriptions file"),
        PREFERRED_TERM_CORRECT(
            "E08", Severity.ERROR,
            "Preferred term matches source",
            "The preferred term must be the active synonym designated as Preferred in the US language refset"),
        NO_BLANK_FSN(
            "E09", Severity.ERROR,
            "No blank FSN values",
            "No row may have a blank FSN column"),
        NO_BLANK_PREFERRED_TERM(
            "E10", Severity.ERROR,
            "No blank preferred term values",
            "No row may have a blank USPreferredTerm column. Note: only applies to active concepts; "
            + "inactive concepts without a US Preferred Term are excluded from this check"),
        FILE_ENDS_WITH_NEWLINE(
            "E11", Severity.ERROR,
            "File ends with a newline",
            "The file must end with a newline (LF) character on the final row"),
        NO_NON_UTF8_CHARACTERS(
            "E12", Severity.ERROR,
            "No non-UTF-8 characters",
            "No row may contain byte sequences that are not valid UTF-8"),
        RECORD_COUNT_CORRECT(
            "E13", Severity.ERROR,
            "Total record count correct",
            "The total number of rows must equal the number of active plus qualifying inactive concepts from the RF2 source"),
        FILE_NOT_EMPTY(
            "E14", Severity.ERROR,
            "File is not empty",
            "The file must contain at least one data row beyond the header"),
        NO_BOM(
            "E15", Severity.ERROR,
            "No UTF-8 BOM",
            "The file must not begin with a UTF-8 Byte Order Mark (0xEF 0xBB 0xBF)"),
        UNIX_LINE_ENDINGS(
            "E16", Severity.ERROR,
            "Unix LF line endings",
            "The file must use Unix-style LF (\\n) line endings, not Windows CRLF (\\r\\n)"),
        NO_BLANK_CONCEPT_ID(
            "E17", Severity.ERROR,
            "No blank ConceptID values",
            "No row may have a blank ConceptID column"),
        CONCEPT_ID_IS_NUMERIC(
            "E18", Severity.ERROR,
            "ConceptID is numeric",
            "Every ConceptID value must consist entirely of digits"),
        ACTIVE_FLAG_VALID_VALUE(
            "E19", Severity.ERROR,
            "Active flag is 0 or 1",
            "The active flag on every row must be exactly '0' or '1'"),
        US_PREFERRED_TERM_NOT_GB(
            "E20", Severity.ERROR,
            "Preferred term is US English, not GB English",
            "The USPreferredTerm column must contain the term from the US English language refset "
            + "(900000000000509007), not the GB English refset (900000000000508004). "
            + "Any concept whose output term matches the GB preferred synonym but NOT the US "
            + "preferred synonym is flagged as potentially using the wrong dialect."),

        // ── WARNING tests ─────────────────────────────────────────────────────
        FSN_HAS_VALID_SEMANTIC_TAG(
            "W01", Severity.WARNING,
            "FSN ends with a valid semantic tag",
            "Every non-empty active FSN should end with a parenthesised semantic tag from the allowed list, "
            + "e.g. '(disorder)'. Inactive concepts are excluded from this check."),
        FSN_SINGLE_SEMANTIC_TAG(
            "W02", Severity.WARNING,
            "FSN contains exactly one semantic tag",
            "Every active FSN should contain exactly one pair of parentheses forming the semantic tag "
            + "at the end of the term. Inactive concepts are excluded from this check."),
        FSN_NOT_SAME_AS_PREFERRED_TERM(
            "W03", Severity.WARNING,
            "FSN and preferred term are not identical",
            "For active concepts, the FSN and preferred term should not be identical — this may indicate "
            + "the preferred term was not resolved. Inactive concepts are excluded from this check."),
        PREFERRED_TERM_NOT_AN_FSN(
            "W04", Severity.WARNING,
            "Preferred term does not look like an FSN",
            "For active concepts, the preferred term should not end with a parenthesised semantic tag — "
            + "this may indicate the FSN was used instead of the synonym. "
            + "Inactive concepts are excluded from this check."),
        NO_TRAILING_WHITESPACE(
            "W05", Severity.WARNING,
            "No trailing whitespace on rows",
            "No row should end with a trailing space or tab character");

        /** Stable unique identifier for this test, e.g. {@code "E01"} or {@code "W03"}.
         *  This is the primary key used in CLI arguments, exception-list filenames,
         *  and all report output.  Codes never change once assigned. */
        public final String   code;
        public final Severity severity;
        public final String   name;
        public final String   description;

        ValidationTest(String code, Severity severity, String name, String description) {
            this.code        = code;
            this.severity    = severity;
            this.name        = name;
            this.description = description;
        }
    }

    // =========================================================================
    // Allowed semantic tags — loaded once from resources
    // =========================================================================

    // Package-private to allow tests to reset between runs
    static Set<String> allowedSemanticTags = null;

    static Set<String> getAllowedSemanticTags() {
        if (allowedSemanticTags != null) return allowedSemanticTags;
        Set<String> tags = new LinkedHashSet<>();
        try (InputStream is = GpsValidator.class.getResourceAsStream("/allowed_semantic_tags.txt")) {
            if (is == null) {
                System.err.println("Warning: allowed_semantic_tags.txt not found in resources; semantic tag validation disabled.");
                allowedSemanticTags = Collections.emptySet();
                return allowedSemanticTags;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
                            tags.add(trimmed.substring(1, trimmed.length() - 1));
                        } else {
                            tags.add(trimmed);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read allowed_semantic_tags.txt: " + e.getMessage());
        }
        allowedSemanticTags = Collections.unmodifiableSet(tags);
        return allowedSemanticTags;
    }

    // =========================================================================
    // CLI entry point
    // =========================================================================

    public static void main(String[] args) {
        boolean activeOnly    = false;
        String  inactiveSince = null;
        int argIndex = 0;

        while (argIndex < args.length && args[argIndex].startsWith("--")) {
            switch (args[argIndex]) {
                case "--active-only":
                    activeOnly = true;
                    argIndex++;
                    break;
                case "--inactive-since":
                    argIndex++;
                    if (argIndex < args.length) {
                        inactiveSince = args[argIndex];
                        if (!inactiveSince.matches("\\d{8}")) {
                            System.err.println("Error: --inactive-since date must be in YYYYMMDD format (e.g. 20230101)");
                            System.exit(1);
                        }
                        argIndex++;
                    } else {
                        System.err.println("Error: --inactive-since requires a date argument (YYYYMMDD)");
                        System.exit(1);
                    }
                    break;
                default:
                    System.err.println("Unknown option: " + args[argIndex]);
                    printUsage();
                    System.exit(1);
            }
        }

        String[] positional = Arrays.copyOfRange(args, argIndex, args.length);
        if (positional.length != 3) {
            printUsage();
            System.exit(1);
        }

        String zipPath    = positional[0];
        String outputPath = positional[1];
        String reportPath = positional[2];

        if (activeOnly && inactiveSince != null) {
            System.err.println("Warning: --inactive-since has no effect when --active-only is also set.");
        }

        validateInputFile(zipPath, "RF2 ZIP");
        if (!isValidZip(zipPath)) {
            System.err.println("Error: RF2 file is not a valid ZIP file: " + zipPath);
            System.exit(1);
        }
        validateInputFile(outputPath, "GPS output");

        Path reportFilePath = Paths.get(reportPath);
        Path reportDir = reportFilePath.getParent();
        if (reportDir != null && !Files.exists(reportDir)) {
            System.err.println("Error: Report output directory does not exist: " + reportDir);
            System.exit(1);
        }

        try {
            // Progress indicator: step 1
            System.out.print("[1/4] Extracting RF2 files...          ");
            Path[] extracted       = extractRf2Zip(zipPath);
            Path   conceptsPath    = extracted[0];
            Path   descriptionsPath = extracted[1];
            Path   preferencesPath  = extracted[2];
            Path   tempDir          = extracted[3];
            System.out.println("done");

            try {
                // Progress indicator: step 2
                System.out.print("[2/4] Building expected output oracle... ");
                Map<String, String[]> expected = buildExpectedOutput(
                        conceptsPath, descriptionsPath, preferencesPath, activeOnly, inactiveSince);
                System.out.println("done (" + expected.size() + " concepts)");

                // Progress indicator: step 3
                System.out.print("[3/4] Validating GPS output...          ");
                List<Finding> findings = validateOutput(Paths.get(outputPath), expected, activeOnly, inactiveSince);
                long errors   = findings.stream().filter(f -> f.severity == Severity.ERROR).count();
                long warnings = findings.stream().filter(f -> f.severity == Severity.WARNING).count();
                System.out.println("done (" + errors + " error(s), " + warnings + " warning(s))");

                // Progress indicator: step 4
                System.out.print("[4/4] Writing report...                 ");
                writeReport(reportPath, zipPath, outputPath, activeOnly, inactiveSince, expected.size(), findings);
                System.out.println("done");

            } finally {
                deleteTempDir(tempDir);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    // =========================================================================
    // list-validations
    // =========================================================================

    public static void listValidations() {
        // Fixed column widths
        final int W_CODE = 6;
        final int W_SEV  = 9;
        final int W_NAME = 46;
        final int W_DESC = 60;

        // Leading indent before each row (two spaces)
        final String INDENT = "  ";
        // The prefix that continuation description lines must align with:
        // INDENT + code-col + "  " + sev-col + "  " + name-col + "  "
        final int CONT_OFFSET = INDENT.length() + W_CODE + 2 + W_SEV + 2 + W_NAME + 2;
        final String CONT_PAD = " ".repeat(CONT_OFFSET);

        String rowFmt = INDENT + "%-" + W_CODE + "s  %-" + W_SEV + "s  %-" + W_NAME + "s  %-" + W_DESC + "s%n";

        String codeSep = "-".repeat(W_CODE);
        String sevSep  = "-".repeat(W_SEV);
        String nameSep = "-".repeat(W_NAME);
        String descSep = "-".repeat(W_DESC);

        System.out.println("GPS Validation Test Catalogue");
        System.out.println("=============================");
        System.out.println();
        System.out.println("Each test has a permanent code (e.g. E01, W03) used in CLI arguments,");
        System.out.println("exception-list filenames, and validation reports.");
        System.out.println("ERROR tests cause a FAIL result; WARNING tests are informational only.");
        System.out.println();

        // Header row
        System.out.printf(rowFmt, "Code", "Severity", "Name", "Description");
        System.out.printf(rowFmt, codeSep, sevSep, nameSep, descSep);

        for (ValidationTest t : ValidationTest.values()) {
            List<String> descLines = wordWrap(t.description, W_DESC);
            // First line: all four columns
            System.out.printf(rowFmt, t.code, t.severity, t.name,
                    descLines.isEmpty() ? "" : descLines.get(0));
            // Continuation lines: blank first three columns, description indented to align
            for (int i = 1; i < descLines.size(); i++) {
                System.out.println(CONT_PAD + descLines.get(i));
            }
        }
        System.out.println();
    }

    /**
     * Wraps {@code text} into lines of at most {@code width} characters, breaking
     * only at word boundaries (spaces).  Never splits a word that is longer than
     * {@code width} — such words occupy their own line.
     *
     * @param text  the text to wrap (may not be null)
     * @param width the maximum line width in characters
     * @return list of lines; never empty (contains at least one element)
     */
    static List<String> wordWrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) { lines.add(""); return lines; }
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() == 0) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    // =========================================================================
    // Oracle
    // =========================================================================

    /**
     * Reads the three source RF2 files and produces the expected GPS output map:
     *   conceptId → [active, expectedFsn, expectedUsPreferredTerm, expectedGbPreferredTerm]
     *
     * Index 0 = active flag, 1 = FSN, 2 = US preferred term, 3 = GB preferred term.
     * The GB preferred term (index 3) is used by the US_PREFERRED_TERM_NOT_GB check to
     * detect concepts where the output contains the GB preferred synonym instead of the US one.
     *
     * The preferred term lookup uses the US English language refset only (refset ID
     * 900000000000509007) and requires BOTH the description AND its language-refset
     * entry to be active.  This mirrors the production extraction logic in
     * ExtractTerms and ensures the oracle is never blank when a valid preferred
     * term exists in the source data.
     */
    public static Map<String, String[]> buildExpectedOutput(
            Path conceptsPath, Path descriptionsPath, Path preferencesPath,
            boolean activeOnly, String inactiveSince) throws IOException {

        // Step 1 — qualified concepts
        Map<String, String> qualifiedConcepts = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(conceptsPath)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                String  id            = p[0];
                String  effectiveTime = p[1];
                String  active        = p[2];
                boolean isActive      = "1".equals(active);
                if (activeOnly && !isActive) continue;
                if (inactiveSince != null && !isActive && effectiveTime.compareTo(inactiveSince) < 0) continue;
                qualifiedConcepts.put(id, active);
            }
        }

        // Step 2 — preference maps: US English and GB English (active entries only).
        // Key: descriptionId  Value: acceptabilityId
        //
        // We build two separate maps so that GB acceptability can never corrupt the
        // US lookup (the root cause of the 826-blank-preferred-terms regression).
        // The GB map is used solely by the US_PREFERRED_TERM_NOT_GB check.
        Map<String, String> descPrefsUS = new HashMap<>();
        Map<String, String> descPrefsGB = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(preferencesPath)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                // id | effectiveTime | active | moduleId | refsetId | referencedComponentId | acceptabilityId
                if (p.length < 7) continue;
                if (!"1".equals(p[2])) continue;                          // active rows only
                if (US_ENGLISH_REFSET_ID.equals(p[4])) {
                    descPrefsUS.put(p[5], p[6]);                          // US: descriptionId → acceptabilityId
                } else if (GB_ENGLISH_REFSET_ID.equals(p[4])) {
                    descPrefsGB.put(p[5], p[6]);                          // GB: descriptionId → acceptabilityId
                }
            }
        }

        // Step 3 — descriptions (only active descriptions count)
        Map<String, String> fsnMap    = new HashMap<>();
        Map<String, String> termMapUS = new HashMap<>();
        Map<String, String> termMapGB = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(descriptionsPath)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                // Columns: id | effectiveTime | active | moduleId | conceptId | languageCode | typeId | term
                if (p.length < 8) continue;
                if (!"1".equals(p[2])) continue; // skip inactive descriptions
                String descId    = p[0];
                String conceptId = p[4];
                String typeId    = p[6];
                String term      = p[7];
                if (!qualifiedConcepts.containsKey(conceptId)) continue;
                if (FSN_TYPE_ID.equals(typeId)) {
                    fsnMap.put(conceptId, term);
                } else if (SYNONYM_TYPE_ID.equals(typeId)) {
                    if (PREFERRED_ID.equals(descPrefsUS.get(descId))) {
                        termMapUS.put(conceptId, term);
                    }
                    if (PREFERRED_ID.equals(descPrefsGB.get(descId))) {
                        termMapGB.put(conceptId, term);
                    }
                }
            }
        }

        // Step 4 — assemble (index 0=active, 1=fsn, 2=usTerm, 3=gbTerm)
        Map<String, String[]> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : qualifiedConcepts.entrySet()) {
            String id = e.getKey();
            expected.put(id, new String[]{
                e.getValue(),
                fsnMap.getOrDefault(id, ""),
                termMapUS.getOrDefault(id, ""),
                termMapGB.getOrDefault(id, "")
            });
        }
        return expected;
    }


    // =========================================================================
    // Validator
    // =========================================================================

    /**
     * Parses the GPS output file and cross-references every row against the
     * expected map.  Returns a list of {@link Finding} objects.
     *
     * Exception lists are honoured per-test: if a concept ID appears in the
     * exception list for a test, findings for that concept from that test are
     * suppressed.
     *
     * Inactive-concept exclusions (requirement 6):
     *   FSN_HAS_VALID_SEMANTIC_TAG, FSN_SINGLE_SEMANTIC_TAG,
     *   FSN_NOT_SAME_AS_PREFERRED_TERM, PREFERRED_TERM_NOT_AN_FSN
     *   are only applied to active concepts (active flag == "1").
     */
    public static List<Finding> validateOutput(
            Path outputPath, Map<String, String[]> expected,
            boolean activeOnly, String inactiveSince) throws IOException {

        List<Finding>         findings    = new ArrayList<>();
        Map<String, String[]> actual      = new LinkedHashMap<>();
        Set<String>           allowedTags = getAllowedSemanticTags();

        // Pre-load exception lists for all tests
        Map<ValidationTest, Set<String>> exceptions = new EnumMap<>(ValidationTest.class);
        for (ValidationTest t : ValidationTest.values()) {
            exceptions.put(t, ExceptionList.getExceptions(t));
        }

        // ── File-level checks (byte scan) ─────────────────────────────────────
        byte[] fileBytes = Files.readAllBytes(outputPath);

        long lineCount = 0;
        for (byte b : fileBytes) { if (b == '\n') lineCount++; }
        if (lineCount <= 1) {
            findings.add(new Finding(ValidationTest.FILE_NOT_EMPTY,
                "File contains no data rows (only a header or is empty)"));
        }

        if (fileBytes.length > 0) {
            if (fileBytes.length >= 3
                    && (fileBytes[0] & 0xFF) == 0xEF
                    && (fileBytes[1] & 0xFF) == 0xBB
                    && (fileBytes[2] & 0xFF) == 0xBF) {
                findings.add(new Finding(ValidationTest.NO_BOM,
                    "File starts with a UTF-8 Byte Order Mark (BOM) which must be removed"));
            }

            boolean hasCrlf = false;
            for (int i = 0; i < fileBytes.length - 1; i++) {
                if (fileBytes[i] == '\r' && fileBytes[i + 1] == '\n') { hasCrlf = true; break; }
            }
            if (hasCrlf) {
                findings.add(new Finding(ValidationTest.UNIX_LINE_ENDINGS,
                    "File uses Windows-style CRLF (\\r\\n) line endings; Unix LF (\\n) is required"));
            }

            if (fileBytes[fileBytes.length - 1] != '\n' && fileBytes[fileBytes.length - 1] != '\r') {
                findings.add(new Finding(ValidationTest.FILE_ENDS_WITH_NEWLINE,
                    "File does not end with a newline on the final row"));
            }

            for (String msg : checkUtf8Encoding(fileBytes)) {
                findings.add(new Finding(ValidationTest.NO_NON_UTF8_CHARACTERS, msg));
            }
        }

        // ── Row-level checks ─────────────────────────────────────────────────
        try (BufferedReader r = Files.newBufferedReader(outputPath)) {
            String header = r.readLine();
            if (!"ConceptID\tActive\tFSN\tUSPreferredTerm".equals(header)) {
                findings.add(new Finding(ValidationTest.HEADER_CORRECT,
                    "Header must be 'ConceptID\\tActive\\tFSN\\tUSPreferredTerm', got: '" + header + "'"));
            }

            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] cols = line.split("\t", -1);
                if (cols.length != 4) {
                    findings.add(new Finding(ValidationTest.FOUR_COLUMNS,
                        "Row does not have exactly 4 tab-separated columns: " + line));
                    continue;
                }

                String id         = cols[0];
                String activeFlag = cols[1];
                String fsn        = cols[2];
                String term       = cols[3];
                boolean isActive  = "1".equals(activeFlag);

                if (actual.containsKey(id)) {
                    addFinding(findings, ValidationTest.NO_DUPLICATE_IDS, id, exceptions,
                        "Duplicate concept ID in output: " + id);
                }
                actual.put(id, new String[]{activeFlag, fsn, term});

                if (id.trim().isEmpty()) {
                    findings.add(new Finding(ValidationTest.NO_BLANK_CONCEPT_ID,
                        "Row has a blank ConceptID: " + line));
                }
                if (!id.trim().isEmpty() && !id.matches("\\d+")) {
                    addFinding(findings, ValidationTest.CONCEPT_ID_IS_NUMERIC, id, exceptions,
                        "ConceptID is not numeric: '" + id + "'");
                }
                if (!"0".equals(activeFlag) && !"1".equals(activeFlag)) {
                    addFinding(findings, ValidationTest.ACTIVE_FLAG_VALID_VALUE, id, exceptions,
                        "Concept " + id + ": active flag has unexpected value '" + activeFlag + "' (must be 0 or 1)");
                }
                if (fsn.trim().isEmpty()) {
                    addFinding(findings, ValidationTest.NO_BLANK_FSN, id, exceptions,
                        "Concept " + id + ": FSN is blank");
                }

                // NO_BLANK_PREFERRED_TERM — active concepts only (requirement 6 / fix 7)
                if (isActive && term.trim().isEmpty()) {
                    addFinding(findings, ValidationTest.NO_BLANK_PREFERRED_TERM, id, exceptions,
                        "Concept " + id + ": preferred term is blank (active concept)");
                }

                // NO_TRAILING_WHITESPACE — WARNING
                if (line.endsWith(" ") || line.endsWith("\t")) {
                    addFinding(findings, ValidationTest.NO_TRAILING_WHITESPACE, id, exceptions,
                        "Concept " + id + ": row has trailing whitespace");
                }

                // Active-only checks (requirements 6)
                if (isActive) {
                    // FSN_SINGLE_SEMANTIC_TAG — count parenthesis pairs
                    if (!fsn.trim().isEmpty()) {
                        int tagCount = 0, pos = 0;
                        while (pos < fsn.length()) {
                            int open  = fsn.indexOf('(', pos);
                            int close = fsn.indexOf(')', pos);
                            if (open != -1 && close != -1 && close > open) { tagCount++; pos = close + 1; }
                            else break;
                        }
                        if (tagCount > 1) {
                            addFinding(findings, ValidationTest.FSN_SINGLE_SEMANTIC_TAG, id, exceptions,
                                "Concept " + id + ": FSN contains more than one semantic tag: '" + fsn + "'");
                        }
                    }

                    // FSN_NOT_SAME_AS_PREFERRED_TERM
                    if (!fsn.trim().isEmpty() && !term.trim().isEmpty() && fsn.equals(term)) {
                        addFinding(findings, ValidationTest.FSN_NOT_SAME_AS_PREFERRED_TERM, id, exceptions,
                            "Concept " + id + ": FSN and preferred term are identical ('" + term
                                + "') — preferred term may not have been resolved");
                    }

                    // PREFERRED_TERM_NOT_AN_FSN
                    if (!term.trim().isEmpty()) {
                        int lo = term.lastIndexOf('('), lc = term.lastIndexOf(')');
                        if (lo != -1 && lc == term.length() - 1 && lo < lc) {
                            addFinding(findings, ValidationTest.PREFERRED_TERM_NOT_AN_FSN, id, exceptions,
                                "Concept " + id + ": preferred term appears to contain a semantic tag and may "
                                    + "be using the FSN instead: '" + term + "'");
                        }
                    }
                } // end active-only checks
            } // end row loop
        }

        // ── Record count ──────────────────────────────────────────────────────
        int activeCount = 0, inactiveCount = 0;
        for (String[] v : expected.values()) {
            if ("1".equals(v[0])) activeCount++; else inactiveCount++;
        }
        if (actual.size() != activeCount + inactiveCount) {
            findings.add(new Finding(ValidationTest.RECORD_COUNT_CORRECT,
                String.format("Record count mismatch: GPS file has %d records but expected %d "
                    + "(%d active + %d qualifying inactive%s)",
                    actual.size(), activeCount + inactiveCount, activeCount, inactiveCount,
                    inactiveSince != null ? " since " + inactiveSince : "")));
        }

        // ── Concept membership ────────────────────────────────────────────────
        for (String id : expected.keySet()) {
            if (!actual.containsKey(id)) {
                String[] e = expected.get(id);
                addFinding(findings, ValidationTest.ALL_EXPECTED_CONCEPTS_PRESENT, id, exceptions,
                    "Concept " + id + " is missing from output (expected active=" + e[0] + ", fsn='" + e[1] + "')");
            }
        }
        for (String id : actual.keySet()) {
            if (!expected.containsKey(id)) {
                addFinding(findings, ValidationTest.NO_UNEXPECTED_CONCEPTS, id, exceptions,
                    "Concept " + id + " is in output but should not be (absent from source or filtered out)");
            }
        }

        // ── Per-concept value correctness ──────────────────────────────────────
        for (String id : expected.keySet()) {
            if (!actual.containsKey(id)) continue;
            String[] exp = expected.get(id);
            String[] act = actual.get(id);
            boolean  conceptIsActive = "1".equals(act[0]);

            if (!exp[0].equals(act[0])) {
                addFinding(findings, ValidationTest.ACTIVE_FLAG_CORRECT, id, exceptions,
                    "Concept " + id + ": active flag — source says '" + exp[0] + "', output has '" + act[0] + "'");
            }
            if (!exp[1].equals(act[1])) {
                addFinding(findings, ValidationTest.FSN_CORRECT, id, exceptions,
                    "Concept " + id + ": FSN — source active FSN is '" + exp[1] + "', output has '" + act[1] + "'");
            }
            if (!exp[2].equals(act[2])) {
                addFinding(findings, ValidationTest.PREFERRED_TERM_CORRECT, id, exceptions,
                    "Concept " + id + ": preferred term — source preferred synonym is '"
                        + exp[2] + "', output has '" + act[2] + "'");
            }

            // US_PREFERRED_TERM_NOT_GB — check that the output term is not the GB preferred
            // term when the US preferred term differs from it.  This catches cases where the
            // extraction accidentally used the GB dialect instead of the US dialect.
            // Only fires when: (a) there is a non-empty GB preferred term, (b) the GB preferred
            // term differs from the US preferred term, and (c) the output matches the GB term.
            String gbTerm = exp.length > 3 ? exp[3] : "";
            String usTerm = exp[2];
            String outTerm = act[2];
            if (!gbTerm.isEmpty() && !gbTerm.equals(usTerm) && gbTerm.equals(outTerm)) {
                addFinding(findings, ValidationTest.US_PREFERRED_TERM_NOT_GB, id, exceptions,
                    "Concept " + id + ": output preferred term matches GB preferred synonym ('"
                        + gbTerm + "') but the US preferred synonym is '" + usTerm
                        + "' — output must use US English");
            }

            // FSN_HAS_VALID_SEMANTIC_TAG — active concepts only (WARNING, requirement 8)
            String fsn = act[1];
            if (conceptIsActive && !fsn.isEmpty()) {
                String tag = extractLastSemanticTag(fsn);
                if (tag == null) {
                    addFinding(findings, ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG, id, exceptions,
                        "Concept " + id + ": FSN does not end with a semantic tag '(tag)': '" + fsn + "'");
                } else if (!allowedTags.isEmpty() && !allowedTags.contains(tag)) {
                    addFinding(findings, ValidationTest.FSN_HAS_VALID_SEMANTIC_TAG, id, exceptions,
                        "Concept " + id + ": FSN ends with unknown semantic tag '(" + tag + ")': '" + fsn + "'");
                }
            }
        }

        return findings;
    }

    // =========================================================================
    // Semantic tag extraction
    // =========================================================================

    /**
     * Extracts the content of the very last parenthesised group in a term,
     * provided that the closing parenthesis is the final character.
     *
     * Examples:
     *   "Transplantation of bone marrow (bone marrow transplant) (procedure)" → "procedure"
     *   "Diabetes mellitus (disorder)"                                        → "disorder"
     *   "Some term without a tag"                                             → null
     */
    public static String extractLastSemanticTag(String term) {
        if (term == null || term.isEmpty()) return null;
        if (term.charAt(term.length() - 1) != ')') return null;
        int lastOpen = term.lastIndexOf('(');
        if (lastOpen == -1) return null;
        int lastClose = term.length() - 1;
        if (lastOpen >= lastClose) return null;
        String tag = term.substring(lastOpen + 1, lastClose).trim();
        return tag.isEmpty() ? null : tag;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Adds a finding only if the concept ID is not on the exception list for that test. */
    private static void addFinding(List<Finding> findings, ValidationTest test,
            String conceptId, Map<ValidationTest, Set<String>> exceptions, String message) {
        if (conceptId != null && exceptions.get(test).contains(conceptId)) return;
        findings.add(new Finding(test, conceptId, message));
    }

    private static void validateInputFile(String path, String label) {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            System.err.println("Error: " + label + " file not found: " + path);
            System.exit(1);
        }
        if (!Files.isReadable(p)) {
            System.err.println("Error: " + label + " file is not readable: " + path);
            System.exit(1);
        }
    }

    private static Path[] extractRf2Zip(String zipFile) throws IOException {
        Path tempDir      = Files.createTempDirectory("gps-validate");
        Path concepts     = null;
        Path descriptions = null;
        Path preferences  = null;

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.contains("Snapshot/Terminology/sct2_Concept_Snapshot_INT")) {
                    concepts = extractEntry(zip, entry, tempDir);
                } else if (name.contains("Snapshot/Terminology/sct2_Description_Snapshot-en_INT")) {
                    descriptions = extractEntry(zip, entry, tempDir);
                } else if (name.contains("Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT")) {
                    preferences = extractEntry(zip, entry, tempDir);
                }
            }
        }

        if (concepts == null || descriptions == null || preferences == null) {
            deleteTempDir(tempDir);
            StringBuilder msg = new StringBuilder("Could not find all required RF2 files in ZIP. Missing:");
            if (concepts == null)     msg.append("\n  - sct2_Concept_Snapshot_INT (under Snapshot/Terminology/)");
            if (descriptions == null) msg.append("\n  - sct2_Description_Snapshot-en_INT (under Snapshot/Terminology/)");
            if (preferences == null)  msg.append("\n  - der2_cRefset_LanguageSnapshot-en_INT (under Snapshot/Refset/Language/)");
            throw new IOException(msg.toString());
        }
        return new Path[]{concepts, descriptions, preferences, tempDir};
    }

    /**
     * Maximum permitted uncompressed size for a single RF2 file extracted from a ZIP
     * (2 GB).  This guards against zip-bomb / decompression attacks.
     * The largest real-world SNOMED CT release files are well under 1 GB uncompressed.
     */
    static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB

    private static Path extractEntry(java.util.zip.ZipFile zip,
            java.util.zip.ZipEntry entry, Path destDir) throws IOException {
        // Security: prevent zip-slip path traversal
        Path dest = destDir.resolve(Paths.get(entry.getName()).getFileName()).normalize();
        if (!dest.startsWith(destDir.normalize())) {
            throw new IOException("Zip entry path traversal detected: " + entry.getName());
        }
        // Security: prevent zip-bomb / decompression attack.
        // Check the declared uncompressed size first (may be -1 for streaming entries).
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_ENTRY_UNCOMPRESSED_BYTES) {
            throw new IOException(String.format(
                "Zip entry '%s' declares an uncompressed size of %d bytes which exceeds the "
                + "permitted maximum of %d bytes. Aborting to prevent a decompression attack.",
                entry.getName(), declaredSize, MAX_ENTRY_UNCOMPRESSED_BYTES));
        }
        // Also count bytes written via a limited stream to catch entries with size == -1 or 0.
        try (InputStream is = zip.getInputStream(entry)) {
            Files.copy(new LimitedInputStream(is, MAX_ENTRY_UNCOMPRESSED_BYTES, entry.getName()),
                    dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    /**
     * An {@link InputStream} decorator that throws {@link IOException} if more than
     * {@code maxBytes} bytes are read.  Used to protect against zip-bomb attacks where
     * the declared entry size is 0 or -1 (unknown).
     */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long        maxBytes;
        private final String      entryName;
        private long bytesRead = 0;

        LimitedInputStream(InputStream delegate, long maxBytes, String entryName) {
            this.delegate  = delegate;
            this.maxBytes  = maxBytes;
            this.entryName = entryName;
        }

        @Override public int read() throws IOException {
            if (bytesRead >= maxBytes) boom();
            int b = delegate.read();
            if (b != -1) bytesRead++;
            return b;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (bytesRead >= maxBytes) boom();
            int n = delegate.read(buf, off, (int) Math.min(len, maxBytes - bytesRead));
            if (n > 0) bytesRead += n;
            return n;
        }

        @Override public void close() throws IOException { delegate.close(); }

        private void boom() throws IOException {
            throw new IOException(String.format(
                "Zip entry '%s' exceeded the permitted maximum uncompressed size of %d bytes "
                + "during extraction. Aborting to prevent a decompression attack.",
                entryName, maxBytes));
        }
    }

    private static List<String> checkUtf8Encoding(byte[] bytes) {
        List<String> violations = new ArrayList<>();
        int lineNumber = 1;
        boolean lineHasError = false;
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            int seqLen;
            if (b == 0x0A) { lineNumber++; lineHasError = false; i++; continue; }
            else if (b <= 0x7F)            seqLen = 1;
            else if ((b & 0xE0) == 0xC0)   seqLen = 2;
            else if ((b & 0xF0) == 0xE0)   seqLen = 3;
            else if ((b & 0xF8) == 0xF0)   seqLen = 4;
            else {
                if (!lineHasError) {
                    violations.add(String.format("Line %d contains non-UTF-8 byte 0x%02X at offset %d", lineNumber, b, i));
                    lineHasError = true;
                }
                i++; continue;
            }
            boolean valid = (i + seqLen <= bytes.length);
            if (valid) {
                for (int c = 1; c < seqLen; c++) {
                    if ((bytes[i + c] & 0xC0) != 0x80) { valid = false; break; }
                }
            }
            if (!valid && !lineHasError) {
                violations.add(String.format("Line %d contains non-UTF-8 byte 0x%02X at offset %d", lineNumber, b, i));
                lineHasError = true;
            }
            i += seqLen;
        }
        return violations;
    }

    private static boolean isValidZip(String filePath) {
        try (InputStream is = Files.newInputStream(Paths.get(filePath))) {
            byte[] magic = new byte[4];
            if (is.read(magic) < 4) return false;
            return magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTempDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Report writer
    // =========================================================================

    private static void writeReport(String reportPath, String zipPath, String outputPath,
            boolean activeOnly, String inactiveSince,
            int expectedCount, List<Finding> findings) throws IOException {

        List<Finding> errors   = findings.stream().filter(f -> f.severity == Severity.ERROR)  .collect(Collectors.toList());
        List<Finding> warnings = findings.stream().filter(f -> f.severity == Severity.WARNING).collect(Collectors.toList());

        boolean passed      = errors.isEmpty();
        boolean hasWarnings = !warnings.isEmpty();
        String  timestamp   = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try (PrintWriter w = new PrintWriter(
                new OutputStreamWriter(Files.newOutputStream(Paths.get(reportPath)), StandardCharsets.UTF_8))) {
            // ── Header ───────────────────────────────────────────────────────
            w.println("GPS Extraction Validation Report");
            w.println("================================");
            w.println("Generated:     " + timestamp);
            w.println("RF2 source:    " + zipPath);
            w.println("GPS output:    " + outputPath);
            w.println("Flags:         " + buildFlagsString(activeOnly, inactiveSince));
            w.println();

            // ── Validation tests catalogue (condensed) ───────────────────────
            w.println("Validation Tests Performed");
            w.println("--------------------------");
            w.println("All tests run during this validation, with exception counts where applicable.");
            w.println("(Excluded = number of concept findings suppressed via the exception list.)");
            w.println();
            w.printf("  %-6s %-9s %-46s %s%n", "Code", "Severity", "Name", "Excluded");
            w.printf("  %-6s %-9s %-46s %s%n", "------", "---------", "----------------------------------------------", "--------");
            for (ValidationTest t : ValidationTest.values()) {
                int excluded = ExceptionList.getExceptions(t).size();
                String excludedStr = excluded > 0 ? String.valueOf(excluded) : "-";
                w.printf("  %-6s %-9s %-46s %s%n", t.code, t.severity, t.name, excludedStr);
            }
            w.println();
            w.println("(Full descriptions and identifiers: java -jar <jarfile> list-validations)");
            w.println();

            // ── Overall summary ───────────────────────────────────────────────
            w.println("Overall Summary");
            w.println("---------------");
            w.println("Source concepts (after filter): " + expectedCount);
            w.println("Error findings:                 " + errors.size());
            w.println("Warning findings:               " + warnings.size());
            w.println();
            w.println("Result: " + (passed ? "PASS" : "FAIL"));
            w.println();

            // ── Errors section ────────────────────────────────────────────────
            if (!errors.isEmpty()) {
                w.println("Errors");
                w.println("======");
                w.println();

                // Summary subsection with overlap detection
                writeFindingsSummary(w, errors, "Error");

                // Full list
                w.println("Full Error List");
                w.println("--------------");
                for (int i = 0; i < errors.size(); i++) {
                    w.printf("[E%d] %s%n", i + 1, errors.get(i).message);
                }
                w.println();
            }

            // ── Warnings section ──────────────────────────────────────────────
            if (!warnings.isEmpty()) {
                w.println("Warnings");
                w.println("========");
                w.println();

                writeFindingsSummary(w, warnings, "Warning");

                w.println("Full Warning List");
                w.println("----------------");
                for (int i = 0; i < warnings.size(); i++) {
                    w.printf("[W%d] %s%n", i + 1, warnings.get(i).message);
                }
                w.println();
            }
        }

        // ── Console output ───────────────────────────────────────────────────
        System.out.println();
        if (passed) {
            if (hasWarnings) {
                System.out.println("Result: PASS with Warnings (" + warnings.size() + " warning(s))");
                System.out.println("Please read the report to understand and analyse these warnings.");
            } else {
                System.out.println("Result: PASS (0 violations)");
            }
        } else {
            System.out.println("Result: FAIL (" + errors.size() + " error(s), " + warnings.size() + " warning(s))");
        }
        System.out.println("Report written to: " + reportPath);
    }

    /**
     * Writes a grouped summary section for a list of findings (errors or warnings).
     * Groups findings by {@link ValidationTest}, prints a count and short description
     * for each group, then detects overlapping concept IDs between groups.
     */
    static void writeFindingsSummary(PrintWriter w, List<Finding> findings, String label) {
        w.println(label + " Summary");
        w.println("-".repeat(label.length() + 8));
        w.println();

        // Group by test
        Map<ValidationTest, List<Finding>> byTest = new LinkedHashMap<>();
        for (Finding f : findings) {
            byTest.computeIfAbsent(f.test, k -> new ArrayList<>()).add(f);
        }

        // Print each group
        for (Map.Entry<ValidationTest, List<Finding>> entry : byTest.entrySet()) {
            ValidationTest test  = entry.getKey();
            List<Finding>  group = entry.getValue();
            w.printf("  [%s] %-45s %,d violation(s)%n", test.code, test.name + ":", group.size());
            w.println("  " + test.description);
            w.println();
        }

        // Overlap detection: find pairs of tests that share the same concept IDs
        List<ValidationTest> tests = new ArrayList<>(byTest.keySet());
        boolean anyOverlap = false;
        for (int i = 0; i < tests.size(); i++) {
            for (int j = i + 1; j < tests.size(); j++) {
                ValidationTest tA = tests.get(i);
                ValidationTest tB = tests.get(j);
                Set<String> idsA = byTest.get(tA).stream()
                    .map(f -> f.conceptId).filter(Objects::nonNull).collect(Collectors.toSet());
                Set<String> idsB = byTest.get(tB).stream()
                    .map(f -> f.conceptId).filter(Objects::nonNull).collect(Collectors.toSet());
                Set<String> overlap = new HashSet<>(idsA);
                overlap.retainAll(idsB);
                if (!overlap.isEmpty()) {
                    if (!anyOverlap) {
                        w.println("Note on overlaps:");
                        anyOverlap = true;
                    }
                    w.printf("  The identical count of %,d between [%s] %s and [%s] %s occurs because%n"
                        + "  the same %,d concept(s) triggered both violations on the same row.%n%n",
                        overlap.size(), tA.code, tA.name, tB.code, tB.name, overlap.size());
                }
            }
        }
        w.println();
    }

    private static String buildFlagsString(boolean activeOnly, String inactiveSince) {
        if (!activeOnly && inactiveSince == null) return "(none)";
        StringBuilder sb = new StringBuilder();
        if (activeOnly) sb.append("--active-only");
        if (inactiveSince != null) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("--inactive-since ").append(inactiveSince);
        }
        return sb.toString();
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar <jarfile> validate [--active-only] [--inactive-since YYYYMMDD]");
        System.err.println("              <rf2-zip> <gps-output-tsv> <report-file>");
        System.err.println();
        System.err.println("  rf2-zip        Path to the SNOMED CT RF2 release ZIP file");
        System.err.println("  gps-output-tsv Path to the GPS extraction output TSV file to validate");
        System.err.println("  report-file    Path where the plain-text validation report will be written");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --active-only              Only validate active concepts");
        System.err.println("  --inactive-since YYYYMMDD  Only include inactive concepts with effectiveTime >= date");
    }
}
