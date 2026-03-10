package org.snomed.gpsextractor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Manages per-assertion exception lists.
 *
 * An exception list is a set of concept IDs excluded from a specific validation test.
 * Tests are identified by their stable unique <em>code</em> (e.g. {@code E10}, {@code W03})
 * as assigned in {@link GpsValidator.ValidationTest}.  The code is accepted on the CLI and
 * is used as the exception-list filename (e.g. {@code .gps-exceptions/E10.txt}).
 *
 * <p>The enum <em>name</em> (e.g. {@code NO_BLANK_PREFERRED_TERM}) is also accepted on the
 * CLI for convenience, and old name-based files left over from earlier versions of this tool
 * are automatically read as a fallback so that existing exception lists continue to work.
 *
 * <p>Lists are stored as plain-text files in a directory called
 * {@code .gps-exceptions/} relative to the current working directory.
 * Each file is named {@code <CODE>.txt} (e.g. {@code E10.txt}) and contains one concept ID
 * per line.  Lines starting with '#' are treated as comments.
 *
 * CLI commands (routed through Main):
 * <pre>
 *   exception-list  list   &lt;CODE|NAME&gt;                    – print all excepted concept IDs
 *   exception-list  add    &lt;CODE|NAME&gt; &lt;concepts-file&gt;    – add concept IDs from a file
 *   exception-list  remove &lt;CODE|NAME&gt; &lt;concepts-file&gt;    – remove concept IDs in a file
 * </pre>
 */
public class ExceptionList {

    /** Directory that holds all exception-list files. */
    static final String EXCEPTIONS_DIR = ".gps-exceptions";

    // =========================================================================
    // Public API used by the validator
    // =========================================================================

    /**
     * Returns the set of concept IDs that are excepted from {@code test}.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>The canonical code-based file, e.g. {@code .gps-exceptions/E10.txt}</li>
     *   <li>The legacy enum-name-based file, e.g. {@code .gps-exceptions/NO_BLANK_PREFERRED_TERM.txt}
     *       — read as a fallback so that exception lists created by earlier versions of this
     *       tool continue to work without any migration step.</li>
     * </ol>
     * If both files exist their contents are merged (union).
     *
     * @param test the validation test whose exceptions to load
     * @return unmodifiable set of excepted concept IDs (never null)
     */
    public static Set<String> getExceptions(GpsValidator.ValidationTest test) {
        Set<String> ids = new LinkedHashSet<>();

        // Primary: code-based file (e.g. E10.txt)
        Path codeFile = getExceptionFile(test);
        if (Files.exists(codeFile)) {
            ids.addAll(loadConceptIds(codeFile));
        }

        // Fallback: legacy name-based file (e.g. NO_BLANK_PREFERRED_TERM.txt)
        Path legacyFile = getLegacyExceptionFile(test);
        if (Files.exists(legacyFile)) {
            ids.addAll(loadConceptIds(legacyFile));
        }

        return Collections.unmodifiableSet(ids);
    }

    // =========================================================================
    // CLI entry point
    // =========================================================================

    /**
     * Entry point for the {@code exception-list} command.
     *
     * @param args sub-command and arguments: {@code <sub-command> <CODE|NAME> [concepts-file]}
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String subCommand = args[0];
        String testKey    = args[1];          // may be a code (E10) or an enum name

        GpsValidator.ValidationTest test = resolveTest(testKey);
        if (test == null) {
            System.err.println("Error: Unknown validation test: '" + testKey + "'");
            System.err.println("Use the test code (e.g. E10) or the enum name (e.g. NO_BLANK_PREFERRED_TERM).");
            System.err.println("Run 'list-validations' to see all codes and names.");
            return;
        }

        switch (subCommand.toLowerCase(Locale.ROOT)) {
            case "list":
                listExceptions(test);
                break;
            case "add":
                if (args.length < 3) { printUsage(); return; }
                addExceptions(test, args[2]);
                break;
            case "remove":
                if (args.length < 3) { printUsage(); return; }
                removeExceptions(test, args[2]);
                break;
            default:
                System.err.println("Unknown sub-command: " + subCommand);
                printUsage();
        }
    }

    // =========================================================================
    // Sub-command implementations
    // =========================================================================

    static void listExceptions(GpsValidator.ValidationTest test) {
        Path file = getExceptionFile(test);
        System.out.println("Exception list for: [" + test.code + "] " + test.name);
        System.out.println("File: " + file.toAbsolutePath());

        Set<String> ids = getExceptions(test);   // union of code + legacy files
        if (ids.isEmpty()) {
            System.out.println("(no exceptions)");
            return;
        }
        System.out.println(ids.size() + " concept(s) excepted:");
        ids.stream().sorted().forEach(id -> System.out.println("  " + id));

        // Warn if a legacy file exists alongside the code file so users know to migrate
        Path legacyFile = getLegacyExceptionFile(test);
        if (Files.exists(legacyFile) && Files.exists(file)) {
            System.out.println();
            System.out.println("Note: a legacy exception file also exists at " + legacyFile.toAbsolutePath());
            System.out.println("Its entries are included above. You may delete it once you have verified the");
            System.out.println("contents are captured in " + file.getFileName() + ".");
        }
    }

    static void addExceptions(GpsValidator.ValidationTest test, String conceptsFilePath) {
        Set<String> toAdd = readConceptIdsFromUserFile(conceptsFilePath);
        if (toAdd == null) return; // error already printed

        // Load existing from the canonical code file only (not the legacy file —
        // we don't want to silently merge on write)
        Set<String> existing = new LinkedHashSet<>(loadConceptIds(getExceptionFile(test)));
        int before = existing.size();
        existing.addAll(toAdd);
        int added = existing.size() - before;

        saveExceptionFile(test, existing);
        System.out.printf("Added %d concept ID(s) to exception list for [%s] %s. Total: %d%n",
            added, test.code, test.name, existing.size());
    }

    static void removeExceptions(GpsValidator.ValidationTest test, String conceptsFilePath) {
        Set<String> toRemove = readConceptIdsFromUserFile(conceptsFilePath);
        if (toRemove == null) return;

        Set<String> existing = new LinkedHashSet<>(loadConceptIds(getExceptionFile(test)));
        int before = existing.size();
        existing.removeAll(toRemove);
        int removed = before - existing.size();

        saveExceptionFile(test, existing);
        System.out.printf("Removed %d concept ID(s) from exception list for [%s] %s. Total: %d%n",
            removed, test.code, test.name, existing.size());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Returns the canonical exception-file path for {@code test}, named by code.
     * Example: {@code .gps-exceptions/E10.txt}
     */
    static Path getExceptionFile(GpsValidator.ValidationTest test) {
        return Paths.get(EXCEPTIONS_DIR, test.code + ".txt");
    }

