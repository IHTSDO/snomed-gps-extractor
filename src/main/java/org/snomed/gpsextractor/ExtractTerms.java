package org.snomed.gpsextractor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Extracts GPS (Global Patient Set) data from a SNOMED CT RF2 release.
 *
 * Two output modes:
 *   ZIP input  → produces an output ZIP containing the TSV data file, Readme.txt,
 *                and (optionally) an implementation guide.
 *   File input → writes a raw TSV directly to the given output path (used in
 *                tests and programmatic calls).
 */
public class ExtractTerms {

    // SNOMED CT identifiers
    static final String FSN_TYPE_ID                = "900000000000003001";
    static final String SYNONYM_TYPE_ID            = "900000000000013009";
    static final String PREFERRED_ACCEPTABILITY_ID = "900000000000548007";
    private static final String ACTIVE_FLAG        = "1";

    // Output file naming
    private static final String  GPS_OUTPUT_PREFIX    = "SnomedINTL_GPSRelease_PRODUCTION_";
    private static final String  GPS_OUTPUT_SUFFIX    = "T120000Z.zip";
    static final Pattern         RELEASE_DATE_PATTERN = Pattern.compile("(\\d{8})");
    private static final String  TAB                  = "\t";

    // Concept file column indices
    private static final int CONCEPT_ID_IDX     = 0;
    private static final int EFFECTIVE_TIME_IDX = 1;
    private static final int ACTIVE_IDX         = 2;

    // Description file column indices
    private static final int DESC_ID_IDX      = 0;
    private static final int DESC_ACTIVE_IDX  = 2;
    private static final int DESC_CONCEPT_IDX = 4;
    private static final int DESC_TYPE_IDX    = 6;
    private static final int DESC_TERM_IDX    = 7;

    /**
     * The US English language refset ID.
     *
     * The combined INT language refset file contains entries for BOTH GB English
     * (900000000000508004) and US English (900000000000509007).  A description can
     * appear in BOTH refsets with DIFFERENT acceptability values.  If we load all
     * entries into a flat map, later rows overwrite earlier ones, so the GB entry
     * may corrupt the US acceptability for a description — causing active concepts
     * to produce blank US Preferred Terms.
     *
     * Filtering to US English only guarantees that each description has exactly one
     * acceptability value and that it reflects the US dialect exclusively.
     */
    static final String US_ENGLISH_REFSET_ID = "900000000000509007";

    // Language refset column indices
    // id | effectiveTime | active | moduleId | refsetId | referencedComponentId | acceptabilityId
    private static final int PREF_ACTIVE_IDX    = 2;
    private static final int PREF_REFSET_IDX    = 4;  // refsetId — used to filter to US English
    private static final int PREF_REFC_IDX      = 5;  // referencedComponentId (= descriptionId)
    private static final int PREF_ACCEPT_IDX    = 6;  // acceptabilityId

    // =========================================================================
    // CLI entry point
    // =========================================================================

