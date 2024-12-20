# SNOMED CT Openset file creator

A utility tool for extracting and processing SNOMED CT terminology data from an RF2 release and producing the SNOMED International openset format.

## Overview

This tool provides two main functionalities:
1. Extracting terms from SNOMED CT source files
2. Filtering terms by semantic tags

## Prerequisites

- Java Runtime Environment (JRE) 8 or higher
- SNOMED CT release files (RF2 format)
  - Concept file
  - Description file
  - Language reference set file (preferences)

## Usage

### Building the JAR
The application can be built using Maven:

```bash
mvn clean package
```

This will create a JAR file in the `target` directory.

### 1. Term Extraction

Run the JAR file with the extract-terms command:

```bash
java -jar target/opensetextractor*.jar extract-terms <concepts-file> <descriptions-file> <preferences-file> <output-file>
```

Parameters:
- `concepts-file`: Path and filename of the SNOMED CT concepts file
- `descriptions-file`: Path and filename of the descriptions file
- `preferences-file`: Path and filename of the language preferences file
- `output-file`: Desired name for the output TSV file

Example:
```bash
java -jar target/opensetextractor*.jar extract-terms demoFiles/concepts.txt demoFiles/descriptions.txt demoFiles/preferences.txt openSCT.tsv
```

### 2. Semantic Tag Filtering

Filter terms by semantic tags using:

```bash
java -jar target/opensetextractor*.jar extract-tags <input-file> <semantic-tag1> <semantic-tag2> <...>
```

Parameters:
- `input-file`: Path and filename of the processed SNOMED CT file (generally the output from ExtractTerms or the downloaded OpenSet file)
- `semantic-tag`: The semantic tags to filter by, using the full tag name in quotes, e.g. "disorder" or "medicinal product"

Example:
```bash
java -jar target/opensetextractor*.jar extract-tags demoFiles/openSCT.txt disorder "medicinal product"
```

Common semantic tags :
- finding
- disorder
- procedure
- organism
- substance
- body structure
- observable entity
More information on semantic tags can be found here - https://confluence.ihtsdotools.org/display/DOCEG/Semantic+Tag


## File Format

### Input Files
The tool expects SNOMED CT RF2 release files in their standard format:
- Concepts file: Contains concept IDs and metadata
- Descriptions file: Contains terms and descriptions
- Language preferences file: Contains preferred terms for specific languages

### Output Format
The generated output file is tab-separated (TSV) and contains the following columns:
1. SCTID (SNOMED CT Identifier)
2. Active flag
3. FSN (Fully Specified Name)
4. Preferred Term in International English

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
- The program will display appropriate error messages if:
  - Input files are not found
  - Files are in incorrect format
  - Invalid semantic tags are specified

## License
Apache, version 2.0 - see LICENSE file