    /**
     * Returns the legacy exception-file path for {@code test}, named by enum name.
     * Example: {@code .gps-exceptions/NO_BLANK_PREFERRED_TERM.txt}
     * Used only as a read-only fallback; new writes always go to the code-based file.
     */
    static Path getLegacyExceptionFile(GpsValidator.ValidationTest test) {
        return Paths.get(EXCEPTIONS_DIR, test.name() + ".txt");
    }

    /**
     * Loads concept IDs from an exception file, ignoring blank lines and
     * comment lines (lines starting with '#').
     */
    static Set<String> loadConceptIds(Path file) {
        Set<String> ids = new LinkedHashSet<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    if (trimmed.matches("\\d+")) {
                        ids.add(trimmed);
                    }
                    // Silently skip non-numeric lines (e.g. accidental blank lines with spaces)
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read exception file " + file + ": " + e.getMessage());
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * Reads concept IDs from a user-supplied file (for add/remove operations).
     * Returns null if the file cannot be read (error already printed).
     */
    static Set<String> readConceptIdsFromUserFile(String filePath) {
        Path path;
        try {
            path = Paths.get(filePath).toAbsolutePath().normalize();
        } catch (Exception e) {
            System.err.println("Error: Invalid file path: " + filePath);
            return null;
        }
        if (!Files.exists(path)) {
            System.err.println("Error: Concepts file not found: " + filePath);
            return null;
        }
        if (!Files.isReadable(path)) {
            System.err.println("Error: Concepts file is not readable: " + filePath);
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    if (trimmed.matches("\\d+")) {
                        ids.add(trimmed);
                    } else {
                        System.err.println("Warning: Skipping non-numeric concept ID in input file: '" + trimmed + "'");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error: Could not read concepts file: " + e.getMessage());
            return null;
        }
        return ids;
    }

    static void saveExceptionFile(GpsValidator.ValidationTest test, Set<String> ids) {
        Path file = getExceptionFile(test);
        try {
            Files.createDirectories(file.getParent());
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                w.println("# Exception list for validation test: [" + test.code + "] " + test.name);
                w.println("# " + test.description);
                w.println("# One concept ID per line. Lines starting with '#' are comments.");
                w.println();
                ids.stream().sorted().forEach(w::println);
            }
        } catch (IOException e) {
            System.err.println("Error: Could not write exception file: " + e.getMessage());
        }
    }

    /**
     * Resolves a user-supplied string to a {@link GpsValidator.ValidationTest}.
     *
     * <p>Matching order:
     * <ol>
     *   <li>Exact code match, case-insensitive (e.g. {@code "e10"} → {@code E10})</li>
     *   <li>Enum name match, case-insensitive (e.g. {@code "no_blank_preferred_term"})</li>
     * </ol>
     *
     * @param key a test code or enum name supplied by the user
     * @return the matching test, or {@code null} if no match is found
     */
    static GpsValidator.ValidationTest resolveTest(String key) {
        if (key == null) return null;
        String upper = key.toUpperCase(Locale.ROOT);
        // 1. Try code match (E10, W03, etc.)
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            if (t.code.equalsIgnoreCase(key)) return t;
        }
        // 2. Try enum name match (NO_BLANK_PREFERRED_TERM, etc.)
        for (GpsValidator.ValidationTest t : GpsValidator.ValidationTest.values()) {
            if (t.name().equalsIgnoreCase(upper)) return t;
        }
        return null;
    }

    static void printUsage() {
        System.err.println("Usage: java -jar <jarfile> exception-list <sub-command> <CODE|NAME> [concepts-file]");
        System.err.println();
        System.err.println("Sub-commands:");
        System.err.println("  list   <CODE|NAME>                 List all concept IDs excepted from a test");
        System.err.println("  add    <CODE|NAME> <concepts-file> Add concept IDs from a file to the exception list");
        System.err.println("  remove <CODE|NAME> <concepts-file> Remove concept IDs in a file from the exception list");
        System.err.println();
        System.err.println("CODE is the stable test identifier shown by list-validations, e.g.: E10, W03");
        System.err.println("NAME is the enum name of the test, e.g.: NO_BLANK_PREFERRED_TERM");
        System.err.println("Both CODE and NAME are accepted; CODE is preferred.");
        System.err.println();
        System.err.println("Run 'list-validations' to see all codes, names, and descriptions.");
        System.err.println();
        System.err.println("The concepts-file must contain one numeric concept ID per line.");
        System.err.println("Exception lists are stored in: ./" + EXCEPTIONS_DIR + "/  (named by code, e.g. E10.txt)");
    }
}

