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

### 1. Term Extraction

The `ExtractTerms` class processes SNOMED CT source files and combines them into a single, tab-separated output file.

```bash
java ExtractTerms <concepts-file> <descriptions-file> <preferences-file> <output-file>
```

Parameters:
- `concepts-file`: Path to the SNOMED CT concepts file
- `descriptions-file`: Path to the descriptions file
- `preferences-file`: Path to the language preferences file
- `output-file`: Desired name for the output TSV file

Example:
```bash
java ExtractTerms demoFiles/concepts.txt demoFiles/descriptions.txt demoFiles/preferences.txt openSCT.tsv
```

### 2. Semantic Tag Filtering

The `ExtractSemanticTags` command allows you to filter terms by their semantic tags (e.g., "finding", "disorder", "procedure").

```bash
java ExtractSemanticTags <input-file> <semantic-tag>
```

Parameters:
- `input-file`: Path to the processed SNOMED CT file (output from ExtractTerms)
- `semantic-tag`: The semantic tag to filter by (case-sensitive)

Example:
```bash
java ExtractSemanticTags demoFiles/openSCT.txt finding
```

Common semantic tags:
- finding
- disorder
- procedure
- organism
- substance
- body structure
- observable entity

## File Format

### Input Files
The tool expects SNOMED CT RF2 release files in their standard format:
- Concepts file: Contains concept IDs and metadata
- Descriptions file: Contains terms and descriptions
- Language preferences file: Contains preferred terms for specific languages

### Output Format
The generated output file is tab-separated (TSV) and contains the following columns:
- Concept ID
- Term
- Semantic Tag
- Additional metadata (if applicable)

## Notes
- Ensure all input files are in the correct RF2 format
- File paths can be relative or absolute
- The semantic tag filter is case-sensitive

## Error Handling
- The program will display appropriate error messages if:
  - Input files are not found
  - Files are in incorrect format
  - Invalid semantic tags are specified

## License
Apache, version 2.0 - see LICENSE file
