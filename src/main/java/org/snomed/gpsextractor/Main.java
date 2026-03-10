package org.snomed.gpsextractor;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command    = args[0];
        String[] cmdArgs  = new String[args.length - 1];
        System.arraycopy(args, 1, cmdArgs, 0, args.length - 1);

        switch (command) {
            case "server":
                Application.main(cmdArgs);
                break;
            case "extract-terms":
                try {
                    ExtractTerms.main(cmdArgs);
                } catch (java.io.IOException e) {
                    System.err.println("Error processing files: " + e.getMessage());
                }
                break;
            case "extract-tags":
                ExtractSemanticTags.main(cmdArgs);
                break;
            case "validate":
                GpsValidator.main(cmdArgs);
                break;
            case "list-validations":
                GpsValidator.listValidations();
                break;
            case "exception-list":
                ExceptionList.main(cmdArgs);
                break;
            default:
                System.err.println("Unknown command: " + command);
                printUsage();
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar <jarfile> <command> [args...]");
        System.err.println();
        System.err.println("Commands:");
        System.err.println("  server");
        System.err.println("      Start the web interface on port 8080");
        System.err.println();
        System.err.println("  extract-terms [--active-only] [--inactive-since YYYYMMDD]");
        System.err.println("                [--implementation-guide <guide-file>]");
        System.err.println("                <rf2-zip-file> [output-file]");
        System.err.println("      Extract GPS data from an RF2 release ZIP");
        System.err.println();
        System.err.println("  extract-tags [--active-only] <input-file> <semantic-tag1> [tag2 ...]");
        System.err.println("      Filter a GPS TSV by semantic tags");
        System.err.println();
        System.err.println("  validate [--active-only] [--inactive-since YYYYMMDD]");
        System.err.println("           <rf2-zip> <gps-output-tsv> <report-file>");
        System.err.println("      Validate a GPS extraction against its source RF2 release");
        System.err.println();
        System.err.println("  list-validations");
        System.err.println("      List all validation tests with names, severity, and descriptions");
        System.err.println();
        System.err.println("  exception-list <sub-command> <CODE> [concepts-file]");
        System.err.println("      Manage per-assertion concept exception lists:");
        System.err.println("        list   <CODE>                  Show all excepted concept IDs");
        System.err.println("        add    <CODE> <concepts-file>  Add concept IDs from a file");
        System.err.println("        remove <CODE> <concepts-file>  Remove concept IDs in a file");
        System.err.println("      CODE is the stable test identifier from list-validations, e.g. E10, W03");
        System.err.println("      (The enum name, e.g. NO_BLANK_PREFERRED_TERM, is also accepted as a fallback.)");
    }
}
