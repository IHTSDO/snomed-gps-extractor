import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class ExtractSemanticTags {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java FindingRecords <input_file.tsv> <semantic_tag>");
            return;
        }

        String inputFile = args[0];
        String semanticTag = args[1];
        String outputFile = semanticTag + "_records.tsv"; // Output filename

        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile));
             BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {

            String line;
            reader.readLine(); // Skip header (assuming your TSV has a header)
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split("\t");
                if (columns.length > 2 && columns[2].contains(semanticTag)) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            System.out.println("Records with semantic tag '" + semanticTag + "' written to " + outputFile);

        } catch (IOException e) {
            System.err.println("Error processing files: " + e.getMessage());
        }
    }
}