    public static void main(String[] args) {
        try {
            boolean activeOnly              = false;
            String  inactiveSinceDate       = null;
            String  implementationGuideFile = null;

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
                            inactiveSinceDate = args[argIndex];
                            if (!inactiveSinceDate.matches("\\d{8}")) {
                                System.err.println("Error: --inactive-since date must be in YYYYMMDD format (e.g. 20230101)");
                                return;
                            }
                            argIndex++;
                        } else {
                            System.err.println("Error: --inactive-since requires a date argument (YYYYMMDD)");
                            return;
                        }
                        break;
                    case "--implementation-guide":
                        argIndex++;
                        if (argIndex < args.length) {
                            implementationGuideFile = args[argIndex];
                            argIndex++;
                        } else {
                            System.err.println("Error: --implementation-guide requires a file path argument");
                            return;
                        }
                        break;
                    default:
                        System.err.println("Error: Unknown option: " + args[argIndex]);
                        printUsage();
                        return;
                }
            }

            String[] fileArgs = Arrays.copyOfRange(args, argIndex, args.length);

            if (activeOnly && inactiveSinceDate != null) {
                System.err.println("Warning: --inactive-since has no effect when --active-only is also set.");
            }

            // Validate implementation guide if provided
            if (implementationGuideFile != null) {
                Path igPath = Paths.get(implementationGuideFile).toAbsolutePath().normalize();
                if (!Files.exists(igPath)) {
                    System.err.println("Error: Implementation guide file not found: " + implementationGuideFile);
                    return;
                }
                if (!Files.isReadable(igPath)) {
                    System.err.println("Error: Implementation guide file is not readable: " + implementationGuideFile);
                    return;
                }
            }

            if (fileArgs.length >= 1 && fileArgs[0].toLowerCase().endsWith(".zip")) {
                // ZIP input → produce ZIP output package
                String inputZip = fileArgs[0];
                Path inputPath = Paths.get(inputZip);
                if (!Files.exists(inputPath)) { System.err.println("Error: Input file not found: " + inputZip); return; }
                if (!Files.isReadable(inputPath)) { System.err.println("Error: Input file is not readable: " + inputZip); return; }

                String outputZip = fileArgs.length >= 2 ? fileArgs[1] : deriveOutputFileName(inputZip);
                Path outputDir = Paths.get(outputZip).getParent();
                if (outputDir != null && !Files.exists(outputDir)) {
                    System.err.println("Error: Output directory does not exist: " + outputDir);
                    return;
                }
                System.out.println("Output file: " + outputZip);
                processZip(inputZip, outputZip, activeOnly, inactiveSinceDate, implementationGuideFile);

            } else if (fileArgs.length == 4) {
                // 4 individual files → write raw TSV directly (used by tests and direct invocation)
                processFiles(fileArgs[0], fileArgs[1], fileArgs[2], fileArgs[3],
                    activeOnly, inactiveSinceDate);
            } else {
                printUsage();
            }
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    // =========================================================================
    // File-name helpers (package-private for tests)
    // =========================================================================

    static String deriveOutputFileName(String inputZip) {
        String baseName = Paths.get(inputZip).getFileName().toString();
        Matcher m = RELEASE_DATE_PATTERN.matcher(baseName);
        String date = m.find() ? m.group(1) : "UNKNOWN";
        return GPS_OUTPUT_PREFIX + date + GPS_OUTPUT_SUFFIX;
    }

    static String extractDateFromFilename(String filename) {
        String baseName = Paths.get(filename).getFileName().toString();
        Matcher m = RELEASE_DATE_PATTERN.matcher(baseName);
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    static String formatDateAsMonthYear(String yyyymmdd) {
        try {
            LocalDate date = LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE);
            return date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        } catch (Exception e) {
            return yyyymmdd;
        }
    }

    // =========================================================================
    // ZIP validation
    // =========================================================================

    private static boolean isValidZip(String filePath) {
        try (InputStream is = Files.newInputStream(Paths.get(filePath))) {
            byte[] magic = new byte[4];
            if (is.read(magic) < 4) return false;
            return magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04;
        } catch (IOException e) {
            return false;
        }
    }

    // =========================================================================
    // ZIP processing pipeline  (RF2 ZIP → GPS ZIP)
    // =========================================================================

    private static void processZip(String zipFile, String outputZip,
            boolean activeOnly, String inactiveSinceDate,
            String implementationGuideFile) throws IOException {

        if (!isValidZip(zipFile)) {
            System.err.println("Error: Input file is not a valid ZIP file: " + zipFile);
            return;
        }

        Path tempDir = Files.createTempDirectory("snomed-extract");
        try {
            String conceptsFile     = null;
            String descriptionsFile = null;
            String preferencesFile  = null;

            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.contains("Snapshot/Terminology/sct2_Concept_Snapshot_INT")) {
                        conceptsFile = extractFileSecure(zip, entry, tempDir);
                    } else if (name.contains("Snapshot/Terminology/sct2_Description_Snapshot-en_INT")) {
                        descriptionsFile = extractFileSecure(zip, entry, tempDir);
                    } else if (name.contains("Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT")) {
                        preferencesFile = extractFileSecure(zip, entry, tempDir);
                    }
                }
            }

            if (conceptsFile == null || descriptionsFile == null || preferencesFile == null) {
                System.err.println("Error: Could not find all required RF2 files in ZIP. Missing:");
                if (conceptsFile == null)     System.err.println("  - sct2_Concept_Snapshot_INT");
                if (descriptionsFile == null) System.err.println("  - sct2_Description_Snapshot-en_INT");
                if (preferencesFile == null)  System.err.println("  - der2_cRefset_LanguageSnapshot-en_INT");
                return;
            }

            Map<String, String>  concepts         = readConcepts(conceptsFile, activeOnly, inactiveSinceDate);
            Map<String, String>  descPrefs        = readPreferences(preferencesFile);
            Map<String, String>  fsnDescriptions  = new HashMap<>();
            Map<String, String>  preferredTerms   = new HashMap<>();
            readDescriptions(descriptionsFile, descPrefs, fsnDescriptions, preferredTerms, concepts);

            writeZipPackage(outputZip, concepts, fsnDescriptions, preferredTerms, implementationGuideFile);

        } finally {
            deleteTempDir(tempDir);
        }
    }

    /**
     * Maximum permitted uncompressed size for a single RF2 file extracted from a ZIP
     * (2 GB).  This guards against zip-bomb / decompression attacks where a small
     * compressed payload expands to an unbounded amount of data on disk or in memory.
     * The largest real-world SNOMED CT release files are well under 1 GB uncompressed.
     */
    static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB

    /**
     * Security-hardened file extraction from ZIP.
     * <ul>
     *   <li>Strips any directory component from the entry name to prevent zip-slip
     *       path traversal.</li>
     *   <li>Validates the entry's declared uncompressed size before writing to prevent
     *       zip-bomb / decompression attacks.</li>
     *   <li>Counts bytes actually written and aborts if the stream exceeds the limit,
     *       catching entries that declare a misleading size of -1 or 0.</li>
     * </ul>
     */
    private static String extractFileSecure(java.util.zip.ZipFile zip,
            ZipEntry entry, Path destDir) throws IOException {
        // Use only the filename component to prevent path traversal
        Path destFile = destDir.resolve(Paths.get(entry.getName()).getFileName()).normalize();
        if (!destFile.startsWith(destDir.normalize())) {
            throw new IOException("Zip entry path traversal detected: " + entry.getName());
        }
        // Check declared uncompressed size (may be -1 for streaming entries)
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_ENTRY_UNCOMPRESSED_BYTES) {
            throw new IOException(String.format(
                "Zip entry '%s' declares an uncompressed size of %d bytes which exceeds the "
                + "permitted maximum of %d bytes. Aborting to prevent a decompression attack.",
                entry.getName(), declaredSize, MAX_ENTRY_UNCOMPRESSED_BYTES));
        }
        // Stream with a counted wrapper to catch entries whose declared size is 0 or -1
        try (InputStream is = zip.getInputStream(entry)) {
            long written = Files.copy(new LimitedInputStream(is, MAX_ENTRY_UNCOMPRESSED_BYTES,
                    entry.getName()), destFile, StandardCopyOption.REPLACE_EXISTING);
            if (written > MAX_ENTRY_UNCOMPRESSED_BYTES) {
                Files.deleteIfExists(destFile);
                throw new IOException(String.format(
                    "Zip entry '%s' expanded to %d bytes, exceeding the permitted maximum of %d bytes.",
                    entry.getName(), written, MAX_ENTRY_UNCOMPRESSED_BYTES));
            }
        }
        return destFile.toString();
    }

    /**
     * An {@link InputStream} decorator that throws an {@link IOException} if more than
     * {@code maxBytes} bytes are read.  Used to protect against zip-bomb attacks where
     * the declared entry size is 0 or -1 (unknown).
     */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long maxBytes;
        private final String entryName;
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
                + "during extraction. Aborting to prevent a decompression attack.", entryName, maxBytes));
        }
    }

    // =========================================================================
    // File processing pipeline  (individual RF2 files → raw TSV)
    // =========================================================================

    /**
     * Reads three individual RF2 files and writes a raw TSV to {@code outputFile}.
     * This path is used for direct file-to-file invocation and by the test suite.
     */
    static void processFiles(String conceptsFile, String descriptionsFile,
            String preferencesFile, String outputFile,
            boolean activeOnly, String inactiveSinceDate) throws IOException {

        Map<String, String> concepts        = readConcepts(conceptsFile, activeOnly, inactiveSinceDate);
        Map<String, String> descPrefs       = readPreferences(preferencesFile);
        Map<String, String> fsnDescriptions = new HashMap<>();
        Map<String, String> preferredTerms  = new HashMap<>();
        readDescriptions(descriptionsFile, descPrefs, fsnDescriptions, preferredTerms, concepts);

        writeTsv(outputFile, concepts, fsnDescriptions, preferredTerms);
    }

    // =========================================================================
    // RF2 readers
    // =========================================================================

    private static Map<String, String> readConcepts(String filename,
            boolean activeOnly, String inactiveSinceDate) throws IOException {
        Map<String, String> concepts = new LinkedHashMap<>();
        try (BufferedReader r = Files.newBufferedReader(Paths.get(filename), StandardCharsets.UTF_8)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(TAB);
                if (p.length <= ACTIVE_IDX) continue;
                boolean isActive = ACTIVE_FLAG.equals(p[ACTIVE_IDX]);
                if (activeOnly && !isActive) continue;
                if (inactiveSinceDate != null && !isActive && p.length > EFFECTIVE_TIME_IDX
                        && p[EFFECTIVE_TIME_IDX].compareTo(inactiveSinceDate) < 0) continue;
                concepts.put(p[CONCEPT_ID_IDX], p[ACTIVE_IDX]);
            }
        }
        return concepts;
    }

    /**
     * Reads the combined INT language refset file and returns a map of
     * descriptionId → acceptabilityId containing ONLY active entries from the
     * US English refset (refsetId 900000000000509007).
     *
     * <p><strong>Why US English only?</strong><br>
     * The INT release ships a single language refset file that contains rows for
     * BOTH GB English (900000000000508004) and US English (900000000000509007).
     * A description can appear in BOTH refsets with DIFFERENT acceptability values.
     * For example, a term may be PREFERRED in GB English but ACCEPTABLE in US
     * English (or not present in US at all).  If we load all rows into a flat map,
     * the last-write-wins behaviour means the GB row can overwrite the US row,
     * causing the wrong (or absent) acceptability value to be used when resolving
     * the US Preferred Term — producing a blank USPreferredTerm in the output.
     *
     * <p>By filtering to US English only we guarantee:
     * <ul>
     *   <li>Each description has exactly one acceptability entry (its US value).</li>
     *   <li>GB acceptability can never corrupt the US lookup.</li>
     *   <li>Descriptions absent from the US refset correctly yield no preferred term.</li>
     * </ul>
     */
    private static Map<String, String> readPreferences(String filename) throws IOException {
        Map<String, String> prefs = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(Paths.get(filename), StandardCharsets.UTF_8)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(TAB);
                if (p.length <= PREF_ACCEPT_IDX) continue;
                if (!ACTIVE_FLAG.equals(p[PREF_ACTIVE_IDX])) continue; // active rows only
                if (!US_ENGLISH_REFSET_ID.equals(p[PREF_REFSET_IDX])) continue; // US English only
                prefs.put(p[PREF_REFC_IDX], p[PREF_ACCEPT_IDX]);
            }
        }
        return prefs;
    }

    /**
     * Reads the descriptions file, populating FSN and preferred-term maps.
     *
     * Only ACTIVE descriptions are processed.  A preferred term requires the
     * description itself to be active AND its language-refset entry to carry
     * the PREFERRED acceptability ID.
     */
    private static void readDescriptions(String filename,
            Map<String, String> descPrefs,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms,
            Map<String, String> qualifiedConcepts) throws IOException {

        try (BufferedReader r = Files.newBufferedReader(Paths.get(filename), StandardCharsets.UTF_8)) {
            r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(TAB);
                if (p.length <= DESC_TERM_IDX) continue;
                if (!ACTIVE_FLAG.equals(p[DESC_ACTIVE_IDX])) continue; // active descriptions only

                String descId    = p[DESC_ID_IDX];
                String conceptId = p[DESC_CONCEPT_IDX];
                String typeId    = p[DESC_TYPE_IDX];
                String term      = p[DESC_TERM_IDX];

                if (!qualifiedConcepts.containsKey(conceptId)) continue;

                if (FSN_TYPE_ID.equals(typeId)) {
                    fsnDescriptions.put(conceptId, term);
                } else if (SYNONYM_TYPE_ID.equals(typeId)
                        && PREFERRED_ACCEPTABILITY_ID.equals(descPrefs.get(descId))) {
                    preferredTerms.put(conceptId, term);
                }
            }
        }
    }

    // =========================================================================
    // Output writers
    // =========================================================================

    /**
     * Writes a raw TSV file.  Used by the 4-arg (individual-files) code path.
     */
    private static void writeTsv(String outputFile,
            Map<String, String> concepts,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms) throws IOException {

        int count = 0;
        try (Writer w = new OutputStreamWriter(
                Files.newOutputStream(Paths.get(outputFile)), StandardCharsets.UTF_8)) {
            w.write("ConceptID\tActive\tFSN\tUSPreferredTerm\n");
            for (Map.Entry<String, String> e : concepts.entrySet()) {
                String id   = e.getKey();
                String fsn  = fsnDescriptions.getOrDefault(id, "");
                String term = preferredTerms.getOrDefault(id, "");
                w.write(id + "\t" + e.getValue() + "\t" + fsn + "\t" + term + "\n");
                count++;
            }
        }
        System.out.println("Processing complete. Created " + count + " records.");
        System.out.println("Output written to: " + outputFile);
    }

    /**
     * Writes the GPS release ZIP package, containing:
     *   1. The TSV data file
     *   2. Readme.txt
     *   3. The implementation guide (if provided)
     *
     * Used by the ZIP-input code path.
     */
    private static void writeZipPackage(String outputZipPath,
            Map<String, String> concepts,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms,
            String implementationGuideFile) throws IOException {

        String tsvEntryName       = Paths.get(outputZipPath.replaceAll("\\.zip$", ".txt"))
                                        .getFileName().toString();
        String effectiveDate      = extractDateFromFilename(outputZipPath);

        List<String> entryNames = new ArrayList<>();
        entryNames.add(tsvEntryName);
        entryNames.add("Readme.txt");
        if (implementationGuideFile != null) {
            entryNames.add(Paths.get(implementationGuideFile).getFileName().toString());
        }

        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(Paths.get(outputZipPath)));
             OutputStreamWriter w = new OutputStreamWriter(zos, StandardCharsets.UTF_8)) {

            // 1. TSV data
            zos.putNextEntry(new ZipEntry(tsvEntryName));
            w.write("ConceptID\tActive\tFSN\tUSPreferredTerm\n");
            for (Map.Entry<String, String> e : concepts.entrySet()) {
                String id   = e.getKey();
                String fsn  = fsnDescriptions.getOrDefault(id, "");
                String term = preferredTerms.getOrDefault(id, "");
                w.write(id + "\t" + e.getValue() + "\t" + fsn + "\t" + term + "\n");
                count++;
            }
            w.flush();
            zos.closeEntry();

            // 2. Readme.txt
            zos.putNextEntry(new ZipEntry("Readme.txt"));
            w.write(buildReadmeContent(effectiveDate, entryNames));
            w.flush();
            zos.closeEntry();

            // 3. Implementation guide (optional)
            if (implementationGuideFile != null) {
                // Security: normalise path to prevent directory traversal
                Path guidePath = Paths.get(implementationGuideFile).toAbsolutePath().normalize();
                String guideEntryName = guidePath.getFileName().toString();
                zos.putNextEntry(new ZipEntry(guideEntryName));
                Files.copy(guidePath, zos);
                zos.closeEntry();
                System.out.println("Implementation guide bundled: " + guideEntryName);
            }
        }
        System.out.println("Processing complete. Created " + count + " records.");
        System.out.println("Output written to: " + outputZipPath);
    }

    // =========================================================================
    // README builder
    // =========================================================================

    /**
     * Builds the Readme.txt content for a GPS release package.
     *
     * Requirement 2: effectiveTime and languageRefsets parameters are removed.
     * URLs are embedded inline (no orphaned lines).
     */
    static String buildReadmeContent(String effectiveDateYYYYMMDD, List<String> zipEntryNames) {
        int    currentYear = LocalDate.now().getYear();
        String monthYear   = formatDateAsMonthYear(effectiveDateYYYYMMDD);

        return "\u00A9 " + currentYear + " International Health Terminology Standards Development Organisation. "
            + "All rights reserved. SNOMED CT\u00AE was originally created by the College of American Pathologists.\n\n"
            + "This document forms part of the SNOMED International Global Patient Set (GPS) release, "
            + "distributed by International Health Terminology Standards Development Organisation, "
            + "trading as SNOMED International, and is subject to the terms of the Creative Commons "
            + "Attribution 4.0 International Public License (https://creativecommons.org/licenses/by/4.0/).\n\n"
            + "Any modification of this document (including without limitation the removal or modification "
            + "of this notice) is prohibited without the express written permission of SNOMED International.\n\n"
            + "Any copy of this document that is not obtained directly from SNOMED International is not "
            + "controlled by SNOMED International, and may have been modified and may be out of date. "
            + "Any recipient of this document who has received it by other means is encouraged to obtain "
            + "a copy directly from SNOMED International (https://www.snomed.org/gps).\n\n"
            + "Dependent International Edition version: " + monthYear + "\n\n"
            + "Scope: This GPS Release package includes all Active concepts from the dependent International "
            + "Edition version, plus all Inactive concepts that were inactivated after the migration to RF2 "
            + "in 20120101.\n\n"
            + "Directory listing:"
            + zipEntryNames.stream().reduce("", (a, n) -> a + "\n    " + n)
            + "\n";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void deleteTempDir(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {}
    }

    private static void printUsage() {
        System.err.println("Usage: extract-terms [--active-only] [--inactive-since YYYYMMDD]");
        System.err.println("                     [--implementation-guide <file>]");
        System.err.println("                     <rf2-zip-file> [output-zip-file]");
        System.err.println("   or: extract-terms <concepts> <descriptions> <preferences> <output-tsv>");
    }
}
