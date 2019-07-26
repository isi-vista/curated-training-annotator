# curated-training-annotator

Tools for the curated training annotation process:

# ProjectGenerator

This will produce Inception annotation projects for the cross-product of a set of users and the event types of an AIDA ontology.

# Exporting and Restoring Annotations

Use `ExportAnnotations.kt` to pull all projects from an `INCEpTION` server. If you have access to the LDC database, then you can put the full document text into the JSON files using `RestoreJson.kt`

## Note on JVM code

If you load the JVM code for this project up in IntelliJ, be sure to enable annotation
processing in your preferences. You may then need to do a Maven reimport after the first
build.
