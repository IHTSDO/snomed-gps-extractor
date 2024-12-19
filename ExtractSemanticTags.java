import java.io.*;
import java.nio.file.*;
import java.util.Objects;

public class ExtractSemanticTags {
    private static final int SEMANTIC_TAG_COLUMN = 2;
    private static final String OUTPUT_FILE_SUFFIX = "_records.tsv";
    private static final String TSV_DELIMITER = "\t";

    public static void main(String[] args) {
        try {
            validateArgs(args);
            String inputFile = args[0];
            String[] semanticTags = new String[args.length - 1];
            System.arraycopy(args, 1, semanticTags, 0, args.length - 1);

            validateInputFile(inputFile);
            validateSemanticTags(semanticTags);

            String outputFile = generateOutputFileName(String.join("_", semanticTags));
            processFile(inputFile, outputFile, semanticTags);

        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }

    private static void validateArgs(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Insufficient arguments provided.");
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
        String baseFileName = semanticTag + OUTPUT_FILE_SUFFIX;
        return sanitizeFileName(baseFileName);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void processFile(String inputFile, String outputFile, String[] semanticTags) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile));
                BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {

            String headerLine = Objects.requireNonNull(reader.readLine(), "Header line cannot be null");
            writer.write(headerLine);
            writer.newLine();

            int recordsFound = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split(TSV_DELIMITER);
                if (columns.length > SEMANTIC_TAG_COLUMN &&
                        matchesAnyTag(columns[SEMANTIC_TAG_COLUMN], semanticTags)) {
                    writer.write(line);
                    writer.newLine();
                    recordsFound++;
                }
            }

            System.out.println(String.format("Found %d records with semantic tags '%s'",
                    recordsFound, String.join("', '", semanticTags)));
            System.out.println("Results written to: " + outputFile);
        }
    }

    private static boolean matchesAnyTag(String column, String[] semanticTags) {
        for (String tag : semanticTags) {
            if (column.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage: java ExtractSemanticTags <input_file.tsv> <semantic_tag1> [semantic_tag2 ...]");
        System.out.println("  input_file.tsv: Path to the input TSV file");
        System.out.println("  semantic_tags: One or more semantic tags to search for from the SNOMED CT OpenSet file ");
    }
}