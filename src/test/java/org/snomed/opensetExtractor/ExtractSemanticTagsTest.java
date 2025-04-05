package org.snomed.opensetExtractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.snomed.opensetExtractor.ExtractSemanticTags;

import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractSemanticTagsTest {
    @TempDir
    Path tempDir;
    private Path inputFile;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test TSV file with proper FSN format (semantic tags in parentheses)
        inputFile = tempDir.resolve("test_input.tsv");
        String content = "Description ID\tTerm\tFSN\n" +
                "1\tHeart disease\tHeart disease (finding)\n" +
                "2\tLung cancer\tLung cancer (disorder)\n" +
                "3\tBlood test\tBlood test (procedure)\n";
        Files.write(inputFile, content.getBytes());
    }

    @Test
    void testValidExtraction() throws IOException {
        String[] args = { inputFile.toString(), "finding" };
        ExtractSemanticTags.main(args);

        Path outputFile = tempDir.resolve("finding_records.tsv");
        assertTrue(Files.exists(outputFile));

        String content = Files.readString(outputFile);
        assertTrue(content.contains("Heart disease"));
        assertFalse(content.contains("Lung cancer"));
        assertTrue(content.contains("(finding)"));  // Verify the semantic tag is present
    }

    @Test
    void testMultipleSemanticTags() throws IOException {
        String[] args = { inputFile.toString(), "finding", "disorder" };
        ExtractSemanticTags.main(args);

        Path outputFile = tempDir.resolve("finding_disorder_records.tsv");
        assertTrue(Files.exists(outputFile));

        String content = Files.readString(outputFile);
        assertTrue(content.contains("Heart disease"));
        assertTrue(content.contains("Lung cancer"));
        assertFalse(content.contains("Blood test"));
        assertTrue(content.contains("(finding)"));   // Verify the semantic tags are present
        assertTrue(content.contains("(disorder)"));
    }

    @Test
    void testInvalidInputFile() {
        String[] args = { "nonexistent.tsv", "finding" };
        assertThrows(IllegalArgumentException.class, () -> ExtractSemanticTags.main(args));
    }

    @Test
    void testInsufficientArguments() {
        String[] args = { inputFile.toString() };
        assertThrows(IllegalArgumentException.class, () -> ExtractSemanticTags.main(args));
    }

    @Test
    void testEmptySemanticTag() {
        String[] args = { inputFile.toString(), "" };
        assertThrows(IllegalArgumentException.class, () -> ExtractSemanticTags.main(args));
    }
}