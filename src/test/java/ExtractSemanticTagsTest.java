package org.snomed.opensetExtractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import org.ihtsdo.opensetExtractor.ExtractSemanticTags;

class ExtractSemanticTagsTest {
    @TempDir
    Path tempDir;
    private Path inputFile;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test TSV file
        inputFile = tempDir.resolve("test_input.tsv");
        String content = "Description ID\tTerm\tSemantic Tag\n" +
                "1\tHeart disease\tfinding\n" +
                "2\tLung cancer\tdisorder\n" +
                "3\tBlood test\tprocedure\n";
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