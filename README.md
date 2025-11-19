# SNOMED CT GPS file creator

A utility tool for extracting and processing SNOMED CT terminology data from an RF2 release and producing the SNOMED International GPS format.

## Overview

This tool provides three main functionalities:
1. Extracting terms from SNOMED CT source files
2. Filtering terms by semantic tags (CLI)
3. Web Interface for easy semantic tag filtering

## Prerequisites

- Java Runtime Environment (JRE) 17 or higher
- SNOMED CT release file zipfile (RF2 format)

## Usage

### Building the JAR
The application can be built using Maven:

```bash
mvn clean package
```

This will create a JAR file in the `target` directory.

### 1. Term Extraction

Run the JAR file with the extract-terms command. You can either provide the SNOMED CT release ZIP file directly or individual files.

**Option 1: Using Release ZIP file (Recommended)**

```bash
java -jar target/snomed-gps-extractor*.jar extract-terms [--active-only] <zip-file> <output-file>
```

Parameters
- `--active-only`: (Optional) If specified, only active concepts will be extracted. By default, all concepts (active and inactive) are processed.
- `zip-file`: Path and filename of the SNOMED CT release zipfile
- `output-file`: Desired name for the output TSV file

Example:
```bash
java -jar target/snomed-gps-extractor*.jar extract-terms SnomedCT_InternationalRF2_PRODUCTION_20250901T120000Z.zip output.tsv
```

**Option 2: Using individual files**

```bash
java -jar target/snomed-gps-extractor*.jar extract-terms [--active-only] <concepts-file> <descriptions-file> <preferences-file> <output-file>
```

Parameters:
- `concepts-file`: Path and filename of the SNOMED CT concepts file
- `descriptions-file`: Path and filename of the descriptions file
- `preferences-file`: Path and filename of the language preferences file (Used to identify the Preferred Term)
- `output-file`: Desired name for the output TSV file

Example:
```bash
java -jar target/snomed-gps-extractor*.jar extract-terms demoFiles/concepts.txt demoFiles/descriptions.txt demoFiles/preferences.txt openSCT.tsv
```

### 2. Semantic Tag Filtering

Filter terms by semantic tags using:

```bash
java -jar target/snomed-gps-extractor*.jar extract-tags [--active-only] <input-file> <semantic-tag1> <semantic-tag2> <...>
```

Parameters:
- `--active-only`: (Optional) If specified, only active concepts will be extracted.
- `input-file`: Path and filename of the processed SNOMED CT file (generally the output from ExtractTerms or the downloaded OpenSet file)
- `semantic-tag`: The semantic tags to filter by, using the full tag name in quotes, e.g. "disorder" or "body structure"

Example:
```bash
java -jar target/snomed-gps-extractor*.jar extract-tags demoFiles/openSCT.txt "disorder" "body structure"
```

### 3. Web Interface

A user-friendly web interface is available for filtering semantic tags.

Start the server:

```bash
java -jar target/snomed-gps-extractor*.jar server
```

Or simply run without arguments:

```bash
java -jar target/snomed-gps-extractor*.jar
```

Then open your browser and navigate to `http://localhost:8080`.

The web interface allows you to:
1. Upload a TSV file (output from Term Extraction).
2. Enter a list of semantic tags (comma-separated).
3. Process and download the filtered file.

Common semantic tags :
- finding
- disorder
- procedure
- organism
- substance
- body structure
- observable entity

More information on semantic tags can be found here - https://docs.snomed.org/snomed-ct-specifications/snomed-ct-editorial-guide/readme/authoring/general-naming-conventions/descriptions/fully-specified-name/index 


## File Format

### Output Format
The generated output file is tab-separated (TSV) and contains the following columns:
- id (SNOMED CT Concept Identifier)
- active (Active flag)
- fsn (Fully Specified Name)
- term (Preferred Term in International English)

Example output:
```
id active fsn term
73211009	1	Diabetes mellitus (disorder)	Diabetes mellitus
44054006	1 Diabetes mellitus type 2 (disorder) Type 2 diabetes mellitus
```

## Notes
- Ensure all input files are in the correct RF2 format
- File paths can be relative or absolute
- The semantic tag filter is case-sensitive and each semantic should be in quotes, e.g. "disorder" or "medicinal product"

## Error Handling
The program will display appropriate error messages if:
- Input files are not found
- Files are in incorrect format
- Invalid semantic tags are specified

## License
Apache, version 2.0 - see LICENSE file
