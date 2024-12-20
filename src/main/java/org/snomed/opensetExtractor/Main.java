package org.snomed.opensetextractor;

public class Main {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar <jarfile> <command> [args...]");
            System.err.println("Commands:");
            System.err.println("  extract-terms <concepts-file> <descriptions-file> <preferences-file> <output-file>");
            System.err.println("  extract-tags <input-file> <semantic-tag1> [semantic-tag2 ...]");
            return;
        }

        String command = args[0];
        String[] remainingArgs = new String[args.length - 1];
        System.arraycopy(args, 1, remainingArgs, 0, args.length - 1);

        switch (command) {
            case "extract-terms":
                ExtractTerms.main(remainingArgs);
                break;
            case "extract-tags":
                ExtractSemanticTags.main(remainingArgs);
                break;
            default:
                System.err.println("Unknown command: " + command);
                break;
        }
    }
}