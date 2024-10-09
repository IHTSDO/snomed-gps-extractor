# opensnomed-extractor

Start by running the ExtractTerms class and passing in the file locations & names of the concepts, descriptions and preferences files as well as the name of the desired output file. i.e.

```bash
java ExtractTerms demoFiles/concepts.txt demoFiles/descriptions.txt demoFiles/preferences.txt openSCT.tsv
```

## Semantic Tag Extractor

Extract the records with a give semantic tag from the Open SNOMED CT output file using the ExtractSemanticTags command, providing the input file and the relevant semantic tag, i.e.

```bash
java ExtractSemanticTags demoFiles/openSCT.txt finding
```
