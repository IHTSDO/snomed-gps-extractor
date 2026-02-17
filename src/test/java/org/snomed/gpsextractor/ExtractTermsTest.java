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
        void testActiveOnlyFiltering() throws IOException {
                // Concept 100: Active
                // Concept 200: Inactive
                String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                "100\t20240101\t1\t900000000000207008\t900000000000074008\n" +
                                "200\t20240101\t0\t900000000000207008\t900000000000074008\n";
                Files.write(conceptsFile, conceptsContent.getBytes());

                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                +
                                "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tActive Concept (FSN)\n"
                                +
                                "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tActive Concept\n" +
                                "201\t20240101\t1\t900000000000207008\t200\ten\t900000000000003001\tInactive Concept (FSN)\n"
                                +
                                "202\t20240101\t1\t900000000000207008\t200\ten\t900000000000013009\tInactive Concept\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                +
                                "301\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n" +
                                "302\t20240101\t1\t900000000000207008\t900000000000509007\t202\t900000000000548007\n";
                Files.write(preferencesFile, preferencesContent.getBytes());

                // Test 1: Default behavior (include inactive)
                String[] argsDefault = {
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(argsDefault);
                String contentDefault = Files.readString(outputFile);
                assertTrue(contentDefault.contains("100\t1\tActive Concept (FSN)\tActive Concept"));
                assertTrue(contentDefault.contains("200\t0\tInactive Concept (FSN)\tInactive Concept"));

                // Test 2: Active only
                String[] argsActiveOnly = {
                                "--active-only",
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(argsActiveOnly);
                String contentActiveOnly = Files.readString(outputFile);
                assertTrue(contentActiveOnly.contains("100\t1\tActive Concept (FSN)\tActive Concept"));
                assertFalse(contentActiveOnly.contains("200\t0\tInactive Concept (FSN)\tInactive Concept"));
        }

        @Test
        void testInactiveSinceDateFiltering() throws IOException {
                // Concept 100: Active (always included)
                // Concept 200: Inactive, effectiveTime 20200101 (old inactivation)
                // Concept 300: Inactive, effectiveTime 20240601 (recent inactivation)
                String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                "100\t20240101\t1\t900000000000207008\t900000000000074008\n" +
                                "200\t20200101\t0\t900000000000207008\t900000000000074008\n" +
                                "300\t20240601\t0\t900000000000207008\t900000000000074008\n";
                Files.write(conceptsFile, conceptsContent.getBytes());

                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                +
                                "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tActive Concept (FSN)\n"
                                +
                                "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tActive Concept\n" +
                                "201\t20200101\t1\t900000000000207008\t200\ten\t900000000000003001\tOld Inactive (FSN)\n"
                                +
                                "202\t20200101\t1\t900000000000207008\t200\ten\t900000000000013009\tOld Inactive\n" +
                                "301\t20240601\t1\t900000000000207008\t300\ten\t900000000000003001\tRecent Inactive (FSN)\n"
                                +
                                "302\t20240601\t1\t900000000000207008\t300\ten\t900000000000013009\tRecent Inactive\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                +
                                "401\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n" +
                                "402\t20200101\t1\t900000000000207008\t900000000000509007\t202\t900000000000548007\n" +
                                "403\t20240601\t1\t900000000000207008\t900000000000509007\t302\t900000000000548007\n";
                Files.write(preferencesFile, preferencesContent.getBytes());

                // Test 1: Without --inactive-since, all concepts included
                String[] argsDefault = {
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(argsDefault);
                String contentDefault = Files.readString(outputFile);
                assertTrue(contentDefault.contains("100\t1\tActive Concept (FSN)\tActive Concept"));
                assertTrue(contentDefault.contains("200\t0\tOld Inactive (FSN)\tOld Inactive"));
                assertTrue(contentDefault.contains("300\t0\tRecent Inactive (FSN)\tRecent Inactive"));

                // Test 2: --inactive-since 20230101 should exclude concept 200 (effectiveTime 20200101)
                // but keep concept 300 (effectiveTime 20240601) and active concept 100
                String[] argsInactiveSince = {
                                "--inactive-since", "20230101",
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(argsInactiveSince);
                String contentFiltered = Files.readString(outputFile);
                assertTrue(contentFiltered.contains("100\t1\tActive Concept (FSN)\tActive Concept"),
                                "Active concepts should always be included");
                assertFalse(contentFiltered.contains("200\t0\tOld Inactive (FSN)\tOld Inactive"),
                                "Inactive concept before the date should be excluded");
                assertTrue(contentFiltered.contains("300\t0\tRecent Inactive (FSN)\tRecent Inactive"),
                                "Inactive concept on or after the date should be included");

                // Test 3: --inactive-since with exact match date should include the concept
                String[] argsExactDate = {
                                "--inactive-since", "20240601",
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(argsExactDate);
                String contentExact = Files.readString(outputFile);
                assertTrue(contentExact.contains("300\t0\tRecent Inactive (FSN)\tRecent Inactive"),
                                "Inactive concept with exact matching date should be included");
                assertFalse(contentExact.contains("200\t0\tOld Inactive (FSN)\tOld Inactive"),
                                "Inactive concept before the date should still be excluded");
        }

        @Test
        void testInactiveSinceWithActiveOnly() throws IOException {
                // When both --active-only and --inactive-since are used, --active-only takes precedence
                String conceptsContent = "id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n" +
                                "100\t20240101\t1\t900000000000207008\t900000000000074008\n" +
                                "300\t20240601\t0\t900000000000207008\t900000000000074008\n";
                Files.write(conceptsFile, conceptsContent.getBytes());

                String descriptionsContent = "id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\n"
                                +
                                "101\t20240101\t1\t900000000000207008\t100\ten\t900000000000003001\tActive (FSN)\n" +
                                "102\t20240101\t1\t900000000000207008\t100\ten\t900000000000013009\tActive\n" +
                                "301\t20240601\t1\t900000000000207008\t300\ten\t900000000000003001\tInactive (FSN)\n" +
                                "302\t20240601\t1\t900000000000207008\t300\ten\t900000000000013009\tInactive\n";
                Files.write(descriptionsFile, descriptionsContent.getBytes());

                String preferencesContent = "id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId\n"
                                +
                                "401\t20240101\t1\t900000000000207008\t900000000000509007\t102\t900000000000548007\n" +
                                "402\t20240601\t1\t900000000000207008\t900000000000509007\t302\t900000000000548007\n";
                Files.write(preferencesFile, preferencesContent.getBytes());

                String[] args = {
                                "--active-only", "--inactive-since", "20200101",
                                conceptsFile.toString(),
                                descriptionsFile.toString(),
                                preferencesFile.toString(),
                                outputFile.toString()
                };
                ExtractTerms.main(args);
                String content = Files.readString(outputFile);
                assertTrue(content.contains("100\t1\tActive (FSN)\tActive"),
                                "Active concept should be included");
                assertFalse(content.contains("300\t0\tInactive (FSN)\tInactive"),
                                "Inactive concept should be excluded when --active-only is set, even if after inactive-since date");
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