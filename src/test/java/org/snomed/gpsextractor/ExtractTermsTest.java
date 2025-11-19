package org.snomed.gpsextractor;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

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
                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                +
                                "456\t20240101\t1\t900000000000207008\t123\ten\t900000000000003001\tHeart (FSN)\n" +
                                "789\t20240101\t1\t900000000000207008\t123\ten\t900000000000013009\tHeart\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                // Create preferences file
                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                +
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

        @Test
        void testPreferenceBasedSelection() throws IOException {
                // Concept 100
                String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                "100\t20240101\t1\t900000000000207008\t900000000000074008\n";
                Files.write(conceptsFile, conceptsContent.getBytes());

                // Two synonyms for Concept 100:
                // Desc 101: "Bad Term"
                // Desc 102: "Good Term"
                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                +
                                "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tBad Term\n" +
                                "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tGood Term\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                // Preferences:
                // Desc 101 is Acceptable (900000000000549004)
                // Desc 102 is Preferred (900000000000548007)
                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                +
                                "201\t20240101\t1\t900000000000207008\t900000000000509007\t101\t900000000000549004\n" +
                                "202\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n";
                Files.write(preferencesFile, preferencesContent.getBytes());

                String[] args = {
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };

                ExtractTerms.main(args);

                String content = Files.readString(outputFile);
                assertTrue(content.contains("100\t1\t\tGood Term"), "Should select 'Good Term' as it is preferred");
                assertFalse(content.contains("Bad Term"), "Should not select 'Bad Term'");
        }

        @Test
        void testZipProcessing() throws IOException {
                // Create a mock ZIP file
                Path zipPath = tempDir.resolve("test.zip");
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                                new java.io.FileOutputStream(zipPath.toFile()))) {
                        // Add Concept file
                        java.util.zip.ZipEntry conceptEntry = new java.util.zip.ZipEntry(
                                        "Snapshot/Terminology/sct2_Concept_Snapshot_INT_20240101.txt");
                        zos.putNextEntry(conceptEntry);
                        String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                        "123\t20240101\t1\t900000000000207008\t900000000000074008\n";
                        zos.write(conceptsContent.getBytes());
                        zos.closeEntry();

                        // Add Description file
                        java.util.zip.ZipEntry descEntry = new java.util.zip.ZipEntry(
                                        "Snapshot/Terminology/sct2_Description_Snapshot-en_INT_20240101.txt");
                        zos.putNextEntry(descEntry);
                        String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                        +
                                        "456\t20240101\t1\t900000000000207008\t123\ten\t900000000000003001\tHeart (FSN)\n"
                                        +
                                        "789\t20240101\t1\t900000000000207008\t123\ten\t900000000000013009\tHeart\n";
                        zos.write(descriptionsContent.getBytes());
                        zos.closeEntry();

                        // Add Language Refset file
                        java.util.zip.ZipEntry refsetEntry = new java.util.zip.ZipEntry(
                                        "Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20240101.txt");
                        zos.putNextEntry(refsetEntry);
                        String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                        +
                                        "999\t20240101\t1\t900000000000207008\t900000000000509007\t789\t900000000000548007\n";
                        zos.write(preferencesContent.getBytes());
                        zos.closeEntry();
                }

                String[] args = {
                                zipPath.toString(),
                                outputFile.toString()
                };

                ExtractTerms.main(args);

                assertTrue(Files.exists(outputFile));
                String content = Files.readString(outputFile);
                assertTrue(content.contains("123\t1\tHeart (FSN)\tHeart"));
        }
}