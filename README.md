# SNOMED CT GPS Extractor

A powerful utility tool for extracting and processing SNOMED CT terminology data from an RF2 release. It produces the SNOMED International GPS (Global Patient Set) format and offers advanced filtering capabilities via both a command-line interface (CLI) and a modern web interface.

You can also download the published GPS directly from SNOMED International at https://www.snomed.org/gps.

## Features

*   [**Term Extraction**](#gps-file-creation): Extracts concepts and terms from SNOMED CT RF2 release files into a simplified TSV format (ID, Active Status, FSN, US Preferred Term).
*   [**Semantic Tag Filtering**](#semantic-tag-filtering): Filter the extracted data based on SNOMED CT semantic tags (e.g., "disorder", "finding", "substance").
*   [**Web Interface**](#web-interface-recommended): A user-friendly web UI — [runs directly in your browser](https://ihtsdo.github.io/snomed-gps-extractor/) with no installation needed.
*   **Active Concept Filtering**: Optionally filter to include only active concepts.
*   [**Output Validation**](#validating-a-gps-extraction): Cross-reference a GPS file against its source RF2 release to verify concept counts, FSNs, preferred terms, and active flags.
*   [**US vs GB Preferred Term Check**](#us-vs-gb-preferred-term-validation): New error check (`US_PREFERRED_TERM_NOT_GB`) detects cases where the GPS output contains the GB English preferred term instead of the US English preferred term.
*   [**Implementation Guide Bundling**](#bundling-an-implementation-guide): Optionally include a GPS Implementation Guide PDF (or any file) in the output ZIP package.
*   [**Validation Test Catalogue**](#listing-validation-tests): Query all validation tests by name and description from the command line.
*   [**Concept Exception Lists**](#concept-exception-lists): Exclude specific concepts from individual validation checks without affecting other checks.
*   [**Exception Count Reporting**](#exception-counts-in-reports): The validation report's "Validation Tests Performed" section now shows how many concepts were excluded via the exception list for each test.
*   [**Condensed Validation Report**](#validation-report-structure): The "Validation Tests Performed" section is now a compact table rather than verbose multi-line blocks. Full descriptions remain available via `list-validations`.
*   **CLI Support**: Robust command-line tools for automation and batch processing.

## Prerequisites

*   **Java Development Kit (JDK)**: Version 25 or higher to build; a Java 25 runtime to run the packaged JAR.
*   **Maven**: For building the project.
*   **SNOMED CT Release Files**: You will need the standard RF2 release files (Concepts, Descriptions, and Language Preferences) or the full release ZIP.

## Installation

Clone the repository and build the project using Maven:

```bash
git clone https://github.com/IHTSDO/snomed-gps-extractor.git
cd snomed-gps-extractor
mvn clean package
```

This will create an executable JAR file in the `target` directory (e.g., `snomed-gps-extractor*.jar`).

---

## SNOMED CT GPS file creation

Extract raw terms from SNOMED CT RF2 files to create a GPS-compatible ZIP package.

### Using SNOMED CT RF2 Release ZIP file

```bash
java -jar target/snomed-gps-extractor*.jar extract-terms \
    [--active-only] \
    [--inactive-since YYYYMMDD] \
    [--implementation-guide <guide-file>] \
    <rf2-zip-file> [output-file]
```

**Options:**

*   `--active-only`: (Optional) If set, only active concepts are extracted. Default is all concepts.
*   `--inactive-since YYYYMMDD`: (Optional) Only include inactive concepts whose effective date is on or after the given date. Active concepts are always included regardless.
*   `--implementation-guide <guide-file>`: (Optional) Path to an implementation guide file (any file name accepted, e.g. a PDF). When provided, this file is bundled into the output ZIP alongside the data file and Readme.txt.

**Example — with an implementation guide:**

```bash
java -jar target/snomed-gps-extractor*.jar extract-terms \
    --inactive-since 20230101 \
    --implementation-guide GPS_ImplementationGuide_v2.pdf \
    SnomedCT_Release_INT_20260101.zip
```

The output ZIP will contain:
```
SnomedINTL_GPSRelease_PRODUCTION_20260101T120000Z.txt   ← GPS data (TSV)
Readme.txt                                              ← Release notes
GPS_ImplementationGuide_v2.pdf                         ← Implementation guide (if provided)
```

### Why preferred terms are always present

The extractor resolves the US Preferred Term by finding the active description for a concept whose language-refset entry carries the PREFERRED acceptability ID (`900000000000548007`). Both the description *and* the language-refset entry must be active. This ensures that inactive descriptions (which may have active historical refset entries) are never mistakenly used — previously a root cause of blank preferred terms in the output.

---

## Bundling an Implementation Guide

The `--implementation-guide` flag accepts any file name. The file is copied verbatim into the output ZIP under its original filename. The `Readme.txt` directory listing is automatically updated to include it.

---

## Semantic Tag Filtering

### Web Interface (Recommended)

The easiest way to filter your GPS data is using the web interface. No installation required — it runs entirely in your browser.

**[Open the Web Interface](https://ihtsdo.github.io/snomed-gps-extractor/)**

Your file is processed locally in the browser and is never uploaded to any server.

1.  **Upload**: Drag and drop your SNOMED CT GPS file (TSV format).
2.  **Configure**:
    *   Toggle **"Active Concepts Only"** to exclude inactive records.
    *   Select the desired **Semantic Tags** from the categorised list.
    *   Add any **Custom Tags** if needed.
3.  **Process**: Click "Process & Download" to get your filtered dataset.

### Command line

Filter an existing GPS TSV file by semantic tags using the command line.

```bash
java -jar target/snomed-gps-extractor*.jar extract-tags [--active-only] <input-file> <tag1> [tag2 ...]
```

*   `--active-only`: (Optional) Filter for active concepts only.
*   `input-file`: The GPS file to filter.
*   `tag`: One or more semantic tags (e.g., "disorder", "body structure").

---

## Validating a GPS Extraction

After producing a GPS file, use the `validate` command to cross-reference it against the RF2 release it was extracted from. The tool independently re-reads the three source RF2 files to build a ground-truth oracle, then checks every row of the GPS output against it.

```bash
java -jar target/snomed-gps-extractor*.jar validate \
    [--active-only] [--inactive-since YYYYMMDD] \
    <rf2-zip-file> <gps-output-tsv> <report-file>
```

Pass the same filter flags (`--active-only`, `--inactive-since`) that were used during extraction so the oracle applies the same concept selection rules.

**Example:**
```bash
java -jar target/snomed-gps-extractor*.jar validate \
    --inactive-since 20230101 \
    SnomedCT_Release_INT_20260101.zip \
    gps_output.tsv \
    validation_report.txt
```

### Validation progress indicator

The validate command shows a real-time progress indicator as it works through each phase:

```
[1/4] Extracting RF2 files...          done
[2/4] Building expected output oracle... done (450,123 concepts)
[3/4] Validating GPS output...          done (0 error(s), 2 warning(s))
[4/4] Writing report...                 done

Result: PASS with Warnings (2 warning(s))
Report written to: validation_report.txt
```

### Validation result: PASS and FAIL

The report has two severity levels:

| Severity | Meaning |
|----------|---------|
| **ERROR** | A definite data quality problem. Any ERROR causes the overall result to be **FAIL**. |
| **WARNING** | A potential issue that warrants investigation but does not cause a FAIL. If warnings are found, the result is **PASS with Warnings** and a notice is printed on the command line. |

### Validation report structure

The validation report contains:

1.  **Header** — timestamps, source paths, and filter flags used.
2.  **Validation Tests Performed** — a condensed table of all tests run, showing code, severity, name, and the number of concept IDs excluded via the exception list for each test. A `-` indicates no exceptions were active. Full descriptions for each test are available via `list-validations`.
3.  **Overall Summary** — concept count, error count, warning count, and overall PASS/FAIL result.
4.  **Errors section** (if any) — an **Error Summary** at the top groups violations by test type, shows a total count for each, and includes overlap notes (see below), followed by the full list of individual violations.
5.  **Warnings section** (if any) — same structure as the Errors section.

#### Exception counts in reports

The "Validation Tests Performed" table includes an **Excluded** column showing how many concept IDs were suppressed by the exception list for each test. For example:

```
  Code   Severity  Test name                                      Excluded
  ------  --------  ----------------------------------------------  --------
  E08    ERROR     Preferred term matches source                  42
  W01    WARNING   FSN ends with a valid semantic tag             -
```

This makes it immediately visible which tests had known exceptions applied, and how many concepts were involved.


#### Overlap detection in the report

The summary automatically detects when the same concepts appear in multiple violation groups. For example:

> Preferred term is blank (NO_BLANK_PREFERRED_TERM): 1,008 violation(s)
> No trailing whitespace on rows (NO_TRAILING_WHITESPACE): 1,008 violation(s)
>
> Note on overlaps: The identical count of 1,008 between 'NO_BLANK_PREFERRED_TERM' and
> 'NO_TRAILING_WHITESPACE' occurs because the same 1,008 concept(s) triggered both
> violations on the same row.

### Listing validation tests

To see the full catalogue of validation tests — including their names, severity levels (ERROR or WARNING), and descriptions — run:

```bash
java -jar target/snomed-gps-extractor*.jar list-validations
```

This prints all tests grouped by severity without requiring any input files.

### Validation checks performed

#### ERROR checks (any failure → FAIL)

| # | Check |
|---|-------|
| E01 | Output file header is exactly: `ConceptID \| Active \| FSN \| USPreferredTerm` |
| E02 | Every data row has exactly 4 tab-separated columns |
| E03 | No concept ID appears more than once in the output |
| E04 | Every concept in the source RF2 (after applying filter flags) is present in the output |
| E05 | No concept appears in the output that is absent from the source RF2 or was filtered out |
| E06 | The `active` flag on each row matches the source concept file |
| E07 | The FSN on each row is the active FSN from the source descriptions file |
| E08 | The preferred term on each row is the active Preferred synonym per the language refset |
| E09 | No blank FSN values |
| E10 | No blank preferred term values (active concepts only — inactive concepts may legitimately lack a US Preferred Term) |
| E11 | File ends with a newline on the final row |
| E12 | No non-UTF-8 characters in any field |
| E13 | Total record count equals active + qualifying inactive concepts from RF2 source |
| E14 | File contains at least one data row (not just a header) |
| E15 | No UTF-8 Byte Order Mark (BOM) at start of file |
| E16 | File uses Unix LF line endings, not Windows CRLF |
| E17 | No blank ConceptID values |
| E18 | ConceptID is numeric on every row |
| E19 | Active flag is strictly `0` or `1` on every row |
| E20 | The `USPreferredTerm` column contains the US English preferred term, not the GB English preferred term. Any concept whose output term matches only the GB preferred synonym (refset `900000000000508004`) and not the US preferred synonym (refset `900000000000509007`) is flagged. |

#### WARNING checks (failures do not cause FAIL)

| # | Check |
|---|-------|
| W01 | Every non-empty active FSN ends with a parenthesised semantic tag from the allowed list (e.g. `(disorder)`). Inactive concepts are excluded. |
| W02 | Active FSN contains exactly one semantic tag. Inactive concepts are excluded. |
| W03 | FSN and preferred term are not identical for active concepts. Inactive concepts are excluded. |
| W04 | Preferred term does not end with a semantic tag (i.e. FSN was not used as preferred term). Inactive concepts are excluded. |
| W05 | No trailing whitespace on any row. |

### Inactive concept exclusions

The following checks apply only to **active** concepts (where `active = 1`). They are silently skipped for inactive concepts, which may legitimately lack preferred terms or well-formed FSNs:

*   W01 — FSN ends with a valid semantic tag
*   W02 — FSN contains exactly one semantic tag
*   W03 — FSN and preferred term are not identical
*   W04 — Preferred term does not look like an FSN
*   E10 — No blank preferred term values

### Semantic tag validation

Semantic tags in FSNs are validated by checking the **last pair of parentheses** in the term. This correctly handles FSNs with nested parentheses such as:

```
Transplantation of bone marrow (bone marrow transplant) (procedure)
```

In this case, the semantic tag is `procedure` (the last parenthesised group), and the earlier parentheses are treated as part of the term text.

Allowed semantic tags are loaded from the bundled `allowed_semantic_tags.txt` resource file.

---

### US vs GB preferred term validation

The SNOMED CT INT release ships a single combined language refset file that contains entries for **both** US English (refset `900000000000509007`) and GB English (refset `900000000000508004`). A description can appear in both refsets with *different* acceptability values — for example, "Paracetamol" may be the GB preferred synonym for a concept while "Acetaminophen" is the US preferred synonym.

Check **E20 — Preferred term is US English, not GB English** detects when the `USPreferredTerm` column in the output contains the GB preferred synonym rather than the US preferred synonym. This can happen if the extraction logic fails to filter to the US refset and the last-write-wins ordering happens to overwrite the US row with the GB row.

The check fires only when **all three** conditions are true:
1. The concept has a non-empty GB preferred term.
2. The GB preferred term differs from the US preferred term.
3. The output's `USPreferredTerm` value matches the GB preferred term.

Concepts where US and GB terms are identical, or where no GB preferred term exists, are silently skipped.

This check can be suppressed for known acceptable exceptions using the exception list:
```bash
java -jar target/snomed-gps-extractor*.jar exception-list add US_PREFERRED_TERM_NOT_GB my_exceptions.txt
```

---

## Concept Exception Lists

The exception-list feature lets you exclude specific concept IDs from individual validation checks without disabling the check globally. This is useful for known data anomalies that you have reviewed and accepted.

Exception lists are stored as plain-text files in a `.gps-exceptions/` directory relative to the current working directory. Each validation test has its own exception file, named after the test's enum identifier (e.g., `.gps-exceptions/NO_BLANK_PREFERRED_TERM.txt`). Files contain one numeric concept ID per line; lines starting with `#` are treated as comments.

### Listing excepted concepts for a test

```bash
java -jar target/snomed-gps-extractor*.jar exception-list list <TEST_NAME>
```

**Example:**
```bash
java -jar target/snomed-gps-extractor*.jar exception-list list NO_BLANK_PREFERRED_TERM
```

### Adding concepts to an exception list

```bash
java -jar target/snomed-gps-extractor*.jar exception-list add <TEST_NAME> <concepts-file>
```

The `concepts-file` must contain one numeric concept ID per line.

**Example:**
```bash
java -jar target/snomed-gps-extractor*.jar exception-list add NO_BLANK_PREFERRED_TERM my_exceptions.txt
```

### Removing concepts from an exception list

```bash
java -jar target/snomed-gps-extractor*.jar exception-list remove <TEST_NAME> <concepts-file>
```

**Example:**
```bash
java -jar target/snomed-gps-extractor*.jar exception-list remove NO_BLANK_PREFERRED_TERM concepts_to_remove.txt
```

### Finding the correct TEST_NAME

Run `list-validations` to see all test names and their descriptions:

```bash
java -jar target/snomed-gps-extractor*.jar list-validations
```

---

## File Formats

### Output Format (GPS)

The tool produces a Tab-Separated Values (TSV) file with the following columns:

| ConceptID | Active | FSN | USPreferredTerm |
|-----------|--------|-----|----------------|
| 73211009 | 1 | Diabetes mellitus (disorder) | Diabetes mellitus |
| 101009 | 0 | Inactive concept (disorder) | Inactive concept |

---

## Security

The following security measures are applied:

*   **Zip-slip prevention**: When extracting entries from RF2 ZIP files, each entry's resolved path is checked against the target directory. Any entry whose path would escape the temp directory (a zip-slip attack) causes extraction to abort with an error. This applies to both the extractor and the validator.
*   **Zip-bomb / decompression attack prevention**: Each ZIP entry's declared uncompressed size is validated before extraction begins. If the declared size exceeds 2 GB, extraction is aborted immediately. Additionally, a `LimitedInputStream` wrapper counts bytes actually written during extraction and aborts if they exceed the limit — catching entries that declare a size of 0 or -1 (unknown). Both the extractor (`ExtractTerms`) and the validator (`GpsValidator`) enforce this limit.
*   **HTTP header injection prevention** (web server mode): The filename returned by `getOriginalFilename()` is attacker-controlled. Before it is embedded in the `Content-Disposition` response header, it is sanitised by removing carriage returns, line feeds, double-quotes, and path separators — characters that can be used for HTTP response-splitting or header-injection attacks. The result is then RFC 6266-quoted (`filename="..."`) so that spaces and other special characters cannot break the header value.
*   **Semantic tag input validation** (web server mode): The `tags` request parameter is validated for maximum length (4,096 characters), maximum tag count (100), maximum individual tag length (100 characters), and against an allowlist of permitted characters (ASCII letters, digits, spaces, and hyphens). Requests that fail validation receive a 400 Bad Request response.
*   **Security response headers** (web server mode): API responses include `X-Content-Type-Options: nosniff` (prevents MIME-type sniffing) and `X-Frame-Options: DENY` (prevents clickjacking via framing).
*   **Explicit UTF-8 throughout**: All file I/O uses an explicit `StandardCharsets.UTF_8` charset rather than the JVM platform default. This prevents data corruption or silent misreading of SNOMED terminology files on platforms where the default charset differs from UTF-8, and ensures the validation report is always written in UTF-8.
*   **Input validation**: All input file paths are validated for existence and readability before processing begins.
*   **Date format validation**: The `--inactive-since` argument is validated against the regex `\d{8}` before use.
*   **Exception list path sanitisation**: Concept-file paths supplied to `exception-list add/remove` are resolved to absolute, normalised paths before reading.
*   **Implementation guide path sanitisation**: The path supplied to `--implementation-guide` is resolved to an absolute, normalised path before being copied into the output ZIP.
*   **Numeric concept ID enforcement**: The exception-list reader rejects any non-numeric lines, preventing injection of arbitrary content into exception files.


---

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

---
&copy; 2026 SNOMED International.
