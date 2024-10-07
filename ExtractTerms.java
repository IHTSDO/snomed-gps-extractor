import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ExtractTerms {

    public static void main(String[] args) throws IOException {

        // Input file paths
        String conceptsFile = "concepts.txt";
        String descriptionsFile = "descriptions.txt";
        String prefsFile = "prefs.txt";

        // Output file path
        String outputFile = "output.tsv";

        // Read concepts.txt and store active concepts in a map
        Map<String, String> activeConcepts = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(conceptsFile))) {
            String line = br.readLine(); // Skip header
            while ((line = br.readLine()) != null) {
                String[] values = line.split("\t");
                if (values[2].equals("1")) {
                    activeConcepts.put(values[0], values[2]);
                }
            }
        }

        // Read descriptions.txt and store relevant descriptions in maps
        Map<String, String> fsnDescriptions = new HashMap<>();
        Map<String, String> termDescriptions = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(descriptionsFile))) {
            String line = br.readLine(); // Skip header
            while ((line = br.readLine()) != null) {
                String[] values = line.split("\t");
                if (values[2].equals("1")) {
                    if (values[6].equals("900000000000003001")) {
                        fsnDescriptions.put(values[4], values[7]);
                    } else if (values[6].equals("900000000000013009")) {
                        termDescriptions.put(values[4], values[7]);
                    }
                }
            }
        }

        //  Read prefs.txt and filter relevant terms
        // TODO: Needs to pick up preferred terms
        
        Map<String, String> filteredTerms = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(prefsFile))) {
            String line = br.readLine(); // Skip header
            while ((line = br.readLine()) != null) {
                String[] values = line.split("\t");
                if (values[4].equals("900000000000509007") && values[6].equals("900000000000548007")) {
                    String term = termDescriptions.get(values[5]);
                    if (term != null) {
                        filteredTerms.put(values[5], term);
                    }
                }
            }
        }
        
        // Write the output.tsv file
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("id\tactive\tfsn\tterm\n"); // Write header
            for (Map.Entry<String, String> entry : activeConcepts.entrySet()) {
                String id = entry.getKey();
                String active = entry.getValue();
                String fsn = fsnDescriptions.get(id);
                String term = termDescriptions.get(id);
                if (fsn != null && term != null) {
                    writer.write(id + "\t" + active + "\t" + fsn + "\t" + term + "\n");
                }
            }
        }
    }
}