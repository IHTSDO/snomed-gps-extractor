package org.snomed.opensetExtractor;

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
        Map<String, String> termDescriptions = new HashMap<>();
        Map<String, String> termConcepts = new HashMap<>();

        readDescriptions(descriptionsFile, fsnDescriptions, termDescriptions, termConcepts);
        Map<String, String> filteredTerms = readPreferences(preferencesFile, termDescriptions);

        writeOutput(outputFile, activeConcepts, fsnDescriptions, termDescriptions, termConcepts);
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
                    concepts.put(parts[CONCEPT_ID_INDEX], line);
                }
            }
        }
        return concepts;
    }

    private static void readDescriptions(String filename,
            Map<String, String> fsnDescriptions,
            Map<String, String> termDescriptions,
            Map<String, String> termConcepts) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                processDescriptionLine(line, fsnDescriptions, termDescriptions, termConcepts);
            }
        }
    }

    private static void processDescriptionLine(String line,
            Map<String, String> fsnDescriptions,
            Map<String, String> termDescriptions,
            Map<String, String> termConcepts) {
        String[] parts = line.split(TAB_DELIMITER);
        if (!isValidDescriptionLine(parts)) {
            return;
        }

        String descriptionId = parts[CONCEPT_ID_INDEX];
        String typeId = parts[TYPE_ID_INDEX];
        String term = parts[TERM_INDEX];

        if (FSN_TYPE_ID.equals(typeId)) {
            fsnDescriptions.put(descriptionId, term);
        } else if (SYNONYM_TYPE_ID.equals(typeId)) {
            termDescriptions.put(descriptionId, term);
            termConcepts.put(descriptionId, parts[4]); // Concept ID
        }
    }

    private static boolean isValidDescriptionLine(String[] parts) {
        return parts.length > TYPE_ID_INDEX &&
                ACTIVE_FLAG.equals(parts[ACTIVE_INDEX]);
    }

    private static Map<String, String> readPreferences(String filename,
            Map<String, String> termDescriptions) throws IOException {
        Map<String, String> preferredTerms = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            String line;
            reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                processPreferenceLine(line, preferredTerms, termDescriptions);
            }
        }
        return preferredTerms;
    }

    private static void processPreferenceLine(String line,
            Map<String, String> preferredTerms,
            Map<String, String> termDescriptions) {
        String[] parts = line.split(TAB_DELIMITER);
        if (isValidPreferenceLine(parts)) {
            String descriptionId = parts[5]; // Reference component ID
            String term = termDescriptions.get(descriptionId);
            if (term != null) {
                preferredTerms.put(descriptionId, term);
            }
        }
    }

    private static boolean isValidPreferenceLine(String[] parts) {
        return parts.length > 5 &&
                ACTIVE_FLAG.equals(parts[ACTIVE_INDEX]) &&
                ACCEPTABILITY_REFSET_ID.equals(parts[4]) && // Reference set ID
                PREFERRED_ACCEPTABILITY_ID.equals(parts[6]); // Acceptability ID
    }

    private static void writeOutput(String filename,
            Map<String, String> activeConcepts,
            Map<String, String> fsnDescriptions,
            Map<String, String> termDescriptions,
            Map<String, String> termConcepts) throws IOException {
        try (FileWriter writer = new FileWriter(filename)) {
            writeHeader(writer);
            for (Map.Entry<String, String> entry : termDescriptions.entrySet()) {
                String descriptionId = entry.getKey();
                String conceptId = termConcepts.get(descriptionId);
                if (activeConcepts.containsKey(conceptId)) {
                    writeTerm(writer, descriptionId, entry.getValue(),
                            conceptId, fsnDescriptions.get(conceptId));
                }
            }
        }
    }

    private static void writeHeader(FileWriter writer) throws IOException {
        writer.write("Description ID\tTerm\tConcept ID\tFSN\n");
    }

    private static void writeTerm(FileWriter writer,
            String descriptionId,
            String term,
            String conceptId,
            String fsn) throws IOException {
        writer.write(String.format("%s\t%s\t%s\t%s\n",
                descriptionId, term, conceptId, fsn != null ? fsn : ""));
    }
}