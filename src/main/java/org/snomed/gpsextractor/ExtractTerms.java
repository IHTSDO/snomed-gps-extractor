package org.snomed.gpsextractor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class ExtractTerms {
    // Constants for SNOMED CT identifiers
    private static final String FSN_TYPE_ID = "900000000000003001";
    private static final String SYNONYM_TYPE_ID = "900000000000013009";
    private static final String PREFERRED_ACCEPTABILITY_ID = "900000000000548007";

    private static final String ACTIVE_FLAG = "1";

    // File processing constants
    private static final String TAB_DELIMITER = "\t";
    private static final int CONCEPT_ID_INDEX = 0;
    private static final int EFFECTIVE_TIME_INDEX = 1;
    private static final int ACTIVE_INDEX = 2;
    private static final int TYPE_ID_INDEX = 6;
    private static final int TERM_INDEX = 7;

    // Preferences file constants
    private static final int PREF_REFERENCED_COMPONENT_ID_INDEX = 5;
    private static final int PREF_ACCEPTABILITY_ID_INDEX = 6;

    public static void main(String[] args) {
        try {
            boolean activeOnly = false;
            String inactiveSinceDate = null;
            String[] fileArgs = args;

            // Parse flags
            int argIndex = 0;
            while (argIndex < args.length && args[argIndex].startsWith("--")) {
                if (args[argIndex].equals("--active-only")) {
                    activeOnly = true;
                    argIndex++;
                } else if (args[argIndex].equals("--inactive-since")) {
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
                } else {
                    break;
                }
            }

            fileArgs = new String[args.length - argIndex];
            System.arraycopy(args, argIndex, fileArgs, 0, args.length - argIndex);

            if (fileArgs.length == 2 && fileArgs[0].toLowerCase().endsWith(".zip")) {
                processZip(fileArgs[0], fileArgs[1], activeOnly, inactiveSinceDate);
            } else if (fileArgs.length == 4) {
                processFiles(fileArgs[0], fileArgs[1], fileArgs[2], fileArgs[3], activeOnly, inactiveSinceDate);
            } else {
                System.err.println("Usage: extract-terms [--active-only] [--inactive-since YYYYMMDD] <rf2-zip-file> <output-file>");
            }
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    private static void processZip(String zipFile, String outputFile, boolean activeOnly, String inactiveSinceDate) throws IOException {
        Path tempDir = Files.createTempDirectory("snomed-extract");
        try {
            String conceptsFile = null;
            String descriptionsFile = null;
            String preferencesFile = null;

            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (name.contains("Snapshot/Terminology/sct2_Concept_Snapshot_INT")) {
                        conceptsFile = extractFile(zip, entry, tempDir);
                    } else if (name.contains("Snapshot/Terminology/sct2_Description_Snapshot-en_INT")) {
                        descriptionsFile = extractFile(zip, entry, tempDir);
                    } else if (name.contains("Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT")) {
                        preferencesFile = extractFile(zip, entry, tempDir);
                    }
                }
            }

            if (conceptsFile != null && descriptionsFile != null && preferencesFile != null) {
                processFiles(conceptsFile, descriptionsFile, preferencesFile, outputFile, activeOnly, inactiveSinceDate);
            } else {
                System.err.println("Could not find all required files in ZIP.");
            }
        } finally {
            // Cleanup temp directory
            try (java.util.stream.Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    private static String extractFile(java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry, Path destDir)
            throws IOException {
        Path destFile = destDir.resolve(Paths.get(entry.getName()).getFileName());
        try (java.io.InputStream is = zip.getInputStream(entry)) {
            Files.copy(is, destFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return destFile.toString();
    }

    private static void processFiles(String conceptsFile, String descriptionsFile,
            String preferencesFile, String outputFile, boolean activeOnly, String inactiveSinceDate) throws IOException {
        Map<String, String> concepts = readConcepts(conceptsFile, activeOnly, inactiveSinceDate);
        Map<String, String> descriptionPreferences = readPreferences(preferencesFile);
        Map<String, String> fsnDescriptions = new HashMap<>();
        Map<String, String> preferredTerms = new HashMap<>();

        readDescriptions(descriptionsFile, descriptionPreferences, fsnDescriptions, preferredTerms, concepts);

        writeOutput(outputFile, concepts, fsnDescriptions, preferredTerms);
    }

    private static Map<String, String> readConcepts(String filename, boolean activeOnly, String inactiveSinceDate) throws IOException {
        Map<String, String> concepts = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(TAB_DELIMITER);
                if (parts.length > ACTIVE_INDEX) {
                    boolean isActive = ACTIVE_FLAG.equals(parts[ACTIVE_INDEX]);
                    if (activeOnly && !isActive) {
                        continue;
                    }
                    // If inactive-since date is set, exclude inactive concepts with effectiveTime before that date
                    if (inactiveSinceDate != null && !isActive && parts.length > EFFECTIVE_TIME_INDEX) {
                        String effectiveTime = parts[EFFECTIVE_TIME_INDEX];
                        if (effectiveTime.compareTo(inactiveSinceDate) < 0) {
                            continue;
                        }
                    }
                    concepts.put(parts[CONCEPT_ID_INDEX], parts[ACTIVE_INDEX]);
                }
            }
        }
        return concepts;
    }

    private static Map<String, String> readPreferences(String filename) throws IOException {
        Map<String, String> preferences = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(TAB_DELIMITER);
                if (parts.length > PREF_ACCEPTABILITY_ID_INDEX && ACTIVE_FLAG.equals(parts[ACTIVE_INDEX])) {
                    preferences.put(parts[PREF_REFERENCED_COMPONENT_ID_INDEX], parts[PREF_ACCEPTABILITY_ID_INDEX]);
                }
            }
        }
        return preferences;
    }

    private static void readDescriptions(String filename,
            Map<String, String> descriptionPreferences,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms,
            Map<String, String> activeConcepts) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                processDescriptionLine(line, descriptionPreferences, fsnDescriptions, preferredTerms, activeConcepts);
            }
        }
    }

    private static void processDescriptionLine(String line,
            Map<String, String> descriptionPreferences,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms,
            Map<String, String> activeConcepts) {
        String[] parts = line.split(TAB_DELIMITER);
        if (!isValidDescriptionLine(parts)) {
            return;
        }

        String descriptionId = parts[0];
        String conceptId = parts[4]; // Concept ID

        // Only process descriptions for concepts we are interested in (active or all,
        // depending on filter)
        if (!activeConcepts.containsKey(conceptId)) {
            return;
        }

        String typeId = parts[TYPE_ID_INDEX];
        String term = parts[TERM_INDEX];

        if (FSN_TYPE_ID.equals(typeId)) {
            fsnDescriptions.put(conceptId, term);
        } else if (SYNONYM_TYPE_ID.equals(typeId)) {
            // Check if this description is preferred in the language refset
            String acceptability = descriptionPreferences.get(descriptionId);
            if (PREFERRED_ACCEPTABILITY_ID.equals(acceptability)) {
                preferredTerms.put(conceptId, term);
            }
        }
    }

    private static boolean isValidDescriptionLine(String[] parts) {
        return parts.length > TERM_INDEX &&
                ACTIVE_FLAG.equals(parts[ACTIVE_INDEX]);
    }

    private static void writeOutput(String filename,
            Map<String, String> concepts,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms) throws IOException {
        int recordCount = 0;
        try (FileWriter writer = new FileWriter(filename)) {
            writeHeader(writer);
            for (String conceptId : concepts.keySet()) {
                String active = concepts.get(conceptId);
                String fsn = fsnDescriptions.get(conceptId);
                String term = preferredTerms.get(conceptId);

                writeTerm(writer, conceptId, active, fsn, term);
                recordCount++;
            }
        }
        System.out.println("Processing complete. Created " + recordCount + " records.");
    }

    private static void writeHeader(FileWriter writer) throws IOException {
        writer.write("id\tactive\tfsn\tterm\n");
    }

    private static void writeTerm(FileWriter writer,
            String conceptId,
            String active,
            String fsn,
            String term) throws IOException {
        writer.write(String.format("%s\t%s\t%s\t%s\n",
                conceptId,
                active,
                fsn != null ? fsn : "",
                term != null ? term : ""));
    }
}