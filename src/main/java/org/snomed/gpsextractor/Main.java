package org.snomed.gpsextractor;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];
        String[] commandArgs = new String[args.length - 1];
        System.arraycopy(args, 1, commandArgs, 0, args.length - 1);

        switch (command) {
            case "server":
                Application.main(commandArgs);
                break;
            case "extract-terms":
                ExtractTerms.main(commandArgs);
                break;
            case "extract-tags":
                ExtractSemanticTags.main(commandArgs);
                break;
            case "validate":
                GpsValidator.main(commandArgs);
                break;
            default:
                System.err.println("Unknown command: " + command);
                printUsage();
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar <jarfile> <command> [args...]");
        System.err.println("Commands:");
        System.err.println("  server                     - Start the web interface on port 8080");
        System.err.println("  extract-terms [--active-only] [--inactive-since YYYYMMDD] <rf2-zip-file> <output-file>");
        System.err.println("  extract-tags [--active-only] <input-file> <semantic-tag1> [semantic-tag2 ...]");
        System.err.println("  validate [--active-only] [--inactive-since YYYYMMDD] <rf2-zip> <gps-output-tsv> <report-file>");
    }
}