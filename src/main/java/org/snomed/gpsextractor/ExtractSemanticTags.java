package org.snomed.gpsextractor;

import java.io.*;
import java.nio.file.*;
import java.util.Objects;

public class ExtractSemanticTags {
    private static final int SEMANTIC_TAG_COLUMN = 2;
    private static final String OUTPUT_FILE_SUFFIX = "_records.tsv";
    private static final String TSV_DELIMITER = "\t";

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                throw new IllegalArgumentException("Insufficient arguments provided.");
            }

            boolean activeOnly = false;
            int argIndex = 0;

            if (args[0].equals("--active-only")) {
                activeOnly = true;
                argIndex++;
            }

            if (args.length <= argIndex + 1) {
                throw new IllegalArgumentException("Insufficient arguments provided after flags.");
            }

            String inputFile = args[argIndex];
            argIndex++;

            String[] semanticTags = new String[args.length - argIndex];
            System.arraycopy(args, argIndex, semanticTags, 0, args.length - argIndex);

            validateInputFile(inputFile);
            validateSemanticTags(semanticTags);

            Path inputPath = Paths.get(inputFile);
            Path outputPath = inputPath.getParent() == null
                    ? Paths.get(generateOutputFileName(String.join("_", semanticTags)))
                    : inputPath.getParent().resolve(generateOutputFileName(String.join("_", semanticTags)));
            processFile(inputFile, outputPath.toString(), semanticTags, activeOnly);

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
            throw e;
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void validateInputFile(String inputFile) throws IOException {
        Path path = Paths.get(inputFile);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Input file does not exist: " + inputFile);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("Input file is not readable: " + inputFile);
        }
        if (Files.size(path) == 0) {
            throw new IllegalArgumentException("Input file is empty: " + inputFile);
        }
    }

    private static void validateSemanticTags(String[] semanticTags) {
        if (semanticTags.length == 0) {
            throw new IllegalArgumentException("At least one semantic tag must be provided");
        }
        for (String tag : semanticTags) {
            if (tag.trim().isEmpty()) {
                throw new IllegalArgumentException("Semantic tags cannot be empty");
            }
        }
    }

    private static String generateOutputFileName(String semanticTag) {
        return sanitizeFileName(semanticTag + OUTPUT_FILE_SUFFIX);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void processFile(String inputFile, String outputFile, String[] semanticTags, boolean activeOnly)
            throws IOException {
        Path outputPath = Paths.get(outputFile);

        // Delete the path if it exists (whether file or directory)
        if (Files.exists(outputPath)) {
            if (Files.isDirectory(outputPath)) {
                Files.delete(outputPath); // Will only delete if directory is empty
            } else {
                Files.deleteIfExists(outputPath);
            }
        }

        // Create parent directories if needed
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile));
                BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            processStream(reader, writer, semanticTags, activeOnly);
        }
        System.out.println("Results written to: " + outputFile);
    }

    public static void processStream(BufferedReader reader, BufferedWriter writer, String[] semanticTags)
            throws IOException {
        processStream(reader, writer, semanticTags, false);
    }

    public static void processStream(BufferedReader reader, BufferedWriter writer, String[] semanticTags,
            boolean activeOnly) throws IOException {
        String headerLine = Objects.requireNonNull(reader.readLine(), "Header line cannot be null");
        writer.write(headerLine);
        writer.newLine();

        int recordsFound = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String[] columns = line.split(TSV_DELIMITER);

            // Check active status if requested
            if (activeOnly) {
                // Assuming active column is at index 1 (based on ExtractTerms output)
                if (columns.length > 1 && !"1".equals(columns[1])) {
                    continue;
                }
            }

            if (columns.length > SEMANTIC_TAG_COLUMN &&
                    matchesAnyTag(columns[SEMANTIC_TAG_COLUMN], semanticTags)) {
                writer.write(line);
                writer.newLine();
                recordsFound++;
            }
        }

        System.out.println(String.format("Found %d records with semantic tags '%s'",
                recordsFound, String.join("', '", semanticTags)));
    }

    private static boolean matchesAnyTag(String column, String[] semanticTags) {
        // Extract the semantic tag from within parentheses at the end of the FSN
        int lastOpenParen = column.lastIndexOf('(');
        int lastCloseParen = column.lastIndexOf(')');

        if (lastOpenParen == -1 || lastCloseParen == -1 || lastOpenParen > lastCloseParen) {
            return false;
        }

        // Extract the tag without parentheses
        String semanticTag = column.substring(lastOpenParen + 1, lastCloseParen);

        for (String tag : semanticTags) {
            if (tag.equals(semanticTag)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java ExtractSemanticTags [--active-only] <input_file.tsv> <semantic_tag1> [semantic_tag2 ...]");
        System.out.println("  --active-only: (Optional) If specified, only active concepts will be extracted.");
        System.out.println("  input_file.tsv: Path to the input TSV file");
        System.out.println("  semantic_tags: One or more semantic tags to search for from the SNOMED CT OpenSet file ");
    }
}