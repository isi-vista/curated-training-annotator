package edu.isi.vista.annotationutils

import edu.isi.nlp.io.OffsetIndexedCorpus
import edu.isi.nlp.symbols.Symbol
import edu.isi.nlp.io.DocIDToFileMappings
import com.google.common.base.Optional
import com.fasterxml.jackson.databind.ObjectMapper
import edu.isi.nlp.io.OriginalTextSource
import edu.isi.nlp.parameters.Parameters
import java.io.File
import java.nio.file.Paths

/**
 * Puts the document text back into the 'sofaString' field inside json files created by `ExportAnnotations.kt`
 * They are redacted from the json export because our LDC license does not permit us to distribute the full document text.
 *
 *
 * The program takes one argument, a parameter file in the format described at
 * https://github.com/isi-vista/nlp-util/blob/master/common-core-open/src/main/java/edu/isi/nlp/parameters/serifstyle/SerifStyleParameterFileLoader.java
 *
 * An example is provided in curated-training-annotator/sample_params/restore-text.params
 * The required parameter values are:
 * <ul>
 *     <li> {@code indexDirectory} is the location of the files produced by {@code IndexFlatGigaword.java}
(https://github.com/isi-vista/nlp-util/blob/master/nlp-core-open/src/main/java/edu/isi/nlp/corpora/gigaword/IndexFlatGigaword.java) </li>
 *     <li> {@code gigawordDataDirectory} is the location of the gigaword text files </li>
 *     <li> {@code inputJsonDirectory} is the location of the stripped json files produced by {@code ExportAnnotations.kt} </li>
 *     <li> {@code restoredJsonDirectory} is where the new json files will go </li>
 *  </ul>
 */

fun main(argv: Array<String>) {
    val params = Parameters.loadSerifStyle(File(argv[0]))
    val inputJsonDirectory = params.getCreatableDirectory("inputJsonDirectory")
    val outputDirectory = params.getCreatableDirectory("restoredJsonDirectory")

    // Make objects for reading, parsing, and writing json:
    val objectMapper = ObjectMapper()
    val prettyPrinter = objectMapper.writerWithDefaultPrettyPrinter()
    val textSource = makeTextSource(params)

    for (projectDir in inputJsonDirectory.listFiles()) {
        if (!projectDir.isDirectory()) {
            continue
        }
        val projectOutDir = File(outputDirectory, projectDir.name)
        projectOutDir.mkdir()
        for (jsonFile in projectDir.listFiles()) {
            val jsonTree = objectMapper.readTree(jsonFile)
            // First 21 characters of the filename are the document id
            // Example filename is AFP_ENG_19960918.0012-admin.json
            val docID = Symbol.from(jsonFile.name.substring(0, 21))
            val text = textSource.getOriginalText(docID).orNull()
                    ?: throw RuntimeException("Could not get original text for $docID")
            jsonTree.replaceFieldEverywhere("sofaString", text)
            val outFile = File(projectOutDir, jsonFile.name)
            outFile.writeBytes(prettyPrinter.writeValueAsBytes(jsonTree))
        }
    }
}

fun makeTextSource(params: Parameters): OriginalTextSource {
    // Create an OriginalTextSource for getting original document text to put in json:
    val textMap = DocIDToFileMappings.forFunction { symbol ->
        Optional.of(File(params.getExistingDirectory("gigawordDataDirectory"), docIDToFilename(symbol)))
    }
    val indexMap = DocIDToFileMappings.forFunction { symbol ->
        Optional.of(File(params.getExistingDirectory("indexDirectory"), docIDToFilename(symbol) + ".index"))
    }
    return OffsetIndexedCorpus.fromTextAndOffsetFiles(textMap, indexMap)
}

fun docIDToFilename(symbol: Symbol?): String {
    if (symbol == null) {
        throw RuntimeException("Got null symbol")
    }
    // e.g. AFP_ENG_19960918.0012 -> afp_eng/afp_eng_199609
    val st = symbol?.asString()?.toLowerCase()
    val dir = st?.substring(0, 7) //
    val basename = st?.substring(0, 14)
    return Paths.get(dir, basename).toString()
}
