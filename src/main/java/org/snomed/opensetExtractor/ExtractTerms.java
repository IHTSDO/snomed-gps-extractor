package org.snomed.opensetextractor;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ExtractTerms {
    // Constants for SNOMED CT identifiers
    private static final String FSN_TYPE_ID = "900000000000003001";
    private static final String SYNONYM_TYPE_ID = "900000000000013009";
    private static final String PREFERRED_ACCEPTABILITY_ID = "900000000000548007";
    private static final String ACCEPTABILITY_REFSET_ID = "900000000000509007";
    private static final String ACTIVE_FLAG = "1";

    // File processing constants
    private static final String TAB_DELIMITER = "\t";
    private static final int CONCEPT_ID_INDEX = 0;
    private static final int ACTIVE_INDEX = 2;
    private static final int TYPE_ID_INDEX = 6;
    private static final int TERM_INDEX = 7;

    public static void main(String[] args) {
        try {
            if (!validateArgs(args)) {
                return;
            }

            processFiles(args[0], args[1], args[2], args[3]);
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    private static void processFiles(String conceptsFile, String descriptionsFile,
            String preferencesFile, String outputFile) throws IOException {
        Map<String, String> activeConcepts = readActiveConcepts(conceptsFile);
        Map<String, String> fsnDescriptions = new HashMap<>();
        Map<String, String> preferredTerms = new HashMap<>();
        
        readDescriptions(descriptionsFile, fsnDescriptions, preferredTerms);
        
        writeOutput(outputFile, activeConcepts, fsnDescriptions, preferredTerms);
    }

    private static boolean validateArgs(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: java ExtractTerms <concepts-rf2-file> <descriptions-rf2-file> " +
                    "<languagePreferences-rf2-file> <output-file>");
            return false;
        }
        return true;
    }

    private static Map<String, String> readActiveConcepts(String filename) throws IOException {
        Map<String, String> concepts = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(TAB_DELIMITER);
                if (parts.length > ACTIVE_INDEX && ACTIVE_FLAG.equals(parts[ACTIVE_INDEX])) {
                    concepts.put(parts[CONCEPT_ID_INDEX], ACTIVE_FLAG);  // Store just the active flag
                }
            }
        }
        return concepts;
    }

    private static void readDescriptions(String filename,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                processDescriptionLine(line, fsnDescriptions, preferredTerms);
            }
        }
    }

    private static void processDescriptionLine(String line,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms) {
        String[] parts = line.split(TAB_DELIMITER);
        if (!isValidDescriptionLine(parts)) {
            return;
        }

        String conceptId = parts[4];  // Concept ID
        String typeId = parts[TYPE_ID_INDEX];
        String term = parts[TERM_INDEX];

        if (FSN_TYPE_ID.equals(typeId)) {
            fsnDescriptions.put(conceptId, term);
        } else if (SYNONYM_TYPE_ID.equals(typeId)) {
            preferredTerms.put(conceptId, term);
        }
    }

    private static boolean isValidDescriptionLine(String[] parts) {
        return parts.length > TYPE_ID_INDEX &&
                ACTIVE_FLAG.equals(parts[ACTIVE_INDEX]);
    }

    private static void writeOutput(String filename,
            Map<String, String> activeConcepts,
            Map<String, String> fsnDescriptions,
            Map<String, String> preferredTerms) throws IOException {
        int recordCount = 0;
        try (FileWriter writer = new FileWriter(filename)) {
            writeHeader(writer);
            for (String conceptId : activeConcepts.keySet()) {
                String fsn = fsnDescriptions.get(conceptId);
                String term = preferredTerms.get(conceptId);
                
                writeTerm(writer, conceptId, fsn, term);
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
            String fsn,
            String term) throws IOException {
        writer.write(String.format("%s\t%s\t%s\t%s\n",
                conceptId,
                "1",  // active (we only store active concepts)
                fsn != null ? fsn : "",
                term != null ? term : ""));
    }
}