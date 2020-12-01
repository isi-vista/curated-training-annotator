# curated-training-annotator

Tools for the curated training annotation process:

The easiest way to run each individual step is through IntelliJ.
Each of them take a single parameter file as input. Sample files for each 
script can be found in `sample_params/`.

# Getting Started
You will need to have the following installed to work with the annotation
utilities:

* [Oracle JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)
* [Apache Maven 3.6.3](https://maven.apache.org/download.cgi)

Once you have both set up, in the working directory run
```
mvn clean install
```

# Generating Inception Projects
`ProjectGenerator.kt` will produce Inception annotation projects for the
cross-product of a set of users and the event types of (by default) an AIDA
ontology. Each generated project will have the naming convention
`optionalProjectPrefix-Event.Type-username`

You may create projects using a custom ontology by using a JSON file like
`docs/aida_changes_0514.json`.

# Exporting Annotations
Use `ExportAnnotations.kt` to pull all projects from an `INCEpTION` server.

# Restoring Corpus Text
If you have access to the LDC database, then you can put the full document
text into the exported JSON files using `RestoreJson.kt`.

Before running this script on projects that use the Gigaword 5 corpus,
you must have the indices, which can be generated from
https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java .
You must unzip all individual `.gz` files in the corpus before running
IndexFlatGigaword.

Use the directory path where you saved the indices as the value of
`indexDirectory`.

# Getting Annotation Statistics
To see the statistics of the annotation projects, run `ExtractAnnotationStats.kt`
on the JSON annotation files.

This will output HTML/JSON reports on the **number of sentences**
annotated per project type, annotator, event type, and individual project,
as well as estimations of their annotation rates. There will also be
separate JSON reports on **indicator searches** and
**cumulative times spent** on each project.

# Running the Export Pipeline
To run each step in the annotation pipeline and save/push the data to a
Github repository, use `PushAnnotations.kt`. This takes a single paramter
file containing all the parameters needed to run each of the following
steps.

1. Set up existing local copy of repository
2. ExportAnnotations
3. ExtractAnnotationStats
4. If you want to restore the corpus text (`restoreJson: true`):
    1. RestoreJson
    2. [curated_training_ingester](https://github.com/isi-vista/gaia-event-extraction/blob/master/gaia_event_extraction/ingesters/curated_training_ingester.py) (from `gaia-event-extraction`; see below)
5. Push changes to remote repository

For the repository setup to succeed, the working directory will need
to be clean (i.e. there should be no local modifications) and be on
the `master` branch. The remote URL also needs to match that of
the remote repository.

If restoring the original text, you will also need local copies of
https://github.com/isi-vista/isi-flexnlp, https://github.com/isi-vista/vistautils,
and https://github.com/isi-vista/gaia-event-extraction . If you need
access to any of these, let one of the admins know.

To run PushAnnotations from the command line, `cd` to the
`curated-training-annotator` directory and enter
```
sh annotation-utils/target/appassembler/bin/pushAnnotations "path/to/parameter/file"
```

## Note on JVM code
If you load the JVM code for this project up in IntelliJ, be sure to enable
annotation processing in your preferences. You may then need to do a Maven
reimport after the first build.
