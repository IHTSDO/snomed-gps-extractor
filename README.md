# SNOMED CT GPS Extractor

A powerful utility tool for extracting and processing SNOMED CT terminology data from an RF2 release. It produces the SNOMED International GPS (Global Patient Set) format and offers advanced filtering capabilities via both a command-line interface (CLI) and a modern web interface.

## Features

*   **Term Extraction**: Extracts concepts and terms from SNOMED CT RF2 release files into a simplified TSV format (ID, Active Status, FSN, Term).
*   **Semantic Tag Filtering**: Filter the extracted data based on SNOMED CT semantic tags (e.g., "disorder", "finding", "substance").
*   **Active Concept Filtering**: Optionally filter to include only active concepts.
*   **Web Interface**: A user-friendly, dark-themed web UI for easy file uploading and semantic tag filtering.
*   **CLI Support**: Robust command-line tools for automation and batch processing.

## Prerequisites

*   **Java Runtime Environment (JRE)**: Version 17 or higher.
*   **Maven**: For building the project.
*   **SNOMED CT Release Files**: You will need the standard RF2 release files (Concepts, Descriptions, and Language Preferences) or the full release ZIP.

## Installation

Clone the repository and build the project using Maven:

```bash
git clone https://github.com/rorydavidson/snomed-gps-extractor.git
cd snomed-gps-extractor
mvn clean package
```

This will create an executable JAR file in the `target` directory (e.g., `snomed-gps-extractor-1.0.jar`).

## Usage

### 1. Web Interface (Recommended)

The easiest way to filter your GPS data is using the built-in web server.

1.  Start the server:
    ```bash
    java -jar target/snomed-gps-extractor-1.0.jar server
    ```
    *Or simply:*
    ```bash
    java -jar target/snomed-gps-extractor-1.0.jar
    ```

2.  Open your browser and navigate to `http://localhost:8080`.

3.  **Upload**: Drag and drop your SNOMED CT GPS file (TSV format).
4.  **Configure**:
    *   Toggle **"Active Concepts Only"** to exclude inactive records.
    *   Select the desired **Semantic Tags** from the categorized list.
    *   Add any **Custom Tags** if needed.
5.  **Process**: Click "Process & Download" to get your filtered dataset.

### 2. Term Extraction (CLI)

Extract raw terms from SNOMED CT RF2 files to create a GPS-compatible TSV file.

**Option 1: Using Release ZIP file**
```bash
java -jar target/snomed-gps-extractor-1.0.jar extract-terms [--active-only] <zip-file> <output-file>
```

**Option 2: Using Individual RF2 Files**
```bash
java -jar target/snomed-gps-extractor-1.0.jar extract-terms [--active-only] <concepts-file> <descriptions-file> <language-preferences-file> <output-file>
```

*   `--active-only`: (Optional) If set, only active concepts are extracted. Default is all concepts.

### 3. Semantic Tag Filtering (CLI)

Filter an existing GPS TSV file by semantic tags using the command line.

```bash
java -jar target/snomed-gps-extractor-1.0.jar extract-tags [--active-only] <input-file> <tag1> [tag2 ...]
```

*   `--active-only`: (Optional) Filter for active concepts only.
*   `input-file`: The GPS file to filter.
*   `tag`: One or more semantic tags (e.g., "disorder", "body structure").

**Example:**
```bash
java -jar target/snomed-gps-extractor-1.0.jar extract-tags --active-only output.tsv "disorder" "finding"
```

## File Formats

### Output Format (GPS)
The tool produces a Tab-Separated Values (TSV) file with the following columns:

| id | active | fsn | term |
|----|--------|-----|------|
| 73211009 | 1 | Diabetes mellitus (disorder) | Diabetes mellitus |
| 101009 | 0 | Inactive concept (disorder) | Inactive concept |

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---
&copy; 2025 SNOMED International.
