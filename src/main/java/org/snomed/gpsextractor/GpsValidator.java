package org.snomed.gpsextractor;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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
 * Checks performed:
 *  - Header is exactly "ConceptID\tActive\tFSN\tUSPreferredTerm"
 *  - No duplicate concept IDs in the output
 *  - No concept is missing that should be present
 *  - No concept is present that should be absent / filtered out
 *  - The active flag on each row matches the source concept file
 *  - The FSN on each row is the active FSN from the source descriptions file
 *  - The preferred term on each row is the active Preferred synonym per the
 *    language refset
 *  - Every non-empty FSN ends with a semantic tag in parentheses "(tag)"
 */
public class GpsValidator {

    // SNOMED CT component identifiers
    private static final String FSN_TYPE_ID     = "900000000000003001";
    private static final String SYNONYM_TYPE_ID = "900000000000013009";
    private static final String PREFERRED_ID    = "900000000000548007";

    // =========================================================================
    // CLI entry point
    // =========================================================================

    public static void main(String[] args) {
        boolean activeOnly   = false;
        String  inactiveSince = null;
        int argIndex = 0;

        while (argIndex < args.length && args[argIndex].startsWith("--")) {
            if ("--active-only".equals(args[argIndex])) {
                activeOnly = true;
                argIndex++;
            } else if ("--inactive-since".equals(args[argIndex])) {
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
            } else {
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

        if (!zipPath.toLowerCase().endsWith(".zip")) {
            System.err.println("Error: first argument must be an RF2 ZIP file");
            System.exit(1);
        }

        try {
            Path[] extracted      = extractRf2Zip(zipPath);
            Path   conceptsPath   = extracted[0];
            Path   descriptionsPath = extracted[1];
            Path   preferencesPath  = extracted[2];
            Path   tempDir          = extracted[3];

            try {
                Map<String, String[]> expected = buildExpectedOutput(
                        conceptsPath, descriptionsPath, preferencesPath,
                        activeOnly, inactiveSince);
                List<String> violations = validateOutput(Paths.get(outputPath), expected);

                writeReport(reportPath, zipPath, outputPath, activeOnly, inactiveSince,
                            expected.size(), violations);
            } finally {
                deleteTempDir(tempDir);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    // =========================================================================
    // Oracle — independent re-implementation of extraction logic
    // =========================================================================

    /**
     * Reads the three source RF2 files and produces the expected GPS output map:
     *   conceptId → [active, expectedFsn, expectedPreferredTerm]
     *
     * Written independently of the production ExtractTerms code so that it acts
     * as a genuine oracle.  The same filtering rules (activeOnly, inactiveSince)
     * are applied so the result can be compared directly against GPS output
     * produced with the same flags.
     *
     * @param conceptsPath     path to sct2_Concept_Snapshot file
     * @param descriptionsPath path to sct2_Description_Snapshot-en file
     * @param preferencesPath  path to der2_cRefset_LanguageSnapshot-en file
     * @param activeOnly       if true, exclude inactive concepts
     * @param inactiveSince    if non-null (YYYYMMDD), exclude inactive concepts
     *                         whose effectiveTime is strictly before this date
     */
    public static Map<String, String[]> buildExpectedOutput(
            Path conceptsPath, Path descriptionsPath, Path preferencesPath,
            boolean activeOnly, String inactiveSince) throws IOException {

        // Step 1 — which concepts qualify under the requested filter?
        Map<String, String> qualifiedConcepts = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(conceptsPath)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                String  id            = p[0];
                String  effectiveTime = p[1];
                String  active        = p[2];
                boolean isActive      = "1".equals(active);

                if (activeOnly && !isActive) continue;
                if (inactiveSince != null && !isActive
                        && effectiveTime.compareTo(inactiveSince) < 0) continue;

                qualifiedConcepts.put(id, active);
            }
        }

        // Step 2 — preference map from active language-refset members only.
        //           Maps descriptionId → acceptabilityId.
        Map<String, String> descPrefs = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(preferencesPath)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 7 || !"1".equals(p[2])) continue;
                descPrefs.put(p[5], p[6]); // referencedComponentId → acceptabilityId
            }
        }

        // Step 3 — read active descriptions to resolve FSN and preferred term.
        Map<String, String> fsnMap  = new HashMap<>();
        Map<String, String> termMap = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(descriptionsPath)) {
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 8 || !"1".equals(p[2])) continue;
                String descId    = p[0];
                String conceptId = p[4];
                String typeId    = p[6];
                String term      = p[7];

