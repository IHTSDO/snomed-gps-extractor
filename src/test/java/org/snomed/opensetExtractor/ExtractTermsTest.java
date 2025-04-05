package org.snomed.opensetExtractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.snomed.opensetExtractor.ExtractTerms;

import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtractTermsTest {
        @TempDir
        Path tempDir;
        private Path conceptsFile;
        private Path descriptionsFile;
        private Path preferencesFile;
        private Path outputFile;

        @BeforeEach
        void setUp() throws IOException {
                // Create test files
                conceptsFile = tempDir.resolve("concepts.txt");
                descriptionsFile = tempDir.resolve("descriptions.txt");
                preferencesFile = tempDir.resolve("preferences.txt");
                outputFile = tempDir.resolve("output.tsv");

                // Create concepts file
                String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                "123\t20240101\t1\t900000000000207008\t900000000000074008\n";
                Files.write(conceptsFile, conceptsContent.getBytes());

                // Create descriptions file
                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n" +
                                "456\t20240101\t1\t900000000000207008\t123\ten\t900000000000003001\tHeart (FSN)\n" +
                                "789\t20240101\t1\t900000000000207008\t123\ten\t900000000000013009\tHeart\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                // Create preferences file
                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n" +
                                "999\t20240101\t1\t900000000000207008\t900000000000509007\t789\t900000000000548007\n";
                Files.write(preferencesFile, preferencesContent.getBytes());
        }

        @Test
        void testValidExtraction() throws IOException {
                String[] args = {
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };

                ExtractTerms.main(args);

                assertTrue(Files.exists(outputFile));
                String content = Files.readString(outputFile);
                assertTrue(content.contains("id\tactive\tfsn\tterm"));
                assertTrue(content.contains("123\t1\tHeart (FSN)\tHeart"));
        }

        @Test
        void testInsufficientArguments() {
                String[] args = { conceptsFile.toString(), descriptionsFile.toString() };
                ExtractTerms.main(args);
                // Should print usage message and return without throwing exception
        }

        @Test
        void testNonexistentInputFile() throws IOException {
                String[] args = {
                                "nonexistent.txt",
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };

                ExtractTerms.main(args);
                // Should handle IOException gracefully
        }

        @Test
        void testEmptyInputFiles() throws IOException {
                // Create empty files
                Files.write(conceptsFile, "".getBytes());
                Files.write(descriptionsFile, "".getBytes());
                Files.write(preferencesFile, "".getBytes());

                String[] args = {
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };

                ExtractTerms.main(args);

                assertTrue(Files.exists(outputFile));
                assertEquals("id\tactive\tfsn\tterm\n",
                                Files.readString(outputFile));
        }
}