                if (!qualifiedConcepts.containsKey(conceptId)) continue;

                if (FSN_TYPE_ID.equals(typeId)) {
                    fsnMap.put(conceptId, term);
                } else if (SYNONYM_TYPE_ID.equals(typeId)
                        && PREFERRED_ID.equals(descPrefs.get(descId))) {
                    termMap.put(conceptId, term);
                }
            }
        }

        // Step 4 — assemble expected output
        Map<String, String[]> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : qualifiedConcepts.entrySet()) {
            String id = e.getKey();
            expected.put(id, new String[]{
                e.getValue(),
                fsnMap.getOrDefault(id, ""),
                termMap.getOrDefault(id, "")
            });
        }
        return expected;
    }

    // =========================================================================
    // Validator — compares actual GPS output to ground truth
    // =========================================================================

    /**
     * Parses the GPS output file and cross-references every row against the
     * expected map returned by buildExpectedOutput().
     *
     * All violations are collected before returning so a single call shows the
     * complete list of problems rather than just the first one.
     *
     * @param outputPath path to the GPS TSV file produced by the extraction
     * @param expected   ground-truth map from buildExpectedOutput()
     * @return list of human-readable violation messages; empty = no violations
     */
    public static List<String> validateOutput(
            Path outputPath, Map<String, String[]> expected) throws IOException {

        List<String> violations = new ArrayList<>();
        Map<String, String[]> actual = new LinkedHashMap<>();

        try (BufferedReader r = Files.newBufferedReader(outputPath)) {
            String header = r.readLine();
            if (!"ConceptID\tActive\tFSN\tUSPreferredTerm".equals(header)) {
                violations.add("Header must be 'ConceptID\\tActive\\tFSN\\tUSPreferredTerm', got: '" + header + "'");
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                // Use limit -1 to preserve trailing empty fields (empty FSN or term)
                String[] cols = line.split("\t", -1);
                if (cols.length != 4) {
                    violations.add("Row does not have exactly 4 tab-separated columns: " + line);
                    continue;
                }
                String id = cols[0];
                if (actual.containsKey(id)) {
                    violations.add("Duplicate concept ID in output: " + id);
                }
                actual.put(id, new String[]{cols[1], cols[2], cols[3]});
            }
        }

        // --- Concept membership ---
        for (String id : expected.keySet()) {
            if (!actual.containsKey(id)) {
                String[] e = expected.get(id);
                violations.add(String.format(
                    "Concept %s is missing from output (expected active=%s, fsn='%s')",
                    id, e[0], e[1]));
            }
        }
        for (String id : actual.keySet()) {
            if (!expected.containsKey(id)) {
                violations.add(String.format(
                    "Concept %s is in output but should not be (absent from source or filtered out)", id));
            }
        }

        // --- Per-concept value correctness ---
        for (String id : expected.keySet()) {
            if (!actual.containsKey(id)) continue;
            String[] exp = expected.get(id);
            String[] act = actual.get(id);

            if (!exp[0].equals(act[0])) {
                violations.add(String.format(
                    "Concept %s: active flag — source says '%s', output has '%s'",
                    id, exp[0], act[0]));
            }
            if (!exp[1].equals(act[1])) {
                violations.add(String.format(
                    "Concept %s: FSN — source active FSN is '%s', output has '%s'",
                    id, exp[1], act[1]));
            }
            if (!exp[2].equals(act[2])) {
                violations.add(String.format(
                    "Concept %s: preferred term — source preferred synonym is '%s', output has '%s'",
                    id, exp[2], act[2]));
            }

            // --- FSN format: must end with (semantic-tag) ---
            String fsn = act[1];
            if (!fsn.isEmpty()) {
                int lastOpen  = fsn.lastIndexOf('(');
                int lastClose = fsn.lastIndexOf(')');
                boolean validFormat = lastOpen  != -1
                                   && lastClose == fsn.length() - 1
                                   && lastOpen  <  lastClose;
                if (!validFormat) {
                    violations.add(String.format(
                        "Concept %s: FSN does not end with a semantic tag '(tag)': '%s'", id, fsn));
                }
            }
        }

        return violations;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Extracts the three required RF2 files from a ZIP to a temp directory.
     *
     * @return [conceptsPath, descriptionsPath, preferencesPath, tempDirPath]
     */
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
            throw new IOException(
                "Could not find all required RF2 files in ZIP. " +
                "Expected entries matching: sct2_Concept_Snapshot_INT, " +
                "sct2_Description_Snapshot-en_INT, der2_cRefset_LanguageSnapshot-en_INT");
        }

        return new Path[]{concepts, descriptions, preferences, tempDir};
    }

    private static Path extractEntry(java.util.zip.ZipFile zip,
            java.util.zip.ZipEntry entry, Path destDir) throws IOException {
        Path dest = destDir.resolve(Paths.get(entry.getName()).getFileName());
        try (InputStream is = zip.getInputStream(entry)) {
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static void deleteTempDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        } catch (IOException ignored) {
        }
    }

    private static void writeReport(String reportPath, String zipPath, String outputPath,
            boolean activeOnly, String inactiveSince,
            int expectedCount, List<String> violations) throws IOException {

        boolean passed    = violations.isEmpty();
        String  timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        try (PrintWriter w = new PrintWriter(new FileWriter(reportPath))) {
            w.println("GPS Extraction Validation Report");
            w.println("================================");
            w.println("Generated:     " + timestamp);
            w.println("RF2 source:    " + zipPath);
            w.println("GPS output:    " + outputPath);
            w.println("Flags:         " + buildFlagsString(activeOnly, inactiveSince));
            w.println();
            w.println("Checks performed");
            w.println("----------------");
            w.println("  [1] Output file header is exactly: ConceptID | Active | FSN | USPreferredTerm");
            w.println("  [2] Every data row has exactly 4 tab-separated columns");
            w.println("  [3] No concept ID appears more than once in the output");
            w.println("  [4] Every concept in the source RF2 (after applying filter flags) is present in the output");
            w.println("  [5] No concept appears in the output that is absent from the source RF2 or was filtered out");
            w.println("  [6] The active flag on each row matches the source concept file");
            w.println("  [7] The FSN on each row is the active FSN from the source descriptions file");
            w.println("  [8] The preferred term on each row is the active Preferred synonym per the language refset");
            w.println("  [9] Every non-empty FSN ends with a parenthesised semantic tag, e.g. '(disorder)'");
            w.println();
            w.println("Source concepts (after filter): " + expectedCount);
            w.println("Violations found:               " + violations.size());
            w.println();
            w.println("Result: " + (passed ? "PASS" : "FAIL"));

            if (!violations.isEmpty()) {
                w.println();
                w.println("Violations");
                w.println("----------");
                for (int i = 0; i < violations.size(); i++) {
                    w.printf("[%d] %s%n", i + 1, violations.get(i));
                }
            }
        }

        System.out.println("Validation complete: " + (passed ? "PASS" : "FAIL") +
                " (" + violations.size() + " violation(s))");
        System.out.println("Report written to: " + reportPath);
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
